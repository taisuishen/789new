package com.bingo789.wallet.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.Money;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.wallet.api.dto.AdjustCommand;
import com.bingo789.wallet.api.dto.BalanceView;
import com.bingo789.wallet.api.dto.BetAndPayoutCommand;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.OpenWalletCommand;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.RollbackCommand;
import com.bingo789.wallet.api.dto.UpdateUserLineCommand;
import com.bingo789.wallet.api.dto.UpdateWalletStatusCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import com.bingo789.wallet.api.enums.WalletResultCode;
import com.bingo789.wallet.api.enums.WalletStatus;
import com.bingo789.wallet.config.WalletProperties;
import com.bingo789.wallet.domain.Wallet;
import com.bingo789.wallet.domain.WalletTxn;
import com.bingo789.wallet.error.WalletErrorCode;
import com.bingo789.wallet.mapper.WalletMapper;
import com.bingo789.wallet.mapper.WalletTxnMapper;
import com.bingo789.wallet.service.WalletSignals.AlreadyApplied;
import com.bingo789.wallet.service.WalletSignals.Rejected;
import com.bingo789.wallet.service.WalletSignals.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

/**
 * Wallet entry point. Responsibilities on top of {@link WalletTxnExecutor}:
 * <ul>
 *   <li>validate and normalize amounts;</li>
 *   <li>idempotency: a duplicate request (unique-key hit) returns the first result with the current balance,
 *   which is what most provider specs require;</li>
 *   <li>a refusal (e.g. insufficient funds) is only returned after checking the request was not already
 *   applied: a retried bet that succeeded the first time must still answer success;</li>
 *   <li>re-run an operation that lost a race on its target.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final int MAX_ATTEMPTS = 3;

    private final WalletTxnExecutor executor;
    private final WalletMapper walletMapper;
    private final WalletTxnMapper txnMapper;
    private final WalletProperties properties;
    private final MeterRegistry meterRegistry;
    private final ShardTemplate shards;

    public WalletResult bet(BetCommand c) {
        return measured("bet", c.userId(), () -> {
            BigDecimal amount = Money.normalizeNonNegative(c.amount());
            IdempotencyKey key = new IdempotencyKey(c.providerCode(), c.providerTxnId(), TxnType.BET);
            return idempotent(c.userId(), c.currency(), List.of(key), () -> executor.bet(c, amount));
        });
    }

    public WalletResult payout(PayoutCommand c) {
        return measured("payout", c.userId(), () -> {
            if (!c.payoutType().isPayout()) {
                return invalid(c.currency(), c.payoutType() + " is not a payout type");
            }
            BigDecimal amount = Money.normalizeNonNegative(c.amount());
            IdempotencyKey key = new IdempotencyKey(c.providerCode(), c.providerTxnId(), c.payoutType());
            return idempotent(c.userId(), c.currency(), List.of(key), () -> executor.payout(c, amount));
        });
    }

    public WalletResult betAndPayout(BetAndPayoutCommand c) {
        return measured("bet_payout", c.userId(), () -> {
            BigDecimal bet = Money.normalizeNonNegative(c.betAmount());
            BigDecimal payout = Money.normalizeNonNegative(c.payoutAmount());
            // primary key is the payout; if only the bet key exists, the bet was tombstoned or applied separately
            List<IdempotencyKey> keys = List.of(
                    new IdempotencyKey(c.providerCode(), c.payoutTxnId(), TxnType.PAYOUT),
                    new IdempotencyKey(c.providerCode(), c.betTxnId(), TxnType.BET));
            return idempotent(c.userId(), c.currency(), keys, () -> executor.betAndPayout(c, bet, payout));
        });
    }

    public WalletResult rollback(RollbackCommand c) {
        return measured("rollback", c.userId(), () -> {
            TxnType targetType = c.effectiveTargetType();
            if (targetType != TxnType.BET && !targetType.isPayout()) {
                return invalid(c.currency(), "rollback target must be a bet or a payout");
            }
            if (c.targetTxnId() != null && !c.targetTxnId().isBlank()) {
                return reverseOne(c, c.targetTxnId(), targetType);
            }
            if (c.roundId() == null || c.roundId().isBlank()) {
                return invalid(c.currency(), "targetTxnId or roundId is required");
            }
            // round-level cancel: reverse every bet of the round; already reversed ones replay
            List<WalletTxn> bets = txnMapper.findBetsInRound(c.userId(), c.providerCode(), c.roundId());
            if (bets.isEmpty()) {
                return WalletResult.reject(WalletResultCode.TXN_NOT_FOUND, c.currency(), currentBalance(c.userId(), c.currency()),
                        "no bets in round " + c.roundId());
            }
            WalletResult last = null;
            for (WalletTxn bet : bets) {
                last = reverseOne(c, bet.getProviderTxnId(), TxnType.BET);
                if (!last.isSuccess()) {
                    return last;
                }
            }
            return last;
        });
    }

    public WalletResult adjust(AdjustCommand c) {
        return measured("adjust", c.userId(), () -> {
            BigDecimal amount = Money.normalizeSigned(c.signedAmount());
            if (Money.isZero(amount)) {
                return invalid(c.currency(), "adjustment amount must not be zero");
            }
            IdempotencyKey key = new IdempotencyKey(c.providerCode(), c.providerTxnId(), TxnType.ADJUST);
            return idempotent(c.userId(), c.currency(), List.of(key),
                    () -> executor.adjust(c, amount, properties.allowNegativeBalance()));
        });
    }

    public WalletResult platformTxn(PlatformTxnCommand c) {
        return measured("platform", c.userId(), () -> {
            if (c.txnType().category() != TxnType.Category.PLATFORM) {
                return invalid(c.currency(), c.txnType() + " is not a platform transaction");
            }
            BigDecimal amount = Money.normalizePositive(c.amount());
            IdempotencyKey key = new IdempotencyKey(c.source(), c.bizNo(), c.txnType());
            return idempotent(c.userId(), c.currency(), List.of(key), () -> executor.platform(c, amount));
        });
    }

    public BalanceView open(OpenWalletCommand c) {
        return shards.forUserWrite(c.userId(), () -> {
            executor.open(c.userId(), c.currency());
            return balance(c.userId(), c.currency());
        });
    }

    public void updateStatus(UpdateWalletStatusCommand c) {
        shards.forUserWrite(c.userId(), () -> {
            executor.updateStatus(c.userId(), c.currency(), c.status(), c.reason());
            return null;
        });
        log.info("wallet lock {} {} for user {} currency {}", c.status() == WalletStatus.ACTIVE ? "released" : "placed as " + c.status(),
                c.reason(), c.userId(), c.currency() == null ? "*" : c.currency());
    }

    public void updateUserLine(UpdateUserLineCommand c) {
        shards.forUserWrite(c.userId(), () -> {
            executor.updateUserLine(c.userId(), c.userLine(), c.version());
            return null;
        });
        log.info("user {} line set to {} (version {})", c.userId(), c.userLine(), c.version());
    }

    public BalanceView balance(long userId, String currency) {
        Wallet wallet = shards.forUser(userId, () -> walletMapper.find(userId, currency));
        if (wallet == null) {
            throw new BizException(WalletErrorCode.WALLET_NOT_FOUND);
        }
        return toView(wallet);
    }

    public List<BalanceView> balances(long userId) {
        return shards.forUser(userId, () -> walletMapper.findByUser(userId)).stream().map(WalletService::toView).toList();
    }

    // ------------------------------------------------------------------ idempotency core

    private WalletResult reverseOne(RollbackCommand c, String targetTxnId, TxnType targetType) {
        TxnType reversalType = targetType == TxnType.BET ? TxnType.ROLLBACK : TxnType.PAYOUT_REVERSAL;
        IdempotencyKey key = new IdempotencyKey(c.providerCode(), targetTxnId, reversalType);
        return idempotent(c.userId(), c.currency(), List.of(key),
                () -> executor.reverse(c, targetTxnId, targetType, properties.allowNegativeBalance()));
    }

    /**
     * @param keys idempotency keys of the rows this operation writes; the first one is the row whose
     *             result is replayed
     */
    private WalletResult idempotent(long userId, String currency, List<IdempotencyKey> keys, Supplier<WalletResult> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (Retry e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("gave up after " + attempt + " attempts: " + e.getMessage());
                }
            } catch (AlreadyApplied e) {
                return replay(userId, currency, keys, true);
            } catch (Rejected e) {
                WalletResult replayed = replay(userId, currency, keys, false);
                return replayed != null ? replayed : rejection(e, userId, currency);
            } catch (RuntimeException e) {
                if (DuplicateKeys.isDuplicateKey(e)) {
                    return replay(userId, currency, keys, true);
                }
                throw e;
            }
        }
    }

    /**
     * @param mustExist true when a unique-key hit proved a row exists; not finding it then means the key
     *                  belongs to another player (provider bug), surfaced as a system error
     */
    private WalletResult replay(long userId, String currency, List<IdempotencyKey> keys, boolean mustExist) {
        for (int i = 0; i < keys.size(); i++) {
            IdempotencyKey key = keys.get(i);
            WalletTxn txn = txnMapper.findByKey(userId, key.providerCode(), key.providerTxnId(), key.type().name());
            if (txn == null) {
                continue;
            }
            BigDecimal balance = currentBalance(userId, currency);
            if (txn.isTombstone()) {
                return WalletResult.reject(WalletResultCode.BET_CANCELLED, currency, balance, "bet was cancelled before it arrived");
            }
            if (i > 0) {
                return WalletResult.reject(WalletResultCode.INVALID_REQUEST, currency, balance,
                        key.type() + " " + key.providerTxnId() + " was already processed on its own");
            }
            if (!txn.getCurrency().equals(currency)) {
                return WalletResult.reject(WalletResultCode.INVALID_REQUEST, currency, balance, "currency does not match the original request");
            }
            return WalletResult.replay(txn.getId(), currency, balance, txn.getBalanceAfter());
        }
        if (mustExist) {
            log.error("idempotency key collision with another player: user={}, keys={}", userId, keys);
            throw new IllegalStateException("idempotency key already used by another player");
        }
        return null;
    }

    private WalletResult rejection(Rejected e, long userId, String currency) {
        Wallet wallet = walletMapper.find(userId, currency);
        BigDecimal balance = wallet == null ? null : wallet.getBalance();
        WalletResultCode code = e.code();
        if (code == null) {
            if (wallet == null) {
                code = WalletResultCode.WALLET_NOT_FOUND;
            } else if (wallet.getStatus() > e.maxStatus().code()) {
                code = WalletResultCode.WALLET_LOCKED;
            } else {
                code = WalletResultCode.INSUFFICIENT_FUNDS;
            }
        }
        return WalletResult.reject(code, currency, balance, e.getMessage());
    }

    /**
     * Runs a money command on the user's shard. Everything inside (transaction, replay lookups, rejection reads)
     * hits that one database; a shard under migration answers 503 so the caller retries with the same key.
     */
    private WalletResult measured(String operation, long userId, Supplier<WalletResult> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "ERROR";
        boolean replay = false;
        try {
            WalletResult result = shards.forUserWrite(userId, action);
            outcome = result.code().name();
            replay = result.replay();
            return result;
        } catch (BizException e) {
            // amount validation: a business answer, not a system failure
            outcome = WalletResultCode.INVALID_REQUEST.name();
            return WalletResult.reject(WalletResultCode.INVALID_REQUEST, null, null, e.getMessage());
        } finally {
            sample.stop(Timer.builder("bingo.wallet.command")
                    .tag("op", operation).tag("outcome", outcome).tag("replay", String.valueOf(replay))
                    .register(meterRegistry));
        }
    }

    private BigDecimal currentBalance(long userId, String currency) {
        Wallet wallet = walletMapper.find(userId, currency);
        return wallet == null ? null : wallet.getBalance();
    }

    private static WalletResult invalid(String currency, String message) {
        return WalletResult.reject(WalletResultCode.INVALID_REQUEST, currency, null, message);
    }

    private static BalanceView toView(Wallet wallet) {
        return new BalanceView(wallet.getUserId(), wallet.getCurrency(), wallet.getBalance(), wallet.getFrozen(),
                WalletStatus.of(wallet.getStatus()));
    }
}

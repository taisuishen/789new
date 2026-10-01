package com.bingo789.wallet.service;

import com.bingo789.common.core.Money;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.common.mybatis.shard.ShardRouter;
import com.bingo789.wallet.api.dto.AdjustCommand;
import com.bingo789.wallet.api.dto.BetAndPayoutCommand;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.RollbackCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import com.bingo789.wallet.api.enums.WalletResultCode;
import com.bingo789.wallet.api.enums.WalletStatus;
import com.bingo789.wallet.domain.Wallet;
import com.bingo789.wallet.domain.WalletTxn;
import com.bingo789.wallet.mapper.WalletLockMapper;
import com.bingo789.wallet.mapper.WalletMapper;
import com.bingo789.wallet.mapper.WalletStatusLogMapper;
import com.bingo789.wallet.mapper.WalletTxnMapper;
import com.bingo789.wallet.mapper.WalletUserLineMapper;
import com.bingo789.wallet.service.WalletSignals.AlreadyApplied;
import com.bingo789.wallet.service.WalletSignals.Rejected;
import com.bingo789.wallet.service.WalletSignals.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One local transaction per money movement: conditional balance UPDATE, then the ledger INSERT whose
 * unique key (provider_code, provider_txn_id, txn_type) is the final idempotency guard.
 * <p>
 * Ordering rule: every transaction touches the wallet row first. That row lock serializes all
 * operations of one player, which is what makes the out-of-order cases (rollback before bet,
 * payout racing a rollback) safe without distributed locks. Isolation is READ COMMITTED
 * (set on the Hikari pool), so reads after the lock see everything committed before it.
 * <p>
 * Never call these methods directly: go through {@link WalletService}, which turns the signals
 * thrown here into idempotent responses.
 */
@Component
@RequiredArgsConstructor
public class WalletTxnExecutor {

    private static final int TX_TIMEOUT_SECONDS = 3;
    private static final int REMARK_MAX = 255;

    private final WalletMapper walletMapper;
    private final WalletTxnMapper txnMapper;
    private final WalletStatusLogMapper statusLogMapper;
    private final WalletLockMapper lockMapper;
    private final WalletUserLineMapper userLineMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final WalletCommitHooks commitHooks;
    private final ShardRouter shardRouter;

    // ------------------------------------------------------------------ seamless-wallet game transactions

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public WalletResult bet(BetCommand c, BigDecimal amount) {
        if (walletMapper.debit(c.userId(), c.currency(), amount, WalletStatus.ACTIVE.code()) == 0) {
            throw Rejected.debitRefused(WalletStatus.ACTIVE);
        }
        Wallet wallet = requireWallet(c.userId(), c.currency());
        WalletTxn txn = ledgerRow(wallet, TxnType.BET, amount.negate(), amount, c.providerCode(), c.providerTxnId())
                .roundId(c.roundId()).gameCode(c.gameCode()).roundClosed(c.roundClosed())
                .build();
        return commit(wallet, txn);
    }

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public WalletResult payout(PayoutCommand c, BigDecimal amount) {
        // lock (or credit) first: the bet check below must not race with a concurrent rollback of that bet
        int rows = Money.isZero(amount)
                ? walletMapper.lockRow(c.userId(), c.currency())
                : walletMapper.credit(c.userId(), c.currency(), amount);
        if (rows == 0) {
            throw Rejected.of(WalletResultCode.WALLET_NOT_FOUND, null);
        }
        if (c.requireBet() && !c.payoutType().isStakeless()
                && !hasLiveBet(c.userId(), c.providerCode(), c.roundId(), c.betTxnId())) {
            throw Rejected.of(WalletResultCode.BET_NOT_FOUND, "no live bet in round " + c.roundId());
        }
        Wallet wallet = requireWallet(c.userId(), c.currency());
        WalletTxn txn = ledgerRow(wallet, c.payoutType(), amount, amount, c.providerCode(), c.providerTxnId())
                .roundId(c.roundId()).gameCode(c.gameCode()).refTxnId(c.betTxnId()).roundClosed(c.roundClosed())
                .build();
        return commit(wallet, txn);
    }

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public WalletResult betAndPayout(BetAndPayoutCommand c, BigDecimal betAmount, BigDecimal payoutAmount) {
        if (walletMapper.debitThenCredit(c.userId(), c.currency(), betAmount, payoutAmount, WalletStatus.ACTIVE.code()) == 0) {
            throw Rejected.debitRefused(WalletStatus.ACTIVE);
        }
        Wallet wallet = requireWallet(c.userId(), c.currency());
        BigDecimal afterBet = wallet.getBalance().subtract(payoutAmount);
        WalletTxn bet = ledgerRow(wallet, afterBet, TxnType.BET, betAmount.negate(), betAmount, c.providerCode(), c.betTxnId())
                .roundId(c.roundId()).gameCode(c.gameCode())
                .build();
        WalletTxn payout = ledgerRow(wallet, TxnType.PAYOUT, payoutAmount, payoutAmount, c.providerCode(), c.payoutTxnId())
                .roundId(c.roundId()).gameCode(c.gameCode()).refTxnId(c.betTxnId()).roundClosed(c.roundClosed())
                .build();
        return commit(wallet, bet, payout);
    }

    /**
     * Reverses one bet (ROLLBACK, credit) or one payout (PAYOUT_REVERSAL, debit). The reversal row reuses the
     * target's provider txn id as its idempotency key, so a target is reversed at most once no matter how
     * many distinct rollback requests arrive.
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public WalletResult reverse(RollbackCommand c, String targetTxnId, TxnType targetType, boolean allowNegative) {
        TxnType reversalType = targetType == TxnType.BET ? TxnType.ROLLBACK : TxnType.PAYOUT_REVERSAL;
        WalletTxn target = txnMapper.findByKey(c.userId(), c.providerCode(), targetTxnId, targetType.name());
        if (target == null) {
            if (targetType != TxnType.BET) {
                throw Rejected.of(WalletResultCode.TXN_NOT_FOUND, targetType + " " + targetTxnId + " not found");
            }
            return cancelBeforeArrival(c, targetTxnId);
        }
        if (target.isTombstone()) {
            throw new AlreadyApplied();
        }
        if (!target.getCurrency().equals(c.currency())) {
            throw Rejected.of(WalletResultCode.INVALID_REQUEST, "currency does not match the original transaction");
        }
        if (reversalType == TxnType.ROLLBACK && target.getRoundId() != null
                && txnMapper.countLivePayouts(c.userId(), c.providerCode(), target.getRoundId(), targetTxnId) > 0) {
            // the round already paid this bet: refunding the stake too would pay the player twice
            throw Rejected.of(WalletResultCode.BET_SETTLED, "bet " + targetTxnId + " is paid out; reverse the payout first");
        }
        BigDecimal amount = target.getAmount();
        BigDecimal delta;
        int rows;
        if (Money.isZero(amount)) {
            rows = walletMapper.lockRow(c.userId(), c.currency());
            delta = Money.ZERO;
        } else if (reversalType == TxnType.ROLLBACK) {
            rows = walletMapper.credit(c.userId(), c.currency(), amount);
            delta = amount;
        } else {
            rows = allowNegative
                    ? walletMapper.forceDebit(c.userId(), c.currency(), amount)
                    : walletMapper.debit(c.userId(), c.currency(), amount, WalletStatus.FROZEN.code());
            delta = amount.negate();
        }
        if (rows == 0) {
            throw reversalType == TxnType.ROLLBACK || Money.isZero(amount)
                    ? Rejected.of(WalletResultCode.WALLET_NOT_FOUND, null)
                    : Rejected.debitRefused(WalletStatus.FROZEN);
        }
        Wallet wallet = requireWallet(c.userId(), c.currency());
        WalletTxn txn = ledgerRow(wallet, reversalType, delta, amount, c.providerCode(), targetTxnId)
                .extTxnId(c.rollbackTxnId()).refTxnId(targetTxnId)
                .roundId(target.getRoundId()).gameCode(target.getGameCode())
                .build();
        return commit(wallet, txn);
    }

    /**
     * Rollback arrived before its bet (typical after a bet timeout). Write a tombstone under the bet's own
     * idempotency key, so the late bet collides with it and is rejected, plus a zero-amount ROLLBACK row
     * that makes repeated rollbacks idempotent.
     */
    private WalletResult cancelBeforeArrival(RollbackCommand c, String betTxnId) {
        if (walletMapper.lockRow(c.userId(), c.currency()) == 0) {
            throw Rejected.of(WalletResultCode.WALLET_NOT_FOUND, null);
        }
        Wallet wallet = requireWallet(c.userId(), c.currency());
        WalletTxn tombstone = ledgerRow(wallet, TxnType.BET, Money.ZERO, Money.ZERO, c.providerCode(), betTxnId)
                .status(WalletTxn.STATUS_TOMBSTONE).roundId(c.roundId()).gameCode(c.gameCode())
                .remark("rollback " + c.rollbackTxnId() + " arrived before the bet")
                .build();
        try {
            txnMapper.insert(tombstone);
        } catch (RuntimeException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                // the bet (or another rollback's tombstone) committed while we waited for the row lock
                throw new Retry("bet " + betTxnId + " was written concurrently");
            }
            throw e;
        }
        WalletTxn rollback = ledgerRow(wallet, TxnType.ROLLBACK, Money.ZERO, Money.ZERO, c.providerCode(), betTxnId)
                .extTxnId(c.rollbackTxnId()).refTxnId(betTxnId).roundId(c.roundId()).gameCode(c.gameCode())
                .build();
        txnMapper.insert(rollback);
        commitHooks.register(wallet, List.of(tombstone, rollback));
        return WalletResult.success(rollback.getId(), wallet.getCurrency(), wallet.getBalance(), rollback.getBalanceAfter());
    }

    /** Provider re-settlement: a new signed row referencing the original, never an edit of it. */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public WalletResult adjust(AdjustCommand c, BigDecimal signedAmount, boolean allowNegative) {
        BigDecimal amount = signedAmount.abs();
        int rows;
        if (signedAmount.signum() > 0) {
            rows = walletMapper.credit(c.userId(), c.currency(), amount);
        } else {
            rows = allowNegative
                    ? walletMapper.forceDebit(c.userId(), c.currency(), amount)
                    : walletMapper.debit(c.userId(), c.currency(), amount, WalletStatus.FROZEN.code());
        }
        if (rows == 0) {
            throw signedAmount.signum() > 0
                    ? Rejected.of(WalletResultCode.WALLET_NOT_FOUND, null)
                    : Rejected.debitRefused(WalletStatus.FROZEN);
        }
        Wallet wallet = requireWallet(c.userId(), c.currency());
        WalletTxn txn = ledgerRow(wallet, TxnType.ADJUST, signedAmount, amount, c.providerCode(), c.providerTxnId())
                .refTxnId(c.refTxnId()).roundId(c.roundId()).remark(truncate(c.reason()))
                .build();
        return commit(wallet, txn);
    }

    // ------------------------------------------------------------------ platform transactions

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public WalletResult platform(PlatformTxnCommand c, BigDecimal amount) {
        long userId = c.userId();
        String currency = c.currency();
        BigDecimal delta;
        switch (c.txnType()) {
            case DEPOSIT, BONUS, REBATE, TRANSFER_IN -> {
                if (walletMapper.credit(userId, currency, amount) == 0) {
                    // first credit in this currency opens the wallet
                    if (walletMapper.insertIgnore(idGenerator.nextId(), userId, currency, shardRouter.logicalShard(userId)) == 1) {
                        applyLocks(userId, currency);
                    }
                    if (walletMapper.credit(userId, currency, amount) == 0) {
                        throw Rejected.of(WalletResultCode.WALLET_NOT_FOUND, null);
                    }
                }
                delta = amount;
            }
            case TRANSFER_OUT -> {
                if (walletMapper.debit(userId, currency, amount, WalletStatus.ACTIVE.code()) == 0) {
                    throw Rejected.debitRefused(WalletStatus.ACTIVE);
                }
                delta = amount.negate();
            }
            case WITHDRAW_FREEZE -> {
                // self-excluded (BET_LOCKED) players must still be able to withdraw; FROZEN (AML hold) cannot
                if (walletMapper.freeze(userId, currency, amount, WalletStatus.BET_LOCKED.code()) == 0) {
                    throw Rejected.debitRefused(WalletStatus.BET_LOCKED);
                }
                delta = amount.negate();
            }
            case WITHDRAW_CONFIRM -> {
                if (walletMapper.releaseFrozen(userId, currency, amount) == 0) {
                    throw Rejected.of(WalletResultCode.INVALID_REQUEST, "frozen amount is lower than " + amount);
                }
                rejectIfSettled(c, TxnType.WITHDRAW_UNFREEZE);
                delta = Money.ZERO;
            }
            case WITHDRAW_UNFREEZE -> {
                if (walletMapper.unfreeze(userId, currency, amount) == 0) {
                    throw Rejected.of(WalletResultCode.INVALID_REQUEST, "frozen amount is lower than " + amount);
                }
                rejectIfSettled(c, TxnType.WITHDRAW_CONFIRM);
                delta = amount;
            }
            default -> throw Rejected.of(WalletResultCode.INVALID_REQUEST, c.txnType() + " is not a platform transaction");
        }
        Wallet wallet = requireWallet(userId, currency);
        WalletTxn txn = ledgerRow(wallet, c.txnType(), delta, amount, c.source(), c.bizNo())
                .remark(truncate(c.remark()))
                .build();
        return commit(wallet, txn);
    }

    /**
     * Places (BET_LOCKED / FROZEN) or releases (ACTIVE) the lock identified by {@code reason}, then sets each affected
     * wallet to its strictest remaining lock.
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public void updateStatus(long userId, String currency, WalletStatus status, String reason) {
        walletMapper.lockAllRows(userId);
        String scope = currency == null ? WalletLockMapper.ALL_CURRENCIES : currency;
        if (status == WalletStatus.ACTIVE) {
            lockMapper.delete(userId, scope, reason);
        } else {
            lockMapper.upsert(userId, scope, reason, status.code());
        }
        for (Wallet wallet : walletMapper.findByUser(userId)) {
            if (currency == null || currency.equals(wallet.getCurrency())) {
                applyLocks(userId, wallet.getCurrency());
            }
        }
        statusLogMapper.insert(userId, currency, status.code(), truncate(reason));
    }

    /** Opens the wallet if missing; a new wallet inherits the user's all-currency locks. */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public void open(long userId, String currency) {
        if (walletMapper.insertIgnore(idGenerator.nextId(), userId, currency, shardRouter.logicalShard(userId)) == 1) {
            applyLocks(userId, currency);
        }
    }

    /**
     * Applies the player's line from user-service (ignored when {@code version} is not newer), then copies it onto
     * every currency row; ledger rows written from now on carry it. Rows already written keep their line.
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public void updateUserLine(long userId, int userLine, long version) {
        userLineMapper.upsert(userId, userLine, version);
        walletMapper.copyUserLine(userId);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A withdrawal ends exactly once: paid out (CONFIRM) or returned (UNFREEZE), never both. Checked after the
     * balance UPDATE, so the wallet row lock makes the check race-free; a violation rolls the UPDATE back.
     */
    private void rejectIfSettled(PlatformTxnCommand c, TxnType otherOutcome) {
        if (txnMapper.findByKey(c.userId(), c.source(), c.bizNo(), otherOutcome.name()) != null) {
            throw Rejected.of(WalletResultCode.INVALID_REQUEST, "withdrawal " + c.bizNo() + " already settled as " + otherOutcome);
        }
    }

    private void applyLocks(long userId, String currency) {
        Integer strictest = lockMapper.strictest(userId, currency);
        walletMapper.updateStatus(userId, currency, strictest == null ? WalletStatus.ACTIVE.code() : strictest);
    }

    private boolean hasLiveBet(long userId, String providerCode, String roundId, String betTxnId) {
        if (betTxnId != null && !betTxnId.isBlank()) {
            WalletTxn bet = txnMapper.findByKey(userId, providerCode, betTxnId, TxnType.BET.name());
            return bet != null && !bet.isTombstone() && !isRolledBack(userId, providerCode, betTxnId);
        }
        for (WalletTxn bet : txnMapper.findBetsInRound(userId, providerCode, roundId)) {
            if (!isRolledBack(userId, providerCode, bet.getProviderTxnId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isRolledBack(long userId, String providerCode, String betTxnId) {
        return txnMapper.findByKey(userId, providerCode, betTxnId, TxnType.ROLLBACK.name()) != null;
    }

    private Wallet requireWallet(long userId, String currency) {
        Wallet wallet = walletMapper.find(userId, currency);
        if (wallet == null) {
            throw new IllegalStateException("wallet row vanished inside transaction: " + userId + "/" + currency);
        }
        return wallet;
    }

    private WalletResult commit(Wallet wallet, WalletTxn... txns) {
        for (WalletTxn txn : txns) {
            txnMapper.insert(txn);
        }
        commitHooks.register(wallet, List.of(txns));
        WalletTxn last = txns[txns.length - 1];
        return WalletResult.success(last.getId(), wallet.getCurrency(), wallet.getBalance(), last.getBalanceAfter());
    }

    private WalletTxn.WalletTxnBuilder ledgerRow(Wallet wallet, TxnType type, BigDecimal balanceDelta, BigDecimal amount,
                                                 String providerCode, String providerTxnId) {
        return ledgerRow(wallet, wallet.getBalance(), type, balanceDelta, amount, providerCode, providerTxnId);
    }

    /** The id is generated after the row lock is held, so ids of one player follow commit order. */
    private WalletTxn.WalletTxnBuilder ledgerRow(Wallet wallet, BigDecimal balanceAfter, TxnType type, BigDecimal balanceDelta,
                                                 BigDecimal amount, String providerCode, String providerTxnId) {
        return WalletTxn.builder()
                .id(idGenerator.nextId())
                .userId(wallet.getUserId())
                .userLine(wallet.getUserLine())
                .currency(wallet.getCurrency())
                .txnType(type.name())
                .direction(balanceDelta.signum())
                .amount(amount)
                .balanceBefore(balanceAfter.subtract(balanceDelta))
                .balanceAfter(balanceAfter)
                .providerCode(providerCode)
                .providerTxnId(providerTxnId)
                .roundClosed(false)
                .status(WalletTxn.STATUS_NORMAL)
                .createdAt(LocalDateTime.now(BingoTime.ZONE));
    }

    private static String truncate(String value) {
        return value == null || value.length() <= REMARK_MAX ? value : value.substring(0, REMARK_MAX);
    }
}

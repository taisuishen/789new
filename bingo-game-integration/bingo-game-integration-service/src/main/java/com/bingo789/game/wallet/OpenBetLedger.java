package com.bingo789.game.wallet;

import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.OpenBet;
import com.bingo789.game.adapter.model.WalletCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Open token-bound stakes of providers that query them (ProviderAdapter#tracksOpenBets, YGR betSlip/roundCheck): the
 * provider resends the payout or refund of every stake it gets back, with the token the stake was placed with.
 * <p>
 * A stake is recorded PENDING before its wallet call, so a stake whose wallet call did not answer (timeout, crash) is
 * always listed: that is the stake the provider has to settle. The wallet outcome then makes it OPEN (debited) or
 * CLOSED (refused); a successful payout of its round or refund of it closes it. A failed status update after the wallet
 * call is only logged: the row stays listed, and settling an already settled stake is idempotent in the wallet.
 * <p>
 * Ids are the wallet's ({@link WalletGateway#fit}), so the provider gets back the ids it sent (up to 128 characters).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenBetLedger {

    /** Most stakes one query returns, oldest first; the provider narrows its window to see the rest. */
    static final int MAX_OPEN_BETS = 5000;

    private final OpenBetMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    /** The player and currency of the latest stake placed with this token, or null. */
    public record Holder(long userId, String currency) {
    }

    /** True for the commands whose stake is tracked. */
    static boolean isStake(WalletCommand command) {
        return command instanceof WalletCommand.Bet || command instanceof WalletCommand.TakeAll;
    }

    /** Before the wallet call of a stake; a retried stake keeps its row. */
    public void pending(String providerCode, String token, WalletCommand stake, long userId, String currency) {
        OpenBetRow row = new OpenBetRow();
        row.setId(idGenerator.nextId());
        row.setProviderCode(providerCode);
        row.setUserId(userId);
        row.setCurrency(currency);
        row.setSessionToken(token);
        row.setPlacedAt(LocalDateTime.now(BingoTime.ZONE));
        switch (stake) {
            case WalletCommand.Bet b -> {
                row.setTxnId(WalletGateway.fit(b.txnId()));
                row.setRoundId(WalletGateway.fit(b.roundId()));
                row.setGameCode(b.gameCode());
                row.setAmount(b.amount());
            }
            case WalletCommand.TakeAll t -> {
                row.setTxnId(WalletGateway.fit(t.txnId()));
                row.setRoundId(WalletGateway.fit(t.roundId()));
                row.setGameCode(t.gameCode());
            }
            default -> throw new IllegalArgumentException("not a stake: " + stake);
        }
        mapper.insertPending(row);
    }

    /** After the wallet answered a command of a tracking provider. */
    public void record(String providerCode, WalletCommand command, CommandOutcome outcome) {
        try {
            switch (command) {
                case WalletCommand.Bet b -> settleStake(providerCode, b.txnId(), outcome, outcome.amount() != null ? outcome.amount() : b.amount());
                case WalletCommand.TakeAll t -> settleStake(providerCode, t.txnId(), outcome, outcome.amount());
                case WalletCommand.Payout p when outcome.isSuccess() -> mapper.closeRound(providerCode, WalletGateway.fit(p.roundId()));
                case WalletCommand.Rollback r when outcome.isSuccess() -> {
                    if (r.targetTxnId() != null) {
                        mapper.closeTxn(providerCode, WalletGateway.fit(r.targetTxnId()));
                    } else if (r.roundId() != null) {
                        mapper.closeRound(providerCode, WalletGateway.fit(r.roundId()));
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.error("open bet of {} not updated after {} answered {}; it stays listed for the provider",
                    providerCode, command.getClass().getSimpleName(), outcome.code(), e);
        }
    }

    private void settleStake(String providerCode, String txnId, CommandOutcome outcome, BigDecimal amount) {
        if (outcome.isSuccess()) {
            mapper.markOpen(providerCode, WalletGateway.fit(txnId), amount);
        } else {
            // a refusal is final: the wallet answers a stake it applied before with that first success, never a refusal
            mapper.closePending(providerCode, WalletGateway.fit(txnId));
        }
    }

    /** PENDING and OPEN stakes placed in [from, to). */
    public List<OpenBet> open(String providerCode, Instant from, Instant to) {
        List<OpenBetRow> rows = mapper.findOpen(providerCode, LocalDateTime.ofInstant(from, BingoTime.ZONE),
                LocalDateTime.ofInstant(to, BingoTime.ZONE), MAX_OPEN_BETS);
        if (rows.size() == MAX_OPEN_BETS) {
            log.warn("{} open bets of {} in [{}, {}): only the oldest {} are answered", rows.size(), providerCode, from, to, MAX_OPEN_BETS);
        }
        return rows.stream()
                .map(r -> new OpenBet(r.getTxnId(), r.getRoundId(), r.getAmount(), r.getSessionToken(), r.getGameCode(),
                        r.getPlacedAt().toInstant(BingoTime.ZONE), OpenBetRow.PENDING.equals(r.getStatus())))
                .toList();
    }

    /** The holder of a token that placed a tracked stake, for its payout or refund after the token expired. */
    public Holder holder(String providerCode, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        OpenBetRow row = mapper.findLatestByToken(providerCode, token);
        return row == null ? null : new Holder(row.getUserId(), row.getCurrency());
    }
}

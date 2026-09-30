package com.bingo789.betrecord.round;

import com.bingo789.betrecord.catalog.GameInfo;
import com.bingo789.betrecord.config.BetRecordProperties;
import com.bingo789.betrecord.entity.GameRound;
import com.bingo789.betrecord.entity.RoundStatus;
import com.bingo789.betrecord.entity.RoundTxn;
import com.bingo789.betrecord.mapper.GameRoundMapper;
import com.bingo789.betrecord.mapper.RoundTxnMapper;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.WalletTxnEvent;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.game.api.dto.RoundResolutionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Round state machine driven by the wallet ledger.
 * <p>
 * A round is identified by (provider, round id, user): live-dealer providers share one round id between all
 * players at a table. Callers run every method under {@code MasterRoute}: consecutive events of a round are
 * applied milliseconds apart and must see each other's writes, which a read replica does not guarantee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundService {

    private final GameRoundMapper roundMapper;
    private final RoundTxnMapper roundTxnMapper;
    private final RoundEventPublisher publisher;
    private final BetRecordProperties properties;

    /**
     * Applies one ledger row exactly once: the round_txn insert and the round update share this local
     * transaction, so a redelivered event hits the round_txn primary key and changes nothing.
     *
     * @param game catalogue attributes of the event's game, looked up by the caller before the transaction;
     *             only used when the event opens the round
     */
    @Transactional
    public void apply(WalletTxnEvent event, RoundEffect effect, GameInfo game) {
        if (!markApplied(event)) {
            return;
        }
        LocalDateTime eventAt = LocalDateTime.ofInstant(event.createdAt(), BingoTime.ZONE);
        RoundEffect.Delta delta = effect.delta(event);
        GameRound round = findRound(event, effect, eventAt.toLocalDate());
        if (round == null) {
            if (effect.requiresExistingRound()) {
                log.warn("{} txn {} for unknown round {}/{} of user {} ignored: nothing to reverse or adjust",
                        effect, event.id(), event.providerCode(), event.roundId(), event.userId());
                return;
            }
            round = openRound(event, eventAt, delta, game);
        } else {
            roundMapper.applyDelta(round.getId(), round.getRoundDate(), delta.bet(), delta.payout(),
                    delta.betCount(), delta.payoutCount(), eventAt);
            applyInMemory(round, delta, eventAt);
            if (round.getStatus().isTerminal()) {
                // TODO: publish a correction event (needs a revision field on RoundSettledEvent)
                log.warn("late {} txn {} applied to {} round {}/{} of user {}; RoundSettledEvent not re-sent",
                        effect, event.id(), round.getStatus(), round.getProviderCode(), round.getRoundId(), round.getUserId());
                return;
            }
        }
        RoundStatus terminal = terminalStatus(round, effect, event);
        if (terminal != null) {
            close(round, terminal, eventAt, event.balanceAfter());
        }
    }

    /**
     * Closes a round locally after the provider resolver reported a terminal outcome on an earlier run and the
     * ledger still has not closed it (i.e. the resolver needed no wallet transaction, or none carried roundClosed).
     *
     * @return true when the round was closed by this call
     */
    @Transactional
    public boolean closeAfterResolution(long id, LocalDate roundDate, String outcome) {
        GameRound round = roundMapper.lockById(id, roundDate);
        if (round == null || round.getStatus() != RoundStatus.OPEN) {
            return false;
        }
        RoundStatus status;
        if (RoundResolutionView.CANCELLED.equals(outcome)) {
            if (round.getBetCount() > 0) {
                log.error("provider reports round {}/{} of user {} cancelled but {} bets are still live in the ledger: manual check required",
                        round.getProviderCode(), round.getRoundId(), round.getUserId(), round.getBetCount());
                return false;
            }
            status = RoundStatus.CANCELLED;
        } else {
            status = RoundStatus.SETTLED;
        }
        // no wallet transaction closed the round, so there is no balance to report
        close(round, status, LocalDateTime.now(BingoTime.ZONE), null);
        return true;
    }

    private boolean markApplied(WalletTxnEvent event) {
        RoundTxn txn = new RoundTxn();
        txn.setTxnId(event.id());
        txn.setRoundId(event.roundId());
        try {
            roundTxnMapper.insert(txn);
            return true;
        } catch (RuntimeException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                // redelivery; MySQL rolls back only the failed statement, the transaction stays usable
                log.debug("wallet txn {} already applied", event.id());
                return false;
            }
            throw e;
        }
    }

    private GameRound findRound(WalletTxnEvent event, RoundEffect effect, LocalDate eventDate) {
        // +1 day tolerates clock skew between wallet nodes around midnight
        LocalDate to = eventDate.plusDays(1);
        GameRound round = roundMapper.findForUpdate(event.providerCode(), event.roundId(), event.userId(),
                eventDate.minusDays(properties.roundLookbackDays()), to);
        if (round == null && effect.mayTargetOldRound() && properties.lateEventLookbackDays() > properties.roundLookbackDays()) {
            round = roundMapper.findForUpdate(event.providerCode(), event.roundId(), event.userId(),
                    eventDate.minusDays(properties.lateEventLookbackDays()), to);
        }
        return round;
    }

    private GameRound openRound(WalletTxnEvent event, LocalDateTime eventAt, RoundEffect.Delta delta, GameInfo game) {
        GameRound round = new GameRound();
        round.setProviderCode(event.providerCode());
        round.setRoundId(event.roundId());
        round.setUserId(event.userId());
        // line, game type and name are snapshots taken when the round starts; later events (applyDelta) never change them
        round.setUserLine(event.userLine());
        round.setCurrency(event.currency());
        round.setGameCode(event.gameCode() == null ? "" : event.gameCode());
        round.setGameType(game.gameType());
        round.setGameName(game.gameName());
        round.setBetAmount(delta.bet());
        round.setPayoutAmount(delta.payout());
        round.setBetCount(delta.betCount());
        round.setPayoutCount(delta.payoutCount());
        round.setStatus(RoundStatus.OPEN);
        round.setRoundDate(eventAt.toLocalDate());
        round.setFirstEventAt(eventAt);
        round.setLastEventAt(eventAt);
        round.setResolveAttempts(0);
        round.setEventPublished(false);
        // A duplicate key here means another consumer wrote the round concurrently (partition handover):
        // the exception rolls back this transaction and the record is retried, finding the round.
        roundMapper.insert(round);
        return round;
    }

    private static void applyInMemory(GameRound round, RoundEffect.Delta delta, LocalDateTime eventAt) {
        round.setBetAmount(round.getBetAmount().add(delta.bet()));
        round.setPayoutAmount(round.getPayoutAmount().add(delta.payout()));
        round.setBetCount(round.getBetCount() + delta.betCount());
        round.setPayoutCount(round.getPayoutCount() + delta.payoutCount());
        if (eventAt.isAfter(round.getLastEventAt())) {
            round.setLastEventAt(eventAt);
        }
    }

    private static RoundStatus terminalStatus(GameRound round, RoundEffect effect, WalletTxnEvent event) {
        if (effect == RoundEffect.ROLLBACK && round.getBetCount() <= 0 && round.getPayoutCount() <= 0) {
            return RoundStatus.CANCELLED;
        }
        return event.roundClosed() ? RoundStatus.SETTLED : null;
    }

    /** @param balanceAfter balance after the wallet txn that closed the round; null when none did */
    private void close(GameRound round, RoundStatus status, LocalDateTime at, BigDecimal balanceAfter) {
        if (roundMapper.close(round.getId(), round.getRoundDate(), status.name(), at, balanceAfter) == 1) {
            round.setStatus(status);
            round.setSettledAt(at);
            round.setBalanceAfter(balanceAfter);
            publisher.publishAfterCommit(round);
        }
    }
}

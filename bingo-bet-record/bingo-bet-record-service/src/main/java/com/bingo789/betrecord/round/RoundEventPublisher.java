package com.bingo789.betrecord.round;

import com.bingo789.betrecord.entity.GameRound;
import com.bingo789.betrecord.entity.RoundStatus;
import com.bingo789.betrecord.mapper.GameRoundMapper;
import com.bingo789.common.core.Money;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes {@link RoundSettledEvent} to {@link Topics#ROUND_SETTLED} after the terminal transition commits.
 * <p>
 * Delivery is at-least-once: game_round.event_published is set when Kafka acknowledges the send, and
 * RoundEventRepublishJob re-sends terminal rounds that were never acknowledged (crash right after commit,
 * broker outage). A crash between the acknowledgement and the flag update therefore produces a duplicate.
 * <p>
 * Every event is the round's full current state plus its {@code revision} (1 = first close, +1 per later change).
 * Consumers key on (providerCode, roundId, userId), ignore a revision they have already applied (or an older one)
 * and replace the effect of an earlier revision with the new one.
 */
@Slf4j
@Component
public class RoundEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final GameRoundMapper roundMapper;
    private final ShardTemplate shards;
    /** Send callbacks run on the producer I/O thread; the flag update must not block it. */
    private final ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public RoundEventPublisher(KafkaTemplate<String, String> kafkaTemplate, GameRoundMapper roundMapper, ShardTemplate shards) {
        this.kafkaTemplate = kafkaTemplate;
        this.roundMapper = roundMapper;
        this.shards = shards;
    }

    /** Must be called inside the transaction that closed the round; sends only if it commits. */
    public void publishAfterCommit(GameRound round) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(round);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(round);
            }
        });
    }

    /** Never throws: a failed send is logged and left to the republish job. */
    public CompletableFuture<?> publish(GameRound round) {
        long id = round.getId();
        LocalDate roundDate = round.getRoundDate();
        RoundSettledEvent event = toEvent(round);
        int revision = event.revision();
        CompletableFuture<SendResult<String, String>> send;
        try {
            send = kafkaTemplate.send(Topics.ROUND_SETTLED, String.valueOf(event.userId()), JsonUtils.toJson(event));
        } catch (RuntimeException e) {
            log.warn("round event send failed for {}/{} (round pk {}), republish job will retry",
                    event.providerCode(), event.roundId(), id, e);
            return CompletableFuture.failedFuture(e);
        }
        return send.whenCompleteAsync((result, error) -> {
            if (error != null) {
                log.warn("round event not acknowledged for {}/{} (round pk {}), republish job will retry",
                        event.providerCode(), event.roundId(), id, error);
                return;
            }
            try {
                shards.forUserWrite(event.userId(), () -> roundMapper.markPublished(id, roundDate, revision));
            } catch (RuntimeException e) {
                log.warn("could not flag round {} as published; it will be re-sent (duplicate)", id, e);
            }
        }, callbackExecutor);
    }

    static RoundSettledEvent toEvent(GameRound round) {
        boolean settled = round.getStatus() == RoundStatus.SETTLED;
        BigDecimal betAmount = round.getBetAmount();
        // TODO: hedge/arbitrage exclusion for live tables (e.g. opposite bets on banker and player in one round)
        BigDecimal validBet = settled && betAmount.signum() > 0 ? betAmount : Money.ZERO;
        return new RoundSettledEvent(
                round.getProviderCode(),
                round.getRoundId(),
                round.getUserId(),
                round.getUserLine(),
                round.getCurrency(),
                round.getGameCode(),
                round.getGameType(),
                round.getGameName(),
                betAmount,
                round.getPayoutAmount(),
                validBet,
                round.getBalanceAfter(),
                round.getStatus().name(),
                toInstant(round.getFirstEventAt()),
                toInstant(round.getSettledAt()),
                round.getRevision());
    }

    private static Instant toInstant(LocalDateTime local) {
        return local == null ? null : local.toInstant(BingoTime.ZONE);
    }

    @PreDestroy
    void shutdown() {
        callbackExecutor.close();
    }
}

package com.bingo789.betrecord.job;

import com.bingo789.betrecord.config.BetRecordProperties;
import com.bingo789.betrecord.entity.GameRound;
import com.bingo789.betrecord.mapper.GameRoundMapper;
import com.bingo789.betrecord.round.RoundService;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.game.api.dto.ResolveRoundCommand;
import com.bingo789.game.api.dto.RoundResolutionView;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rounds that stay OPEN without ledger events (missed payout callback, provider never sent roundClosed) are
 * handed to game-integration, which asks the provider and applies missing payouts or refunds through the wallet.
 * Those wallet transactions come back through the ledger stream and close the round the normal way.
 * <p>
 * When the provider reported a terminal outcome on a previous run and the round is still OPEN (nothing was
 * missing, so no closing transaction was written), the round is closed locally. Waiting one run avoids closing
 * a round before the resolver's own payout has arrived through Kafka.
 */
@Slf4j
@Component
public class UnsettledRoundJob {

    private final GameRoundMapper roundMapper;
    private final RoundService roundService;
    private final ProviderQueryClient providerQueryClient;
    private final BetRecordProperties properties;
    private final ShardTemplate shards;
    private final AtomicLong overdueRounds = new AtomicLong();

    public UnsettledRoundJob(GameRoundMapper roundMapper, RoundService roundService, ProviderQueryClient providerQueryClient,
                             BetRecordProperties properties, ShardTemplate shards, MeterRegistry meterRegistry) {
        this.roundMapper = roundMapper;
        this.roundService = roundService;
        this.providerQueryClient = providerQueryClient;
        this.properties = properties;
        this.shards = shards;
        Gauge.builder("bingo.rounds.unsettled.overdue", overdueRounds, AtomicLong::doubleValue)
                .description("OPEN rounds without ledger events beyond the unsettled threshold, refreshed by betRecordUnsettledRoundJob")
                .register(meterRegistry);
    }

    @XxlJob("betRecordUnsettledRoundJob")
    public void execute() {
        LocalDateTime now = LocalDateTime.now(BingoTime.ZONE);
        LocalDateTime scanBefore = now.minus(properties.minUnsettledThreshold());
        ScanStats total = new ScanStats();
        shards.forEachDataSource(dataSource -> scan(now, scanBefore, total));
        overdueRounds.set(total.overdue);
        XxlJobHelper.log("overdue={}, closedLocally={}, failed={}", total.overdue, total.closed, total.failed);
    }

    /** Runs inside one shard's scope; the rounds found there are resolved on the same shard. */
    private void scan(LocalDateTime now, LocalDateTime scanBefore, ScanStats stats) {
        long afterId = 0;
        while (true) {
            List<GameRound> batch = roundMapper.findOverdue(scanBefore, afterId, properties.batchSize());
            for (GameRound round : batch) {
                afterId = round.getId();
                if (!round.getLastEventAt().isBefore(now.minus(properties.unsettledThresholdFor(round.getProviderCode())))) {
                    continue;
                }
                stats.overdue++;
                try {
                    if (resolve(round)) {
                        stats.closed++;
                    }
                } catch (Exception e) {
                    stats.failed++;
                    log.warn("resolving round {}/{} of user {} failed", round.getProviderCode(), round.getRoundId(), round.getUserId(), e);
                }
            }
            if (batch.size() < properties.batchSize()) {
                break;
            }
        }
    }

    /** @return true when the round was closed locally */
    private boolean resolve(GameRound round) {
        if (isTerminal(round.getResolveOutcome())) {
            boolean closedNow = MasterRoute.run(() ->
                    roundService.closeAfterResolution(round.getId(), round.getRoundDate(), round.getResolveOutcome()));
            if (!closedNow) {
                // ledger and provider disagree (or the round closed meanwhile): ask the provider again next run
                roundMapper.recordResolveAttempt(round.getId(), round.getRoundDate(), RoundResolutionView.UNKNOWN);
            }
            return closedNow;
        }
        RoundResolutionView resolution = providerQueryClient.resolveRound(new ResolveRoundCommand(
                round.getProviderCode(), round.getRoundId(), round.getUserId(), round.getCurrency()));
        String outcome = resolution == null || resolution.outcome() == null ? RoundResolutionView.UNKNOWN : resolution.outcome();
        roundMapper.recordResolveAttempt(round.getId(), round.getRoundDate(), outcome);
        int attempts = round.getResolveAttempts() + 1;
        if (attempts >= properties.maxResolveAttempts()) {
            // alerting hook: log-based alert rule on this message
            log.error("ALERT unresolved round {}/{} of user {} after {} attempts, last outcome {} ({}), open since {}",
                    round.getProviderCode(), round.getRoundId(), round.getUserId(), attempts, outcome,
                    resolution == null ? null : resolution.message(), round.getFirstEventAt());
        }
        // TODO: per-round back-off (next_resolve_at) so long-running STILL_OPEN rounds are not queried every run
        return false;
    }

    private static final class ScanStats {
        long overdue;
        int closed;
        int failed;
    }

    private static boolean isTerminal(String outcome) {
        return RoundResolutionView.SETTLED.equals(outcome) || RoundResolutionView.CANCELLED.equals(outcome);
    }
}

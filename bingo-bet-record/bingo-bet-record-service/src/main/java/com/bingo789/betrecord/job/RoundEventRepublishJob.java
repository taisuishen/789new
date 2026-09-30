package com.bingo789.betrecord.job;

import com.bingo789.betrecord.config.BetRecordProperties;
import com.bingo789.betrecord.entity.GameRound;
import com.bingo789.betrecord.mapper.GameRoundMapper;
import com.bingo789.betrecord.round.RoundEventPublisher;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Re-sends RoundSettledEvents that Kafka never acknowledged; completes the at-least-once guarantee. Scans every shard. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoundEventRepublishJob {

    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final GameRoundMapper roundMapper;
    private final RoundEventPublisher publisher;
    private final BetRecordProperties properties;
    private final ShardTemplate shards;

    @XxlJob("betRecordRoundEventRepublishJob")
    public void execute() {
        LocalDateTime before = LocalDateTime.now(BingoTime.ZONE).minus(properties.republishDelay());
        int[] resent = {0};
        shards.forEachDataSource(dataSource -> resent[0] += republish(before));
        if (resent[0] > 0) {
            log.warn("re-sent {} round events that were never acknowledged", resent[0]);
        }
        XxlJobHelper.log("resent={}", resent[0]);
    }

    private int republish(LocalDateTime before) {
        long afterId = 0;
        int resent = 0;
        while (true) {
            List<GameRound> batch = roundMapper.findUnpublished(before, afterId, properties.batchSize());
            List<CompletableFuture<?>> sends = new ArrayList<>(batch.size());
            for (GameRound round : batch) {
                afterId = round.getId();
                sends.add(publisher.publish(round));
            }
            // failures are logged by the publisher and picked up again by the next run
            try {
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                        .handle((ignored, error) -> null)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while re-sending round events", e);
            } catch (Exception e) {
                throw new IllegalStateException("round event re-send did not complete", e);
            }
            resent += batch.size();
            if (batch.size() < properties.batchSize()) {
                break;
            }
        }
        return resent;
    }
}

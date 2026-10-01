package com.bingo789.betrecord.pull;

import com.bingo789.betrecord.catalog.GameCatalog;
import com.bingo789.betrecord.catalog.GameInfo;
import com.bingo789.betrecord.config.BetRecordProperties;
import com.bingo789.betrecord.entity.BetPullCheckpoint;
import com.bingo789.betrecord.mapper.BetPullCheckpointMapper;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.ProviderBetEvent;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.common.mybatis.shard.ShardMigratingException;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.game.api.dto.BetPullPage;
import com.bingo789.game.api.dto.BetPullQuery;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pulls one provider's bet history into provider_bet_record and forwards each record as ProviderBetEvent
 * (the provider side of reconciliation). Job param: provider code; schedule one job per provider with a
 * single-instance routing strategy and "discard later" blocking so windows of one provider never overlap in time.
 * <p>
 * Window: from = checkpoint - overlap, to = min(now - settleDelay, from + maxWindow). The checkpoint only advances
 * after every page of the window was stored and acknowledged by Kafka, so a failed run is simply repeated.
 * Without a checkpoint the first window ends at now - settleDelay; to backfill, insert a bet_pull_checkpoint row
 * with the desired start (catch-up then proceeds max-window per run).
 * Rate limits: pages are fetched sequentially with a pause; game-integration additionally runs each provider
 * query inside that provider's bulkhead. Window and pause can be set per provider
 * (bingo.bet-record.pull.providers.CODE.*). Providers whose API accepts shorter windows than max-window, or pages by
 * number / cursor, split the window themselves: their adapter returns hasMore with a cursor for the next part.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderBetPullJob {

    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final ProviderQueryClient providerQueryClient;
    private final ProviderBetRecordService recordService;
    private final PlayerLineResolver lineResolver;
    private final GameCatalog gameCatalog;
    private final BetPullCheckpointMapper checkpointMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BetRecordProperties properties;
    private final ShardTemplate shards;

    @XxlJob("betRecordProviderPullJob")
    public void execute() {
        String providerCode = XxlJobHelper.getJobParam() == null ? "" : XxlJobHelper.getJobParam().trim();
        if (providerCode.isEmpty()) {
            XxlJobHelper.handleFail("job param must be a provider code");
            return;
        }
        try {
            pull(providerCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            XxlJobHelper.handleFail("interrupted");
        } catch (Exception e) {
            log.warn("bet pull failed for provider {}; the window is retried on the next run", providerCode, e);
            XxlJobHelper.handleFail("bet pull failed: " + e);
        }
    }

    private void pull(String providerCode) throws Exception {
        BetRecordProperties.Pull cfg = properties.pull().forProvider(providerCode);
        Instant now = Instant.now();
        // checkpoints are not per user: they live on the global shard
        BetPullCheckpoint checkpoint = shards.onGlobal(() -> MasterRoute.run(() -> checkpointMapper.selectById(providerCode)));
        Instant upperBound = now.minus(cfg.settleDelay());
        Instant from = checkpoint == null
                ? upperBound.minus(cfg.maxWindow())
                : checkpoint.getWindowEnd().toInstant(BingoTime.ZONE).minus(cfg.overlap());
        Instant to = upperBound.isBefore(from.plus(cfg.maxWindow())) ? upperBound : from.plus(cfg.maxWindow());
        if (!to.isAfter(from)) {
            XxlJobHelper.log("nothing to pull for {} (window {} - {})", providerCode, from, to);
            return;
        }

        String cursor = null;
        int pages = 0;
        long received = 0;
        long published = 0;
        while (true) {
            BetPullPage page = providerQueryClient.pullBetRecords(new BetPullQuery(providerCode, from, to, cursor, cfg.pageSize()));
            List<ProviderBetRecordView> records = page == null || page.records() == null ? List.of() : page.records();
            received += records.size();
            published += storeAndPublish(providerCode, records);
            pages++;
            if (page == null || !page.hasMore()) {
                break;
            }
            if (page.nextCursor() == null || Objects.equals(page.nextCursor(), cursor)) {
                throw new IllegalStateException("provider returned hasMore without advancing the cursor");
            }
            if (pages >= cfg.maxPages()) {
                throw new IllegalStateException("page limit " + cfg.maxPages() + " reached; reduce max-window");
            }
            cursor = page.nextCursor();
            Thread.sleep(cfg.pageInterval());
        }

        shards.onGlobal(() -> checkpointMapper.advance(providerCode, LocalDateTime.ofInstant(to, BingoTime.ZONE), LocalDateTime.now(BingoTime.ZONE)));
        XxlJobHelper.log("{}: window {} - {}, pages={}, received={}, published={}", providerCode, from, to, pages, received, published);
    }

    /**
     * Resolves the page's player lines (one user-service call per 1000 players; a failure fails the run) and game
     * attributes, stores the page (one transaction per owning shard), then publishes the records not yet
     * acknowledged and waits for Kafka before flagging them.
     */
    private int storeAndPublish(String providerCode, List<ProviderBetRecordView> records) throws Exception {
        if (records.isEmpty()) {
            return 0;
        }
        PageLookups lookups = lookups(providerCode, records);
        Map<String, List<ProviderBetRecordView>> byShard = new LinkedHashMap<>();
        for (ProviderBetRecordView record : records) {
            if (shards.router().isMigrating(record.userId())) {
                // fails the run before the checkpoint advances; the next run pulls the window again (upserts)
                throw new ShardMigratingException(shards.router().logicalShard(record.userId()));
            }
            byShard.computeIfAbsent(shards.router().dataSourceOf(record.userId()), k -> new ArrayList<>()).add(record);
        }
        int published = 0;
        for (Map.Entry<String, List<ProviderBetRecordView>> shard : byShard.entrySet()) {
            published += storeAndPublishOnShard(providerCode, shard.getKey(), shard.getValue(), lookups);
        }
        return published;
    }

    private PageLookups lookups(String providerCode, List<ProviderBetRecordView> records) {
        Map<Long, Integer> lines = lineResolver.linesOf(providerCode, records.stream().map(ProviderBetRecordView::userId).toList());
        Map<String, GameInfo> games = new HashMap<>();
        for (ProviderBetRecordView record : records) {
            String gameCode = record.gameCode() == null ? "" : record.gameCode();
            games.computeIfAbsent(gameCode, code -> gameCatalog.lookup(providerCode, code));
        }
        return new PageLookups(lines, games);
    }

    private int storeAndPublishOnShard(String providerCode, String dataSource, List<ProviderBetRecordView> records,
                                       PageLookups lookups) throws Exception {
        List<ProviderBetEvent> toPublish = shards.onDataSource(dataSource,
                () -> MasterRoute.run(() -> recordService.upsertPage(providerCode, records, lookups)));
        if (toPublish.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<SendResult<String, String>>> sends = toPublish.stream()
                .map(e -> kafkaTemplate.send(Topics.PROVIDER_BET, String.valueOf(e.userId()), JsonUtils.toJson(e)))
                .toList();
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        shards.onDataSource(dataSource, () -> MasterRoute.run(() -> {
            recordService.markPublished(providerCode, toPublish);
            return null;
        }));
        return toPublish.size();
    }
}

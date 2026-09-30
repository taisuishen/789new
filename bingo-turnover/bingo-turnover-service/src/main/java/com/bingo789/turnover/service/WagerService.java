package com.bingo789.turnover.service;

import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.turnover.domain.BucketStatus;
import com.bingo789.turnover.domain.TurnoverBucket;
import com.bingo789.turnover.domain.TurnoverRecord;
import com.bingo789.turnover.mapper.TurnoverBucketMapper;
import com.bingo789.turnover.mapper.TurnoverRecordMapper;
import com.bingo789.turnover.service.SettingService.Rule;
import com.bingo789.turnover.service.SettingService.Rules;
import com.bingo789.turnover.service.Waterfall.Take;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the round-settled stream to the players' buckets, in real time (per round, like the balance; never
 * aggregated), writing a 稽核记录 (turnover_record) for every bucket a round reaches and every bucket it clears.
 * <p>
 * Per Kafka batch: one query per shard database finds the players that have any ACTIVE bucket (most have none and
 * cost nothing more). Each such player is then handled in one local transaction on their shard: read the ACTIVE
 * buckets, skip rounds already applied (their seq-0 WAGER record exists), run the waterfall and the automatic
 * clearing in memory for all of the player's rounds in the batch, insert the records, and write every touched
 * bucket once with an optimistic version check. Any failure (duplicate record from a concurrent redelivery, version
 * conflict, shard move) throws, the batch is redelivered, and the already-applied rounds are skipped the second time.
 * <p>
 * Kafka keys the stream by userId, so one player's rounds are applied in order by a single consumer. Latency is the
 * pipeline's (wallet commit -> CDC -> bet-record -> bingo.round.settled -> here), normally seconds; a withdrawal
 * checked in that gap sees a slightly higher outstanding amount, i.e. errs towards review, never towards release.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WagerService {

    private final TurnoverBucketMapper bucketMapper;
    private final TurnoverRecordMapper recordMapper;
    private final SettingService settingService;
    private final ShardTemplate shards;
    private final TransactionTemplate transactionTemplate;
    private final TurnoverRecords records;

    public void applySettledRounds(List<RoundSettledEvent> events) {
        Map<Long, List<SettledRound>> byUser = new LinkedHashMap<>();
        for (RoundSettledEvent event : events) {
            SettledRound round = SettledRound.of(event);
            if (round != null) {
                byUser.computeIfAbsent(round.userId(), k -> new ArrayList<>()).add(round);
            }
        }
        if (byUser.isEmpty()) {
            return;
        }
        Set<Long> withBuckets = usersWithActiveBuckets(byUser.keySet());
        if (withBuckets.isEmpty()) {
            return;
        }
        Rules rules = settingService.rules();
        for (Map.Entry<Long, List<SettledRound>> player : byUser.entrySet()) {
            long userId = player.getKey();
            if (withBuckets.contains(userId)) {
                shards.forUserWrite(userId, () -> transactionTemplate.execute(tx -> applyPlayer(userId, player.getValue(), rules)));
            }
        }
    }

    /** One IN query per shard database. */
    private Set<Long> usersWithActiveBuckets(Set<Long> userIds) {
        Map<String, List<Long>> byDataSource = new LinkedHashMap<>();
        for (long userId : userIds) {
            byDataSource.computeIfAbsent(shards.router().dataSourceOf(userId), k -> new ArrayList<>()).add(userId);
        }
        Set<Long> found = new HashSet<>();
        byDataSource.forEach((dataSource, ids) ->
                found.addAll(shards.onDataSource(dataSource, () -> bucketMapper.selectUsersWithActive(ids))));
        return found;
    }

    private Void applyPlayer(long userId, List<SettledRound> rounds, Rules rules) {
        List<TurnoverBucket> buckets = bucketMapper.selectActive(userId);
        if (buckets.isEmpty()) {
            return null;
        }
        Set<String> applied = new HashSet<>();
        List<String> keys = rounds.stream().filter(SettledRound::fills).map(SettledRound::roundKey).toList();
        if (!keys.isEmpty()) {
            applied.addAll(recordMapper.selectAppliedRoundKeys(keys));
        }

        Map<Long, TurnoverBucket> touched = new LinkedHashMap<>();
        List<TurnoverRecord> changes = new ArrayList<>();
        for (SettledRound round : rounds) {
            Rule rule = rules.of(round.userLine(), round.currency());
            if (round.fills() && applied.add(round.roundKey())) {
                List<Take> takes = Waterfall.fill(buckets, round, rule.completeBelowRemaining());
                for (int seq = 0; seq < takes.size(); seq++) {
                    Take take = takes.get(seq);
                    touched.put(take.bucket().getId(), take.bucket());
                    changes.add(records.wagered(round, seq, take));
                }
            }
            if (rule.clearBelowBalance() != null && round.balanceAfter() != null
                    && round.balanceAfter().compareTo(rule.clearBelowBalance()) < 0) {
                for (TurnoverBucket cleared : Waterfall.clearForLowBalance(buckets, round)) {
                    touched.put(cleared.getId(), cleared);
                    changes.add(records.cleared(cleared, cleared.remaining()));
                    log.info("wagering requirement {} of user {} cleared: balance {} below {} {}", cleared.getId(), userId,
                            round.balanceAfter().toPlainString(), rule.clearBelowBalance().toPlainString(), round.currency());
                }
            }
        }
        if (!changes.isEmpty()) {
            recordMapper.insertBatch(changes);
        }
        for (TurnoverBucket bucket : touched.values()) {
            if (bucketMapper.updateState(bucket) == 0) {
                // an operator / another consumer changed it meanwhile: roll back, the batch is redelivered
                throw new IllegalStateException("concurrent update of wagering requirement " + bucket.getId());
            }
            if (bucket.getStatus() == BucketStatus.COMPLETED) {
                log.info("wagering requirement {} ({} {}) of user {} completed ({})", bucket.getId(),
                        bucket.getSourceType(), bucket.getSourceNo(), userId, bucket.getCloseReason());
            }
        }
        return null;
    }
}

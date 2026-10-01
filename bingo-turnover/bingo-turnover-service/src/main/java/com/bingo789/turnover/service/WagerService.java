package com.bingo789.turnover.service;

import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.turnover.domain.BucketStatus;
import com.bingo789.turnover.domain.CloseReason;
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

import java.math.BigDecimal;
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
 * <p>
 * Round revisions (a late rollback / payout after the round was published, RoundSettledEvent.revision > 1) are rare
 * and applied one by one after the batch: what the round gave the buckets so far (its WAGER rows) is moved to the new
 * valid bet. More is filled through the waterfall; less is taken back from the buckets that took it, newest first,
 * reopening a bucket the round had completed. A bucket that was cleared or closed by an operator is not touched: that
 * part is an ALERT for manual review. A revision at or below the last one applied to the round is ignored.
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
        List<SettledRound> revisions = new ArrayList<>();
        for (RoundSettledEvent event : events) {
            SettledRound round = event.isRevision() ? SettledRound.revisionOf(event) : SettledRound.of(event);
            if (round == null) {
                continue;
            }
            if (event.isRevision()) {
                revisions.add(round);
            } else {
                byUser.computeIfAbsent(round.userId(), k -> new ArrayList<>()).add(round);
            }
        }
        if (byUser.isEmpty() && revisions.isEmpty()) {
            return;
        }
        Rules rules = settingService.rules();
        Set<Long> withBuckets = byUser.isEmpty() ? Set.of() : usersWithActiveBuckets(byUser.keySet());
        for (Map.Entry<Long, List<SettledRound>> player : byUser.entrySet()) {
            long userId = player.getKey();
            if (withBuckets.contains(userId)) {
                shards.forUserWrite(userId, () -> transactionTemplate.execute(tx -> applyPlayer(userId, player.getValue(), rules)));
            }
        }
        // after the first settlements of the batch: a revision may follow its round's first settlement in one batch
        for (SettledRound revision : revisions) {
            shards.forUserWrite(revision.userId(), () -> transactionTemplate.execute(tx -> applyRevision(revision, rules)));
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
        write(userId, changes, touched.values());
        return null;
    }

    private Void applyRevision(SettledRound round, Rules rules) {
        List<TurnoverRecord> applied = recordMapper.selectRoundWagers(round.roundKey());
        int lastRevision = applied.stream().mapToInt(r -> r.getRoundRevision() == null ? 1 : r.getRoundRevision()).max().orElse(0);
        if (round.revision() <= lastRevision) {
            return null;
        }
        BigDecimal given = applied.stream().map(TurnoverRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal target = round.validBet();
        Map<Long, TurnoverBucket> touched = new LinkedHashMap<>();
        List<TurnoverRecord> changes = new ArrayList<>();
        if (target.compareTo(given) > 0) {
            List<TurnoverBucket> buckets = bucketMapper.selectActive(round.userId());
            Rule rule = rules.of(round.userLine(), round.currency());
            List<Take> takes = Waterfall.fill(buckets, round.withValidBet(target.subtract(given)), rule.completeBelowRemaining());
            for (int seq = 0; seq < takes.size(); seq++) {
                Take take = takes.get(seq);
                touched.put(take.bucket().getId(), take.bucket());
                changes.add(records.wagered(round, seq, take));
            }
            write(round.userId(), changes, touched.values());
        } else if (target.compareTo(given) < 0) {
            takeBack(round, applied, given.subtract(target), changes, touched);
            if (!changes.isEmpty()) {
                recordMapper.insertBatch(changes);
            }
            for (TurnoverBucket bucket : touched.values()) {
                if (bucketMapper.updateRevised(bucket) == 0) {
                    throw new IllegalStateException("concurrent update of wagering requirement " + bucket.getId());
                }
            }
        }
        log.info("round {} revision {} of user {}: valid bet given to requirements {} -> {}", round.roundKey(),
                round.revision(), round.userId(), given.toPlainString(), target.toPlainString());
        return null;
    }

    /** Newest contribution first; a bucket completed by the round is reopened, a cleared / manual one is left alone. */
    private void takeBack(SettledRound round, List<TurnoverRecord> applied, BigDecimal excess,
                          List<TurnoverRecord> changes, Map<Long, TurnoverBucket> touched) {
        Map<Long, BigDecimal> byBucket = new LinkedHashMap<>();
        for (TurnoverRecord record : applied.reversed()) {
            byBucket.merge(record.getBucketId(), record.getAmount(), BigDecimal::add);
        }
        BigDecimal left = excess;
        int seq = 0;
        for (Map.Entry<Long, BigDecimal> entry : byBucket.entrySet()) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal back = left.min(entry.getValue());
            if (back.signum() <= 0) {
                continue;
            }
            TurnoverBucket bucket = bucketMapper.selectById(entry.getKey());
            if (bucket == null || !reversible(bucket)) {
                log.error("ALERT round {} revision {}: {} of valid bet taken by wagering requirement {} ({}) cannot be "
                                + "taken back automatically", round.roundKey(), round.revision(), back.toPlainString(),
                        entry.getKey(), bucket == null ? "missing" : bucket.getStatus() + "/" + bucket.getCloseReason());
                continue;
            }
            bucket.setAchievedAmount(bucket.getAchievedAmount().subtract(back));
            if (bucket.getStatus() == BucketStatus.COMPLETED) {
                bucket.setStatus(BucketStatus.ACTIVE);
                bucket.setCloseReason(null);
                bucket.setClosedBy(null);
                bucket.setClosedAt(null);
            }
            touched.put(bucket.getId(), bucket);
            changes.add(records.takenBack(round, seq++, bucket, back));
            left = left.subtract(back);
        }
    }

    /** ACTIVE, or COMPLETED by the waterfall itself (not cleared, voided or closed by an operator). */
    private static boolean reversible(TurnoverBucket bucket) {
        return bucket.getStatus() == BucketStatus.ACTIVE
                || bucket.getStatus() == BucketStatus.COMPLETED && Waterfall.SYSTEM.equals(bucket.getClosedBy())
                && (bucket.getCloseReason() == CloseReason.FULFILLED || bucket.getCloseReason() == CloseReason.REMAINING_BELOW_THRESHOLD);
    }

    private void write(long userId, List<TurnoverRecord> changes, Iterable<TurnoverBucket> touched) {
        if (!changes.isEmpty()) {
            recordMapper.insertBatch(changes);
        }
        for (TurnoverBucket bucket : touched) {
            if (bucketMapper.updateState(bucket) == 0) {
                // an operator / another consumer changed it meanwhile: roll back, the batch is redelivered
                throw new IllegalStateException("concurrent update of wagering requirement " + bucket.getId());
            }
            if (bucket.getStatus() == BucketStatus.COMPLETED) {
                log.info("wagering requirement {} ({} {}) of user {} completed ({})", bucket.getId(),
                        bucket.getSourceType(), bucket.getSourceNo(), userId, bucket.getCloseReason());
            }
        }
    }
}

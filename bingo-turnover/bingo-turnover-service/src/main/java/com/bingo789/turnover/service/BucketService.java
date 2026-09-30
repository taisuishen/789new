package com.bingo789.turnover.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.Money;
import com.bingo789.common.core.game.TurnoverScope;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.turnover.TurnoverErrorCode;
import com.bingo789.turnover.api.dto.BucketView;
import com.bingo789.turnover.api.dto.ClearBucketsCommand;
import com.bingo789.turnover.api.dto.ManualBucketCommand;
import com.bingo789.turnover.api.dto.OutstandingView;
import com.bingo789.turnover.api.dto.TurnoverRecordView;
import com.bingo789.turnover.domain.BucketStatus;
import com.bingo789.turnover.domain.CloseReason;
import com.bingo789.turnover.domain.SourceType;
import com.bingo789.turnover.domain.TurnoverBucket;
import com.bingo789.turnover.domain.TurnoverRecord;
import com.bingo789.turnover.mapper.TurnoverBucketMapper;
import com.bingo789.turnover.mapper.TurnoverRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Creates, closes and reads buckets; every call runs on the player's shard. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BucketService {

    private final TurnoverBucketMapper bucketMapper;
    private final TurnoverRecordMapper recordMapper;
    private final TurnoverRecords records;
    private final ShardTemplate shards;
    private final TransactionTemplate transactionTemplate;

    /** What a new bucket is made of; {@code scopeValue} null / blank for ALL. */
    public record NewBucket(long userId, int userLine, String currency, TurnoverScope scope, String scopeValue,
                            SourceType sourceType, String sourceNo, BigDecimal baseAmount, BigDecimal multiplier,
                            Instant createdAt, String operator) {
    }

    /**
     * Idempotent per (player, source type, source no). Returns null when the multiplier or amount is not positive
     * (no requirement), otherwise the stored bucket (the existing one on a redelivery).
     */
    public TurnoverBucket create(NewBucket spec) {
        if (spec.multiplier() == null || spec.multiplier().signum() <= 0
                || spec.baseAmount() == null || spec.baseAmount().signum() <= 0) {
            return null;
        }
        TurnoverBucket bucket = new TurnoverBucket();
        bucket.setUserId(spec.userId());
        bucket.setUserLine(UserLine.orDefault(spec.userLine()));
        bucket.setCurrency(spec.currency().toUpperCase(Locale.ROOT));
        bucket.setScopeType(spec.scope());
        bucket.setScopeValue(spec.scope() == TurnoverScope.ALL || spec.scopeValue() == null ? "" : spec.scopeValue());
        bucket.setScopeRank(TurnoverBucket.rankOf(spec.scope()));
        bucket.setSourceType(spec.sourceType());
        bucket.setSourceNo(spec.sourceNo());
        bucket.setBaseAmount(spec.baseAmount());
        bucket.setMultiplier(spec.multiplier());
        bucket.setRequiredAmount(spec.baseAmount().multiply(spec.multiplier()).setScale(Money.SCALE, RoundingMode.UP));
        bucket.setAchievedAmount(Money.ZERO);
        bucket.setStatus(BucketStatus.ACTIVE);
        bucket.setVersion(0);
        bucket.setCreatedAt(LocalDateTime.ofInstant(spec.createdAt(), BingoTime.ZONE));
        return shards.forUserWrite(spec.userId(), () -> {
            try {
                // the bucket and its CREATE record commit together
                transactionTemplate.executeWithoutResult(tx -> {
                    bucketMapper.insert(bucket);
                    recordMapper.insertBatch(List.of(records.created(bucket, spec.operator())));
                });
                log.info("wagering requirement {} created for user {}: {} {} x{} = {} {} ({} {})", bucket.getId(),
                        spec.userId(), spec.sourceType(), spec.baseAmount().toPlainString(),
                        spec.multiplier().toPlainString(), bucket.getRequiredAmount().toPlainString(),
                        bucket.getCurrency(), spec.scope(), bucket.getScopeValue());
                return bucket;
            } catch (RuntimeException e) {
                if (!DuplicateKeys.isDuplicateKey(e)) {
                    throw e;
                }
                return bucketMapper.selectBySource(spec.userId(), spec.sourceType().name(), spec.sourceNo());
            }
        });
    }

    public BucketView addManual(long userId, int userLine, ManualBucketCommand command) {
        TurnoverScope scope = parseScope(command.scopeType(), command.scopeValue());
        TurnoverBucket bucket = create(new NewBucket(userId, userLine, command.currency(), scope, command.scopeValue(),
                SourceType.MANUAL, command.ticketNo(), command.required(), BigDecimal.ONE, Instant.now(),
                command.operatorId()));
        log.info("manual wagering requirement {} for user {} by {}: {}", bucket.getId(), userId, command.operatorId(),
                command.reason());
        return view(bucket);
    }

    /** Closes the ACTIVE buckets as CLEARED (one CLEAR record each), all in one transaction on the player's shard. */
    public int clear(long userId, ClearBucketsCommand command) {
        String currency = command.currency().toUpperCase(Locale.ROOT);
        LocalDateTime now = BingoTime.now();
        int cleared = shards.forUserWrite(userId, () -> transactionTemplate.execute(tx -> {
            List<TurnoverBucket> targets = bucketMapper.selectActive(userId).stream()
                    .filter(b -> b.getCurrency().equals(currency))
                    .filter(b -> command.bucketId() == null || b.getId().equals(command.bucketId()))
                    .toList();
            List<TurnoverRecord> changes = new ArrayList<>(targets.size());
            for (TurnoverBucket bucket : targets) {
                BigDecimal dropped = bucket.remaining();
                bucket.setStatus(BucketStatus.CLEARED);
                bucket.setCloseReason(CloseReason.MANUAL);
                bucket.setClosedBy(command.operatorId());
                bucket.setClosedAt(now);
                if (bucketMapper.updateState(bucket) == 0) {
                    throw new BizException(TurnoverErrorCode.CONCURRENT_UPDATE);
                }
                changes.add(records.cleared(bucket, dropped));
            }
            if (!changes.isEmpty()) {
                recordMapper.insertBatch(changes);
            }
            return targets.size();
        }));
        if (command.bucketId() != null && cleared == 0) {
            throw new BizException(TurnoverErrorCode.BUCKET_NOT_FOUND);
        }
        log.info("{} wagering requirement(s) of user {} {} cleared by {}: {}", cleared, userId, currency,
                command.operatorId(), command.reason());
        return cleared;
    }

    /** 稽核记录, newest first; {@code bucketId} null = all buckets. */
    public List<TurnoverRecordView> records(long userId, Long bucketId, LineScope scope, int limit) {
        return shards.forUser(userId, () -> recordMapper.selectByUser(userId, bucketId,
                        scope.isAll() ? null : scope.lines(), Math.clamp(limit, 1, 500)))
                .stream()
                .map(BucketService::view)
                .toList();
    }

    public OutstandingView outstanding(long userId, String currency) {
        String cur = currency.toUpperCase(Locale.ROOT);
        List<TurnoverBucket> active = shards.forUser(userId, () -> bucketMapper.selectActive(userId)).stream()
                .filter(b -> b.getCurrency().equals(cur))
                .toList();
        BigDecimal required = Money.ZERO;
        BigDecimal achieved = Money.ZERO;
        BigDecimal remaining = Money.ZERO;
        for (TurnoverBucket b : active) {
            required = required.add(b.getRequiredAmount());
            achieved = achieved.add(b.getAchievedAmount());
            remaining = remaining.add(b.remaining());
        }
        return new OutstandingView(userId, cur, active.size(), required, achieved, remaining);
    }

    public List<BucketView> list(long userId, String status, LineScope scope) {
        String filter = status == null || status.isBlank() ? null : BucketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        return shards.forUser(userId, () -> bucketMapper.selectByUser(userId, filter, scope.isAll() ? null : scope.lines()))
                .stream()
                .map(BucketService::view)
                .toList();
    }

    public List<BucketView> active(long userId) {
        return shards.forUser(userId, () -> bucketMapper.selectActive(userId)).stream().map(BucketService::view).toList();
    }

    /** Configuration input: unknown scopes / values are errors. */
    public static TurnoverScope parseScope(String scopeType, String scopeValue) {
        String error = TurnoverScope.validate(scopeType, scopeValue);
        if (error != null) {
            throw new BizException(TurnoverErrorCode.INVALID_SCOPE, error);
        }
        return scopeType == null ? TurnoverScope.ALL : TurnoverScope.valueOf(scopeType);
    }

    static TurnoverRecordView view(TurnoverRecord r) {
        return new TurnoverRecordView(r.getId(), r.getBucketId(), r.getUserLine(), r.getRecordType().name(),
                r.getCurrency(), r.getAmount(), r.getAchievedAfter(), r.getRemainingAfter(), r.getStatusAfter().name(),
                r.getReason(), r.getOperator(), r.getProviderCode(), r.getGameCode(), r.getGameType(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().atZone(BingoTime.ZONE).toInstant());
    }

    static BucketView view(TurnoverBucket b) {
        return new BucketView(b.getId(), b.getUserId(), b.getUserLine(), b.getCurrency(), b.getScopeType().name(),
                b.getScopeValue() == null || b.getScopeValue().isEmpty() ? null : b.getScopeValue(),
                b.getSourceType().name(), b.getSourceNo(), b.getBaseAmount(), b.getMultiplier(), b.getRequiredAmount(),
                b.getAchievedAmount(), b.remaining(), b.getStatus().name(),
                b.getCloseReason() == null ? null : b.getCloseReason().name(),
                b.getCreatedAt().atZone(BingoTime.ZONE).toInstant(),
                b.getClosedAt() == null ? null : b.getClosedAt().atZone(BingoTime.ZONE).toInstant());
    }
}

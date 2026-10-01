package com.bingo789.turnover.service;

import com.bingo789.common.core.Money;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.turnover.domain.BucketStatus;
import com.bingo789.turnover.domain.RecordType;
import com.bingo789.turnover.domain.TurnoverBucket;
import com.bingo789.turnover.domain.TurnoverRecord;
import com.bingo789.turnover.service.Waterfall.Take;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Builds 稽核记录 rows from the bucket state right after the change (call it before the bucket changes again). */
@Component
@RequiredArgsConstructor
public class TurnoverRecords {

    /** reason of a negative WAGER row. */
    static final String ROUND_REVISED = "ROUND_REVISED";

    private final SnowflakeIdGenerator idGenerator;

    public TurnoverRecord created(TurnoverBucket bucket, String operator) {
        TurnoverRecord record = base(bucket, RecordType.CREATE, bucket.getRequiredAmount(), LocalDate.now(BingoTime.ZONE));
        record.setReason(bucket.getSourceType().name());
        record.setOperator(operator);
        return record;
    }

    /** @param seq the bucket's position in the round's waterfall */
    public TurnoverRecord wagered(SettledRound round, int seq, Take take) {
        TurnoverRecord record = base(take.bucket(), RecordType.WAGER, take.amount(), round.betDate());
        roundFields(record, round, seq);
        if (take.bucket().getStatus() != BucketStatus.ACTIVE) {
            record.setReason(take.bucket().getCloseReason().name());
            record.setOperator(take.bucket().getClosedBy());
        }
        return record;
    }

    /** A round revision took {@code amount} of valid bet back from the bucket (stored as a negative WAGER). */
    public TurnoverRecord takenBack(SettledRound round, int seq, TurnoverBucket bucket, BigDecimal amount) {
        TurnoverRecord record = base(bucket, RecordType.WAGER, amount.negate(), round.betDate());
        roundFields(record, round, seq);
        record.setReason(ROUND_REVISED);
        record.setOperator(Waterfall.SYSTEM);
        return record;
    }

    private static void roundFields(TurnoverRecord record, SettledRound round, int seq) {
        record.setUserLine(round.userLine());
        record.setRoundKey(round.roundKey());
        record.setRoundRevision(round.revision());
        record.setSeq(seq);
        record.setProviderCode(round.providerCode());
        record.setGameCode(round.gameCode());
        record.setGameType(round.gameType());
    }

    /** @param dropped the remainder that no longer has to be wagered */
    public TurnoverRecord cleared(TurnoverBucket bucket, BigDecimal dropped) {
        TurnoverRecord record = base(bucket, RecordType.CLEAR, dropped, LocalDate.now(BingoTime.ZONE));
        record.setReason(bucket.getCloseReason().name());
        record.setOperator(bucket.getClosedBy());
        return record;
    }

    private TurnoverRecord base(TurnoverBucket bucket, RecordType type, BigDecimal amount, LocalDate date) {
        TurnoverRecord record = new TurnoverRecord();
        record.setId(idGenerator.nextId());
        record.setUserId(bucket.getUserId());
        record.setUserLine(bucket.getUserLine());
        record.setBucketId(bucket.getId());
        record.setRecordType(type);
        record.setCurrency(bucket.getCurrency());
        record.setAmount(amount);
        record.setAchievedAfter(bucket.getAchievedAmount());
        record.setRemainingAfter(bucket.getStatus() == BucketStatus.ACTIVE ? bucket.remaining() : Money.ZERO);
        record.setStatusAfter(bucket.getStatus());
        record.setRecordDate(date);
        return record;
    }
}

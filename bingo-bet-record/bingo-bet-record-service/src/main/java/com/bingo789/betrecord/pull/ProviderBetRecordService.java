package com.bingo789.betrecord.pull;

import com.bingo789.betrecord.catalog.GameInfo;
import com.bingo789.betrecord.entity.ProviderBetRecord;
import com.bingo789.betrecord.mapper.ProviderBetRecordMapper;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.ProviderBetEvent;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores one page of provider bet records and tells the caller which ones still have to be published.
 * <p>
 * A record is published once, when first stored (or again if a previous run stored it but crashed before
 * Kafka acknowledged it). Re-pulled records whose status or payout changed are updated here but NOT re-published:
 * ProviderBetEvent is a snapshot and reconcile's hourly aggregate is additive, so a second event would double
 * count. The per-record daily reconciliation (StarRocks, upsert by providerBetId) is authoritative for such changes.
 * TODO: add a revision / previous values to ProviderBetEvent if hourly aggregates must follow re-settlements.
 * <p>
 * user_line, game_type and game_name are taken when a record is first stored and kept on re-pulls; an event
 * re-sent for a record stored by an earlier run carries the stored line.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderBetRecordService {

    private final ProviderBetRecordMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Caller runs this under MasterRoute: the published flags must be read from the primary.
     *
     * @return the events still to be published, in page order
     */
    @Transactional
    public List<ProviderBetEvent> upsertPage(String providerCode, List<ProviderBetRecordView> records, PageLookups lookups) {
        Map<String, ProviderBetRecordView> valid = new LinkedHashMap<>();
        for (ProviderBetRecordView record : records) {
            if (isValid(providerCode, record)) {
                valid.put(record.providerBetId(), record); // last occurrence wins within a page
            }
        }
        if (valid.isEmpty()) {
            return List.of();
        }
        List<ProviderBetRecord> rows = valid.values().stream().map(r -> toEntity(r, lookups)).toList();
        TimeRange range = TimeRange.of(rows);
        Map<String, ProviderBetRecord> stored = new HashMap<>();
        for (ProviderBetRecord row : mapper.findStored(providerCode, valid.keySet(), range.from(), range.to())) {
            stored.putIfAbsent(row.getProviderBetId(), row);
        }
        mapper.upsertBatch(rows);
        List<ProviderBetEvent> toPublish = new ArrayList<>(valid.size());
        for (ProviderBetRecordView record : valid.values()) {
            ProviderBetRecord existing = stored.get(record.providerBetId());
            if (existing == null) {
                toPublish.add(toEvent(record, lookups.lineOf(record.userId())));
            } else if (!Boolean.TRUE.equals(existing.getPublished())) {
                toPublish.add(toEvent(record, existing.getUserLine()));
            }
        }
        return toPublish;
    }

    @Transactional
    public void markPublished(String providerCode, List<ProviderBetEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<LocalDateTime> betTimes = events.stream().map(e -> local(e.betTime())).toList();
        mapper.markPublished(providerCode, events.stream().map(ProviderBetEvent::providerBetId).toList(),
                betTimes.stream().min(Comparator.naturalOrder()).orElseThrow(),
                betTimes.stream().max(Comparator.naturalOrder()).orElseThrow());
    }

    private static boolean isValid(String providerCode, ProviderBetRecordView record) {
        boolean valid = record != null
                && providerCode.equals(record.providerCode())
                && record.providerBetId() != null && !record.providerBetId().isBlank()
                && record.betTime() != null
                && record.currency() != null
                && record.betAmount() != null;
        if (!valid) {
            log.warn("invalid provider bet record from {} skipped: {}", providerCode, record);
        }
        return valid;
    }

    private ProviderBetRecord toEntity(ProviderBetRecordView view, PageLookups lookups) {
        ProviderBetRecord row = new ProviderBetRecord();
        row.setId(idGenerator.nextId());
        row.setProviderCode(view.providerCode());
        row.setProviderBetId(view.providerBetId());
        row.setRoundId(view.roundId());
        row.setUserId(view.userId());
        // only written for new records: the upsert keeps the stored line and game attributes
        row.setUserLine(lookups.lineOf(view.userId()));
        row.setCurrency(view.currency());
        row.setGameCode(view.gameCode() == null ? "" : view.gameCode());
        GameInfo game = lookups.gameOf(row.getGameCode());
        row.setGameType(game.gameType());
        row.setGameName(game.gameName());
        row.setBetAmount(view.betAmount());
        row.setPayoutAmount(view.payoutAmount() == null ? BigDecimal.ZERO : view.payoutAmount());
        row.setStatus(view.status() == null ? "UNKNOWN" : view.status());
        row.setBetTime(local(view.betTime()));
        row.setSettleTime(view.settleTime() == null ? null : local(view.settleTime()));
        return row;
    }

    /** The payload is the provider's view as received (not the normalized row) plus the record's line. */
    private static ProviderBetEvent toEvent(ProviderBetRecordView r, Integer userLine) {
        return new ProviderBetEvent(r.providerCode(), r.providerBetId(), r.roundId(), r.userId(), userLine, r.currency(),
                r.gameCode(), r.betAmount(), r.payoutAmount(), r.status(), r.betTime(), r.settleTime());
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, BingoTime.ZONE);
    }

    private record TimeRange(LocalDateTime from, LocalDateTime to) {

        static TimeRange of(List<ProviderBetRecord> rows) {
            LocalDateTime from = rows.getFirst().getBetTime();
            LocalDateTime to = from;
            for (ProviderBetRecord row : rows) {
                if (row.getBetTime().isBefore(from)) {
                    from = row.getBetTime();
                }
                if (row.getBetTime().isAfter(to)) {
                    to = row.getBetTime();
                }
            }
            return new TimeRange(from, to);
        }
    }
}

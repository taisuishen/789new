package com.bingo789.reconcile.ingest;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.ProviderBetEvent;
import com.bingo789.common.mq.event.WalletTxnEvent;
import com.bingo789.reconcile.mapper.PlatformHourlyMapper;
import com.bingo789.reconcile.mapper.ProviderHourlyMapper;
import com.bingo789.reconcile.model.HourKey;
import com.bingo789.reconcile.model.PlatformHourlyRow;
import com.bingo789.reconcile.model.ProviderHourlyRow;
import com.bingo789.wallet.api.enums.TxnType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Folds one Kafka batch into the hourly aggregates. Aggregates and consumed offsets are written in ONE local
 * transaction, so a redelivered batch (rebalance, crash, error-handler retry) never counts twice.
 * One upsert per aggregate row per batch keeps write amplification low even for hot games.
 * <p>
 * Rows are kept per user line (the line carried by each event, i.e. the player's line when the txn / record was
 * written) for line-scoped reports; comparisons with the provider always sum all lines.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregationService {

    private static final int STATUS_NORMAL = 1;
    /** Provider statuses (as normalized by game-integration) whose stake and win do not count. */
    private static final Set<String> VOID_STATUSES = Set.of("CANCELLED", "CANCELED", "VOID", "REFUNDED", "ROLLBACK");

    private final KafkaOffsetStore offsetStore;
    private final PlatformHourlyMapper platformMapper;
    private final ProviderHourlyMapper providerMapper;

    @Transactional
    public void applyLedgerBatch(String group, String topic, List<ConsumerRecord<String, String>> records) {
        KafkaOffsetStore.Pending pending = offsetStore.lockAndFilter(group, topic, records);
        if (pending.isEmpty()) {
            return;
        }
        Map<HourKey, PlatformHourlyRow> rows = new TreeMap<>();
        for (ConsumerRecord<String, String> record : pending.records()) {
            WalletTxnEvent event = parse(record, WalletTxnEvent.class);
            if (event != null && event.status() == STATUS_NORMAL) {
                accumulate(rows, event);
            }
        }
        if (!rows.isEmpty()) {
            platformMapper.upsert(new ArrayList<>(rows.values()));
        }
        offsetStore.save(group, topic, pending.nextOffsets());
    }

    @Transactional
    public void applyProviderBatch(String group, String topic, List<ConsumerRecord<String, String>> records) {
        KafkaOffsetStore.Pending pending = offsetStore.lockAndFilter(group, topic, records);
        if (pending.isEmpty()) {
            return;
        }
        Map<HourKey, ProviderHourlyRow> rows = new TreeMap<>();
        for (ConsumerRecord<String, String> record : pending.records()) {
            ProviderBetEvent event = parse(record, ProviderBetEvent.class);
            if (event == null || event.betTime() == null || event.providerCode() == null || event.currency() == null) {
                continue;
            }
            boolean voided = event.status() != null && VOID_STATUSES.contains(event.status().toUpperCase(Locale.ROOT));
            HourKey key = new HourKey(hourOf(event.betTime()), event.userLine(), event.providerCode(), nz(event.gameCode()),
                    event.currency());
            rows.computeIfAbsent(key, ProviderHourlyRow::new)
                    .add(voided ? BigDecimal.ZERO : nz(event.betAmount()), voided ? BigDecimal.ZERO : nz(event.payoutAmount()));
        }
        if (!rows.isEmpty()) {
            providerMapper.upsert(new ArrayList<>(rows.values()));
        }
        offsetStore.save(group, topic, pending.nextOffsets());
    }

    /** Game transactions only; platform types (deposit, withdraw, bonus, transfer ...) are ignored. */
    private static void accumulate(Map<HourKey, PlatformHourlyRow> rows, WalletTxnEvent event) {
        TxnType type;
        try {
            type = TxnType.valueOf(event.txnType());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("unknown txn type {} in wallet txn {}", event.txnType(), event.id());
            return;
        }
        if (type.category() != TxnType.Category.GAME) {
            return;
        }
        if (event.providerCode() == null || event.currency() == null || event.createdAt() == null) {
            log.error("incomplete game txn {} skipped: provider={}, currency={}, createdAt={}",
                    event.id(), event.providerCode(), event.currency(), event.createdAt());
            return;
        }
        HourKey key = new HourKey(hourOf(event.createdAt()), event.userLine(), event.providerCode(), nz(event.gameCode()),
                event.currency());
        PlatformHourlyRow row = rows.computeIfAbsent(key, PlatformHourlyRow::new);
        BigDecimal amount = event.amount() == null ? BigDecimal.ZERO : event.amount();
        switch (type) {
            case BET -> row.addBet(amount);
            case ROLLBACK -> row.addRollback(amount);
            case PAYOUT, FREE_PAYOUT, JACKPOT_PAYOUT, PROMO_PAYOUT -> row.addPayout(amount);
            case PAYOUT_REVERSAL -> row.addPayoutReversal(amount);
            case ADJUST -> row.addAdjust(amount.multiply(BigDecimal.valueOf(Integer.signum(event.direction()))));
            default -> {
                // not a game type; filtered above
            }
        }
    }

    /** A malformed payload is a producer bug: logged with its coordinates and skipped (its offset still advances). */
    private static <T> T parse(ConsumerRecord<String, String> record, Class<T> type) {
        if (record.value() == null) {
            return null;
        }
        try {
            return JsonUtils.fromJson(record.value(), type);
        } catch (RuntimeException e) {
            log.error("malformed {} skipped at {}-{}@{}: {}", type.getSimpleName(), record.topic(), record.partition(),
                    record.offset(), e.toString());
            return null;
        }
    }

    private static LocalDateTime hourOf(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.HOURS), BingoTime.ZONE);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

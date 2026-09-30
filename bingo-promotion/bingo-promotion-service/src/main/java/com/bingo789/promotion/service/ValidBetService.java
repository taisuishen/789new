package com.bingo789.promotion.service;

import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.domain.ValidBetDaily;
import com.bingo789.promotion.mapper.PromotionRoundAppliedMapper;
import com.bingo789.promotion.mapper.ValidBetDailyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Daily valid bet per (business day, player, currency, provider), the base of rebates. */
@Service
@RequiredArgsConstructor
public class ValidBetService {

    private static final String SETTLED = "SETTLED";

    private final ValidBetDailyMapper validBetMapper;
    private final PromotionRoundAppliedMapper roundAppliedMapper;
    private final TransactionTemplate transactionTemplate;
    private final PromotionProperties properties;

    /**
     * Applies one Kafka batch in one transaction: per-round dedupe rows, then one multi-row upsert of the batch
     * pre-aggregated per day/player/currency/provider. Any failure rolls back and the batch is redelivered;
     * already-applied rounds are then skipped. stat_date is the settlement time in the business zone.
     * user_line is the line of the player's latest round of that day: a player migrated mid-day ends up with the
     * day on the later line.
     * TODO: corrections (a round cancelled or re-settled after it was applied) need delta events from bet-record.
     */
    public void applySettledRounds(List<RoundSettledEvent> events) {
        Map<String, RoundSettledEvent> rounds = new LinkedHashMap<>();
        for (RoundSettledEvent e : events) {
            if (SETTLED.equals(e.status()) && e.validBet() != null && e.validBet().signum() > 0
                    && e.providerCode() != null && e.roundId() != null && e.currency() != null
                    && (e.settledTime() != null || e.betTime() != null)) {
                rounds.putIfAbsent(roundKey(e), e);
            }
        }
        if (rounds.isEmpty()) {
            return;
        }
        ZoneId zone = properties.zone();
        transactionTemplate.executeWithoutResult(tx -> {
            roundAppliedMapper.selectExisting(rounds.keySet()).forEach(rounds::remove);
            if (rounds.isEmpty()) {
                return;
            }
            roundAppliedMapper.insertBatch(rounds.keySet());
            // sorted by key: concurrent upserts touch rows in the same order
            Map<DailyKey, ValidBetDaily> rows = new TreeMap<>();
            Map<UserDay, Integer> latestLines = new HashMap<>();
            for (RoundSettledEvent e : rounds.values()) {
                Instant at = e.settledTime() != null ? e.settledTime() : e.betTime();
                LocalDate statDate = LocalDate.ofInstant(at, zone);
                DailyKey key = new DailyKey(statDate, e.userId(), e.currency(), e.providerCode());
                ValidBetDaily row = rows.computeIfAbsent(key, DailyKey::newRow);
                row.setValidBet(row.getValidBet().add(e.validBet()));
                row.setRoundCount(row.getRoundCount() + 1);
                // batch order is the player's stream order (message key = userId): the latest round's line wins
                latestLines.put(new UserDay(statDate, e.userId()), e.userLine());
            }
            // every row of a user-day written by this batch gets the same line, so the most recently updated row of
            // the day (which RebateService reads) never disagrees with its siblings from the same statement
            for (ValidBetDaily row : rows.values()) {
                row.setUserLine(latestLines.get(new UserDay(row.getStatDate(), row.getUserId())));
            }
            validBetMapper.upsertBatch(new ArrayList<>(rows.values()));
        });
    }

    /** Includes the user: in multi-player games (e.g. bingo rooms) many players share one provider round id. */
    static String roundKey(RoundSettledEvent e) {
        return e.providerCode() + ':' + e.roundId() + ':' + e.userId();
    }

    private record UserDay(LocalDate statDate, long userId) {
    }

    private record DailyKey(LocalDate statDate, long userId, String currency, String providerCode)
            implements Comparable<DailyKey> {

        private static final Comparator<DailyKey> ORDER = Comparator.comparing(DailyKey::statDate)
                .thenComparingLong(DailyKey::userId)
                .thenComparing(DailyKey::currency)
                .thenComparing(DailyKey::providerCode);

        @Override
        public int compareTo(DailyKey other) {
            return ORDER.compare(this, other);
        }

        ValidBetDaily newRow() {
            ValidBetDaily row = new ValidBetDaily();
            row.setStatDate(statDate);
            row.setUserId(userId);
            row.setCurrency(currency);
            row.setProviderCode(providerCode);
            row.setValidBet(BigDecimal.ZERO);
            row.setRoundCount(0);
            return row;
        }
    }
}

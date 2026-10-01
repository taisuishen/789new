package com.bingo789.promotion.service;

import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.domain.PromotionRoundApplied;
import com.bingo789.promotion.domain.ValidBetDaily;
import com.bingo789.promotion.mapper.PromotionRoundAppliedMapper;
import com.bingo789.promotion.mapper.ValidBetDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Daily valid bet per (business day, player, currency, provider), the base of rebates.
 * <p>
 * promotion_round_applied remembers, per round, the revision and valid bet already counted. A later revision of the
 * round (RoundSettledEvent.revision, e.g. its bets rolled back after it was settled) adds the DIFFERENCE to the same
 * day; an older or already applied revision changes nothing. A change to a day whose rebate may already have been
 * settled is logged: settled rebates are not recalculated automatically.
 */
@Slf4j
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
     * already-applied rounds are then skipped. stat_date is the settlement time in the business zone (a revision keeps
     * the round's settlement time, so it lands on the same day).
     * user_line is the line of the player's latest round of that day: a player migrated mid-day ends up with the
     * day on the later line.
     */
    public void applySettledRounds(List<RoundSettledEvent> events) {
        Map<String, RoundSettledEvent> rounds = new LinkedHashMap<>();
        for (RoundSettledEvent e : events) {
            if (e.providerCode() == null || e.roundId() == null || e.currency() == null
                    || (e.settledTime() == null && e.betTime() == null)) {
                continue;
            }
            // a first settlement without valid bet counts nothing; a revision may take earlier valid bet back
            if (!e.isRevision() && validBet(e).signum() == 0) {
                continue;
            }
            // the newest revision of a round in the batch wins
            rounds.merge(roundKey(e), e, (a, b) -> b.revision() >= a.revision() ? b : a);
        }
        if (rounds.isEmpty()) {
            return;
        }
        ZoneId zone = properties.zone();
        LocalDate today = LocalDate.now(zone);
        transactionTemplate.executeWithoutResult(tx -> {
            Map<String, PromotionRoundApplied> applied = new HashMap<>();
            for (PromotionRoundApplied row : roundAppliedMapper.selectExisting(rounds.keySet())) {
                applied.put(row.getRoundKey(), row);
            }
            List<PromotionRoundApplied> inserts = new ArrayList<>();
            // sorted by key: concurrent upserts touch rows in the same order
            Map<DailyKey, ValidBetDaily> rows = new TreeMap<>();
            Map<UserDay, Integer> latestLines = new HashMap<>();
            for (Map.Entry<String, RoundSettledEvent> entry : rounds.entrySet()) {
                RoundSettledEvent e = entry.getValue();
                BigDecimal target = validBet(e);
                PromotionRoundApplied previous = applied.get(entry.getKey());
                BigDecimal delta;
                int roundCountDelta;
                if (previous == null) {
                    if (target.signum() == 0) {
                        continue;
                    }
                    inserts.add(new PromotionRoundApplied(entry.getKey(), e.revision(), target));
                    delta = target;
                    roundCountDelta = 1;
                } else if (e.revision() > previous.getRevision()) {
                    if (roundAppliedMapper.updateRevision(entry.getKey(), e.revision(), target, previous.getRevision()) == 0) {
                        throw new IllegalStateException("round " + entry.getKey() + " revised concurrently");
                    }
                    delta = target.subtract(previous.getValidBet());
                    roundCountDelta = Integer.compare(target.signum(), 0) - Integer.compare(previous.getValidBet().signum(), 0);
                } else {
                    continue;
                }
                if (delta.signum() == 0 && roundCountDelta == 0) {
                    continue;
                }
                Instant at = e.settledTime() != null ? e.settledTime() : e.betTime();
                LocalDate statDate = LocalDate.ofInstant(at, zone);
                if (previous != null && statDate.isBefore(today)) {
                    log.warn("valid bet of round {} on {} changed by {} (revision {}): that day's rebate may already be settled",
                            entry.getKey(), statDate, delta.toPlainString(), e.revision());
                }
                DailyKey key = new DailyKey(statDate, e.userId(), e.currency(), e.providerCode());
                ValidBetDaily row = rows.computeIfAbsent(key, DailyKey::newRow);
                row.setValidBet(row.getValidBet().add(delta));
                row.setRoundCount(row.getRoundCount() + roundCountDelta);
                // batch order is the player's stream order (message key = userId): the latest round's line wins
                latestLines.put(new UserDay(statDate, e.userId()), e.userLine());
            }
            if (!inserts.isEmpty()) {
                roundAppliedMapper.insertBatch(inserts);
            }
            if (rows.isEmpty()) {
                return;
            }
            // every row of a user-day written by this batch gets the same line, so the most recently updated row of
            // the day (which RebateService reads) never disagrees with its siblings from the same statement
            for (ValidBetDaily row : rows.values()) {
                row.setUserLine(latestLines.get(new UserDay(row.getStatDate(), row.getUserId())));
            }
            validBetMapper.upsertBatch(new ArrayList<>(rows.values()));
        });
    }

    /** Valid bet of a settled round; 0 for a cancelled one. */
    private static BigDecimal validBet(RoundSettledEvent e) {
        return SETTLED.equals(e.status()) && e.validBet() != null && e.validBet().signum() > 0 ? e.validBet() : BigDecimal.ZERO;
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

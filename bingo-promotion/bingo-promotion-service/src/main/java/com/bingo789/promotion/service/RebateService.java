package com.bingo789.promotion.service;

import com.bingo789.common.core.Money;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.BonusGrantedEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.promotion.common.Texts;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionEntry;
import com.bingo789.promotion.domain.PromotionType;
import com.bingo789.promotion.domain.RebateRecord;
import com.bingo789.promotion.domain.RebateStatus;
import com.bingo789.promotion.domain.ValidBetDaily;
import com.bingo789.promotion.mapper.PromotionMapper;
import com.bingo789.promotion.mapper.RebateRecordMapper;
import com.bingo789.promotion.mapper.ValidBetDailyMapper;
import com.bingo789.promotion.terms.RebateTerms;
import com.bingo789.promotion.web.dto.RebateRecordView;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily rebates on valid bet, with the terms of the REBATE promotion of each player's line. Computation and payment
 * are separate steps so a payment problem never blocks the computation, and PENDING rows of earlier days are
 * retried by every run.
 * TODO(compliance): confirm whether rebates count as marketing incentives for self-excluded players; if so,
 *  check UserClient.playerStatus(userId).canPlay before paying.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RebateService {

    /** Wallet idempotency source for every promotion credit. */
    static final String WALLET_SOURCE = "PROMOTION";
    static final String BONUS_TYPE = "REBATE";
    private static final int USER_PAGE = 500;
    private static final int PAY_PAGE = 200;

    /** Latest update wins; a tie (same millisecond) is broken by the higher line, only to stay deterministic. */
    private static final Comparator<ValidBetDaily> LATEST = Comparator
            .comparing(ValidBetDaily::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ValidBetDaily::getUserLine, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final ValidBetDailyMapper validBetMapper;
    private final PromotionMapper promotionMapper;
    private final RebateRecordMapper recordMapper;
    private final WalletClient walletClient;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final SnowflakeIdGenerator idGenerator;
    private final PromotionProperties properties;

    public record PayStats(int paid, int failed, int unknown) {
    }

    /**
     * Computes the rebates of one closed business day. A user-day's line is the line at the end of the day (that of
     * its most recently updated valid_bet_daily row); the user-day uses the ONLINE REBATE promotion active at any
     * time during the day whose user_lines contain that line (several: highest sort, then newest), none = no rebate.
     * With those terms: per provider line valid_bet x rate (provider rate, else the default rate), capped by that
     * rate's daily cap, summed per player and currency, rounded down.
     * Idempotent: an existing (stat_date, user, currency) row is kept as is.
     * TODO: valid bet settled after the day was computed is not rebated; needs an adjustment run.
     *
     * @return number of rebate rows created
     * @throws IllegalStateException when a candidate promotion's stored terms are invalid (nothing is written)
     */
    public int computeRebates(LocalDate statDate) {
        List<Programme> programmes = programmes(statDate);
        if (programmes.isEmpty()) {
            log.info("no ONLINE rebate promotion was active on {}, no rebates", statDate);
            return 0;
        }
        long afterUserId = 0;
        int created = 0;
        while (true) {
            List<Long> userIds = validBetMapper.selectUserIds(statDate, afterUserId, USER_PAGE);
            if (userIds.isEmpty()) {
                break;
            }
            afterUserId = userIds.getLast();
            List<RebateRecord> records = toRecords(statDate, validBetMapper.selectByUsers(statDate, userIds), programmes);
            if (!records.isEmpty()) {
                created += recordMapper.insertIgnoreBatch(records);
            }
        }
        return created;
    }

    /** Pays every PENDING rebate, whatever its day. */
    public PayStats payPending() {
        long afterId = 0;
        int paid = 0;
        int failed = 0;
        int unknown = 0;
        while (true) {
            long cursor = afterId;
            List<RebateRecord> page = MasterRoute.run(() -> recordMapper.selectPending(cursor, PAY_PAGE));
            if (page.isEmpty()) {
                break;
            }
            for (RebateRecord record : page) {
                afterId = record.getId();
                switch (pay(record)) {
                    case PAID -> paid++;
                    case FAILED -> failed++;
                    case UNKNOWN -> unknown++;
                }
            }
        }
        return new PayStats(paid, failed, unknown);
    }

    public List<RebateRecordView> listOwn(long userId, LocalDate from, LocalDate to) {
        return recordMapper.selectByUser(userId, from, to).stream().map(RebateRecordView::of).toList();
    }

    /**
     * Wallet credit (idempotent on PROMOTION / "REBATE-" + id / REBATE), then PAID + outbox in one transaction.
     * The event carries the line and wagering terms stored with the record.
     */
    private PayOutcome pay(RebateRecord record) {
        String bizNo = "REBATE-" + record.getId();
        WalletResult wallet;
        try {
            wallet = walletClient.platformTxn(new PlatformTxnCommand(record.getUserId(), record.getCurrency(), TxnType.REBATE,
                    WALLET_SOURCE, bizNo, record.getAmount(), "rebate " + record.getStatDate()));
        } catch (RuntimeException e) {
            log.warn("rebate {} outcome unknown, stays PENDING for the next run", bizNo, e);
            return PayOutcome.UNKNOWN;
        }
        if (!wallet.isSuccess()) {
            log.warn("rebate {} refused by the wallet: {} {}", bizNo, wallet.code(), wallet.message());
            recordMapper.markFailed(record.getId(), Texts.truncate(wallet.code() + ": " + wallet.message(), 255));
            return PayOutcome.FAILED;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            if (recordMapper.markPaid(record.getId()) == 1) {
                outboxService.save(Topics.BONUS_GRANTED, bizNo, new BonusGrantedEvent(bizNo,
                        record.getUserId(), record.getUserLine(), record.getCurrency(), record.getAmount(), BONUS_TYPE,
                        record.getTurnoverMultiplier(), record.getTurnoverScope(), record.getTurnoverScopeValue(),
                        Instant.now()));
            }
        });
        return PayOutcome.PAID;
    }

    /** REBATE promotions ONLINE and active at any time during the business day, highest priority first. */
    private List<Programme> programmes(LocalDate statDate) {
        ZoneId zone = properties.zone();
        LocalDateTime dayStart = BingoTime.toLocal(statDate.atStartOfDay(zone).toInstant());
        LocalDateTime dayEnd = BingoTime.toLocal(statDate.plusDays(1).atStartOfDay(zone).toInstant());
        List<Programme> programmes = new ArrayList<>();
        for (Promotion row : promotionMapper.selectOnlineOverlapping(PromotionType.REBATE.name(), dayStart, dayEnd)) {
            PromotionEntry entry = PromotionEntry.of(row);
            try {
                programmes.add(new Programme(entry.id(), entry.userLines(), RebateTerms.parse(entry.config())));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("ALERT promotion " + entry.id() + " has invalid rebate terms: " + e.getMessage(), e);
            }
        }
        return programmes;
    }

    private List<RebateRecord> toRecords(LocalDate statDate, List<ValidBetDaily> rows, List<Programme> programmes) {
        Map<Long, List<ValidBetDaily>> perUser = new LinkedHashMap<>();
        for (ValidBetDaily row : rows) {
            perUser.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(row);
        }
        LocalDateTime now = BingoTime.now();
        List<RebateRecord> records = new ArrayList<>();
        perUser.forEach((userId, userRows) -> {
            int line = UserLine.orDefault(userRows.stream().max(LATEST).map(ValidBetDaily::getUserLine).orElse(null));
            Programme programme = programmeFor(programmes, line);
            if (programme == null) {
                return;
            }
            Map<String, Totals> perCurrency = new LinkedHashMap<>();
            for (ValidBetDaily row : userRows) {
                perCurrency.computeIfAbsent(row.getCurrency(), k -> new Totals())
                        .add(row.getValidBet(), programme.terms().rebate(row.getProviderCode(), row.getValidBet()));
            }
            perCurrency.forEach((currency, totals) -> {
                // never round a payout up
                BigDecimal amount = totals.rebate.setScale(Money.SCALE, RoundingMode.DOWN);
                if (amount.signum() > 0) {
                    records.add(newRecord(statDate, userId, line, currency, totals.validBet, amount, programme, now));
                }
            });
        });
        return records;
    }

    private RebateRecord newRecord(LocalDate statDate, long userId, int line, String currency, BigDecimal validBet,
                                   BigDecimal amount, Programme programme, LocalDateTime now) {
        RebateRecord record = new RebateRecord();
        record.setId(idGenerator.nextId());
        record.setStatDate(statDate);
        record.setUserId(userId);
        record.setUserLine(line);
        record.setCurrency(currency);
        record.setValidBet(validBet);
        record.setAmount(amount);
        record.setPromotionId(programme.promotionId());
        record.setTurnoverMultiplier(programme.terms().turnover().multiplier());
        record.setTurnoverScope(programme.terms().turnover().scope());
        record.setTurnoverScopeValue(programme.terms().turnover().scopeValue());
        record.setStatus(RebateStatus.PENDING);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private static Programme programmeFor(List<Programme> programmes, int line) {
        for (Programme programme : programmes) {
            if (programme.lines().contains(line)) {
                return programme;
            }
        }
        return null;
    }

    /** One REBATE promotion with its parsed terms. */
    private record Programme(long promotionId, Set<Integer> lines, RebateTerms terms) {
    }

    private static final class Totals {
        private BigDecimal validBet = BigDecimal.ZERO;
        private BigDecimal rebate = BigDecimal.ZERO;

        void add(BigDecimal validBetDelta, BigDecimal rebateDelta) {
            validBet = validBet.add(validBetDelta);
            rebate = rebate.add(rebateDelta);
        }
    }
}

package com.bingo789.reconcile;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.ProviderBetEvent;
import com.bingo789.common.mq.event.WalletTxnEvent;
import com.bingo789.reconcile.ingest.AggregationService;
import com.bingo789.reconcile.mapper.GgrDailyMapper;
import com.bingo789.reconcile.mapper.PlatformHourlyMapper;
import com.bingo789.reconcile.mapper.ProviderHourlyMapper;
import com.bingo789.reconcile.model.GgrRow;
import com.bingo789.reconcile.model.ProviderTotals;
import com.bingo789.reconcile.recon.HourlyReconJob;
import com.bingo789.reconcile.report.GgrService;
import com.bingo789.reconcile.report.ProviderSettlementJob;
import com.xxl.job.core.context.XxlJobContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User lines in reconciliation: aggregates are kept per line, while every platform-vs-provider comparison, the
 * provider settlement and the RTP monitor sum all lines. Requires Docker: {@code mvn verify -Pit}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "bingo.mybatis.worker-id=1",
        "bingo.reconcile.revenue-share[DEMO]=0.10"
})
class ReconcileLineIT {

    private static final String DEMO = "DEMO";
    /** Second provider with a real difference: proves the hourly comparison ran for the hour. */
    private static final String CONTROL = "DEMO2";
    private static final String GAME = "fortune-tiger";
    private static final String CUR = "PHP";
    private static final String PLATFORM_GROUP = "it-platform";
    private static final String PROVIDER_GROUP = "it-provider";
    /** 2026-09-30 13:00 UTC+8. */
    private static final LocalDateTime HOUR = LocalDateTime.of(2026, 9, 30, 13, 0);

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("bingo_reconcile")
            .withCommand("--default-time-zone=+08:00")
            // same session settings as the application's JDBC URL (%2B = '+')
            .withUrlParam("connectionTimeZone", "%2B08:00")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../../deploy/sql/09_reconcile.sql"),
                    "/docker-entrypoint-initdb.d/09_reconcile.sql");

    @Autowired
    AggregationService aggregation;

    @Autowired
    PlatformHourlyMapper platformMapper;

    @Autowired
    ProviderHourlyMapper providerMapper;

    @Autowired
    GgrDailyMapper ggrMapper;

    @Autowired
    GgrService ggrService;

    @Autowired
    HourlyReconJob hourlyJob;

    @Autowired
    ProviderSettlementJob settlementJob;

    @Autowired
    JdbcTemplate jdbc;

    private long offset;

    @BeforeAll
    static void platformTimeZone() {
        BingoTime.applyJvmDefault();
    }

    @BeforeEach
    void emptyTables() {
        for (String table : List.of("kafka_offset", "recon_platform_hourly", "recon_provider_hourly", "recon_diff",
                "ggr_daily", "provider_settlement")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }

    @Test
    void hourlyAggregatesArePerLineButComparedWithTheProviderAcrossLines() {
        aggregation.applyLedgerBatch(PLATFORM_GROUP, Topics.WALLET_TXN, records(Topics.WALLET_TXN,
                txn(1, 1, 1, DEMO, "BET", "100", HOUR.plusMinutes(5)),
                txn(2, 1, 1, DEMO, "PAYOUT", "30", HOUR.plusMinutes(6)),
                txn(3, 2, 2, DEMO, "BET", "50", HOUR.plusMinutes(10)),
                txn(4, 2, 2, DEMO, "PAYOUT", "20", HOUR.plusMinutes(11)),
                txn(5, 3, 1, CONTROL, "BET", "10", HOUR.plusMinutes(20))));
        // player 2 was moved to line 3 before bet-record pulled the provider's record
        aggregation.applyProviderBatch(PROVIDER_GROUP, Topics.PROVIDER_BET, records(Topics.PROVIDER_BET,
                providerBet("b-1", 1, 1, DEMO, "100", "30", HOUR.plusMinutes(5)),
                providerBet("b-2", 2, 3, DEMO, "50", "20", HOUR.plusMinutes(10)),
                providerBet("c-1", 3, 1, CONTROL, "7", "0", HOUR.plusMinutes(20))));

        assertThat(rows("SELECT user_line, bet_amount, payout_amount, bet_count FROM recon_platform_hourly"
                + " WHERE stat_hour = ? AND provider_code = ? ORDER BY user_line", HOUR, DEMO))
                .containsExactly("1:100:30:1", "2:50:20:1");
        assertThat(rows("SELECT user_line, bet_amount, payout_amount, record_count FROM recon_provider_hourly"
                + " WHERE stat_hour = ? AND provider_code = ? ORDER BY user_line", HOUR, DEMO))
                .containsExactly("1:100:30:1", "3:50:20:1");

        // comparison inputs: one total per provider and currency, whatever the lines
        assertThat(totals(platformMapper.sumByProviderCurrency(HOUR), DEMO)).isEqualTo("150:50");
        assertThat(totals(providerMapper.sumByProviderCurrency(HOUR), DEMO)).isEqualTo("150:50");

        assertThat(runJob("2026-09-30T13:00", hourlyJob::execute)).isEqualTo(XxlJobContext.HANDLE_CODE_SUCCESS);
        // per line the two sides disagree; summed over the lines DEMO matches, the control provider's gap is found
        assertThat(rows("SELECT provider_code, metric, diff FROM recon_diff ORDER BY id")).containsExactly("DEMO2:NET_BET:3");
    }

    @Test
    void dailyGgrIsPerLineButReportSettlementAndRtpInputsSumAllLines() {
        LocalDate day = HOUR.toLocalDate();
        aggregation.applyLedgerBatch(PLATFORM_GROUP, Topics.WALLET_TXN, records(Topics.WALLET_TXN,
                txn(1, 1, 1, DEMO, "BET", "100", HOUR.plusMinutes(5)),
                txn(2, 1, 1, DEMO, "PAYOUT", "30", HOUR.plusMinutes(6)),
                txn(3, 2, 2, DEMO, "BET", "50", HOUR.plusHours(1).plusMinutes(10)),
                txn(4, 2, 2, DEMO, "PAYOUT", "20", HOUR.plusHours(1).plusMinutes(11))));

        assertThat(ggrService.rebuildDay(day)).isEqualTo(2);
        assertThat(rows("SELECT user_line, bet, payout, ggr, bet_count FROM ggr_daily WHERE stat_date = ? ORDER BY user_line", day))
                .containsExactly("1:100:30:70:1", "2:50:20:30:1");

        List<GgrRow> report = ggrMapper.sumByDayProviderCurrency(day, day, null);
        assertThat(report).hasSize(1);
        assertThat(report.getFirst().getGgr()).isEqualByComparingTo("100");

        List<GgrRow> settlementInput = ggrMapper.sumByProviderCurrency(day.withDayOfMonth(1), day.plusDays(1));
        assertThat(settlementInput).hasSize(1);
        assertThat(settlementInput.getFirst().getGgr()).isEqualByComparingTo("100");

        // neither line alone reaches the RTP volume thresholds, the game as a whole does
        List<GgrRow> rtpInput = ggrMapper.gameVolumes(day, day.plusDays(1), new BigDecimal("120"), 2);
        assertThat(rtpInput).hasSize(1);
        assertThat(rtpInput.getFirst().getBet()).isEqualByComparingTo("150");
        assertThat(rtpInput.getFirst().getPayout()).isEqualByComparingTo("50");
        assertThat(rtpInput.getFirst().getBetCount()).isEqualTo(2L);

        assertThat(runJob("2026-09", settlementJob::execute)).isEqualTo(XxlJobContext.HANDLE_CODE_SUCCESS);
        assertThat(rows("SELECT provider_code, currency, ggr, amount_due FROM provider_settlement WHERE period = ?", "2026-09"))
                .containsExactly("DEMO:PHP:100:10");
    }

    private List<ConsumerRecord<String, String>> records(String topic, Object... events) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (Object event : events) {
            records.add(new ConsumerRecord<>(topic, 0, offset++, "key", JsonUtils.toJson(event)));
        }
        return records;
    }

    private static WalletTxnEvent txn(long id, long userId, int line, String provider, String type, String amount,
                                      LocalDateTime at) {
        return new WalletTxnEvent(id, userId, line, CUR, type, "BET".equals(type) ? -1 : 1, new BigDecimal(amount),
                BigDecimal.ZERO, provider, "ptx-" + id, "r-" + userId, GAME, null, false, 1, at.toInstant(BingoTime.ZONE));
    }

    private static ProviderBetEvent providerBet(String betId, long userId, int line, String provider, String bet,
                                                String payout, LocalDateTime at) {
        return new ProviderBetEvent(provider, betId, "r-" + userId, userId, line, CUR, GAME, new BigDecimal(bet),
                new BigDecimal(payout), "SETTLED", at.toInstant(BingoTime.ZONE), at.plusSeconds(1).toInstant(BingoTime.ZONE));
    }

    /** Runs an XXL job handler with a job parameter; returns its handle code. */
    private static int runJob(String param, Runnable handler) {
        XxlJobContext.setXxlJobContext(new XxlJobContext(1L, param, 1L, System.currentTimeMillis(), null, 0, 1));
        try {
            handler.run();
            return XxlJobContext.getXxlJobContext().getHandleCode();
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    /** Each row as its column values joined by ':' (decimals without trailing zeros). */
    private List<String> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream()
                .map(row -> row.values().stream().map(ReconcileLineIT::plain).collect(Collectors.joining(":")))
                .toList();
    }

    private static String totals(List<ProviderTotals> totals, String providerCode) {
        return totals.stream()
                .filter(t -> t.getProviderCode().equals(providerCode))
                .map(t -> plain(t.getBetAmount()) + ":" + plain(t.getPayoutAmount()))
                .collect(Collectors.joining(","));
    }

    private static String plain(Object value) {
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : String.valueOf(value);
    }
}

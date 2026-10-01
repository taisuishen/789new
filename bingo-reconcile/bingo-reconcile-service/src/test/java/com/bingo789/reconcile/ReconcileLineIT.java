package com.bingo789.reconcile;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.game.api.dto.RoundResolutionView;
import com.bingo789.reconcile.mapper.GgrDailyMapper;
import com.bingo789.reconcile.model.GgrRow;
import com.bingo789.reconcile.recon.DailyDetailReconJob;
import com.bingo789.reconcile.recon.HourlyReconJob;
import com.bingo789.reconcile.report.GgrService;
import com.bingo789.reconcile.report.ProviderSettlementJob;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Reconciliation on the StarRocks figures (ReconcileDw), here played by MySQL tables with the same columns
 * (dw_test_tables.sql). GGR is kept per user line, while every platform-vs-provider comparison, the provider
 * settlement and the RTP monitor sum all lines. Requires Docker: {@code mvn verify -Pit}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "bingo.mybatis.worker-id=1",
        "bingo.reconcile.revenue-share[DEMO]=0.10"
})
class ReconcileLineIT {

    private static final String DEMO = "DEMO";
    /** Second provider with a real difference: proves the hourly comparison ran for the hour. */
    private static final String CONTROL = "DEMO2";
    private static final String GAME = "fortune-tiger";
    private static final String CUR = "PHP";
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
                    "/docker-entrypoint-initdb.d/09_reconcile.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("dw_test_tables.sql"),
                    "/docker-entrypoint-initdb.d/10_dw_test_tables.sql");

    /** The "StarRocks" of this test is the same MySQL database. */
    @DynamicPropertySource
    static void dw(DynamicPropertyRegistry registry) {
        registry.add("bingo.reconcile.dw.url", mysql::getJdbcUrl);
        registry.add("bingo.reconcile.dw.username", mysql::getUsername);
        registry.add("bingo.reconcile.dw.password", mysql::getPassword);
    }

    @MockitoBean
    ProviderQueryClient providerQueryClient;

    @Autowired
    GgrDailyMapper ggrMapper;

    @Autowired
    GgrService ggrService;

    @Autowired
    HourlyReconJob hourlyJob;

    @Autowired
    DailyDetailReconJob detailJob;

    @Autowired
    ProviderSettlementJob settlementJob;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void platformTimeZone() {
        BingoTime.applyJvmDefault();
    }

    @BeforeEach
    void emptyTables() {
        for (String table : List.of("wallet_txn", "game_round", "provider_bet", "recon_diff", "ggr_daily",
                "provider_settlement")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }

    @Test
    void theHourlyComparisonSumsEveryLineOnBothSides() {
        txn(1, 1, 1, DEMO, "BET", "100", HOUR.plusMinutes(5));
        txn(2, 1, 1, DEMO, "PAYOUT", "30", HOUR.plusMinutes(6));
        txn(3, 2, 2, DEMO, "BET", "50", HOUR.plusMinutes(10));
        txn(4, 2, 2, DEMO, "PAYOUT", "20", HOUR.plusMinutes(11));
        txn(5, 3, 1, CONTROL, "BET", "10", HOUR.plusMinutes(20));
        // a rolled-back bet and a tombstone count nothing
        txn(6, 4, 1, DEMO, "BET", "40", HOUR.plusMinutes(30));
        txn(7, 4, 1, DEMO, "ROLLBACK", "40", HOUR.plusMinutes(31));
        jdbc.update("UPDATE wallet_txn SET status = 3 WHERE id = 5");
        txn(8, 3, 1, CONTROL, "BET", "10", HOUR.plusMinutes(21));
        // player 2 was moved to line 3 before bet-record pulled the provider's record
        providerBet("b-1", "r-1", 1, 1, DEMO, "100", "30", HOUR.plusMinutes(5));
        providerBet("b-2", "r-2", 2, 3, DEMO, "50", "20", HOUR.plusMinutes(10));
        providerBet("c-1", "r-3", 3, 1, CONTROL, "7", "0", HOUR.plusMinutes(20));

        assertThat(runJob("2026-09-30T13:00", hourlyJob::execute)).isEqualTo(XxlJobContext.HANDLE_CODE_SUCCESS);
        // DEMO matches across lines; the control provider's gap is found
        assertThat(rows("SELECT provider_code, metric, diff FROM recon_diff ORDER BY id")).containsExactly("DEMO2:NET_BET:3");
    }

    @Test
    void dailyGgrIsPerLineButReportSettlementAndRtpInputsSumAllLines() {
        LocalDate day = HOUR.toLocalDate();
        txn(1, 1, 1, DEMO, "BET", "100", HOUR.plusMinutes(5));
        txn(2, 1, 1, DEMO, "PAYOUT", "30", HOUR.plusMinutes(6));
        txn(3, 2, 2, DEMO, "BET", "50", HOUR.plusHours(1).plusMinutes(10));
        txn(4, 2, 2, DEMO, "PAYOUT", "20", HOUR.plusHours(1).plusMinutes(11));

        assertThat(ggrService.rebuildDay(day)).isEqualTo(2);
        // a re-run replaces the day
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

    @Test
    void dailyDetailMatchingTicketsEveryPattern() {
        when(providerQueryClient.resolveRound(any())).thenReturn(
                new RoundResolutionView(RoundResolutionView.SETTLED, "applied missing payout"));
        round("r-ok", 1, "10", "5");
        providerBet("b-ok", "r-ok", 1, 1, DEMO, "10", "5", HOUR);
        // the provider paid 50, our ledger credited nothing
        round("r-win", 2, "10", "0");
        providerBet("b-win", "r-win", 2, 1, DEMO, "10", "50", HOUR);
        round("r-lonely", 3, "20", "0");
        providerBet("b-unknown", "r-unknown", 4, 1, DEMO, "30", "0", HOUR);

        assertThat(runJob("2026-09-30", detailJob::execute)).isEqualTo(XxlJobContext.HANDLE_CODE_SUCCESS);
        assertThat(rows("SELECT metric, platform_value, provider_value, status FROM recon_diff ORDER BY metric"))
                .containsExactly("MISSING_ON_PLATFORM:0:30:OPEN", "MISSING_ON_PROVIDER:20:0:OPEN",
                        "PROVIDER_WIN_NOT_CREDITED:0:50:AUTO_FIXED");
    }

    private void txn(long id, long userId, int line, String provider, String type, String amount, LocalDateTime at) {
        jdbc.update("""
                        INSERT INTO wallet_txn (id, created_at, user_id, user_line, currency, txn_type, direction, amount,
                                                provider_code, round_id, game_code, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)""",
                id, at, userId, line, CUR, type, "BET".equals(type) ? -1 : 1, new BigDecimal(amount), provider,
                "r-" + userId, GAME);
    }

    private void providerBet(String betId, String roundId, long userId, int line, String provider, String bet,
                             String payout, LocalDateTime at) {
        jdbc.update("""
                        INSERT INTO provider_bet (provider_code, provider_bet_id, round_id, user_id, user_line, currency,
                                                  bet_amount, payout_amount, status, bet_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?)""",
                provider, betId, roundId, userId, line, CUR, new BigDecimal(bet), new BigDecimal(payout), at);
    }

    private void round(String roundId, long userId, String bet, String payout) {
        jdbc.update("""
                        INSERT INTO game_round (provider_code, round_id, user_id, currency, bet_amount, payout_amount,
                                                status, bet_at, settled_at, revision)
                        VALUES (?, ?, ?, ?, ?, ?, 'SETTLED', ?, ?, 1)""",
                DEMO, roundId, userId, CUR, new BigDecimal(bet), new BigDecimal(payout), HOUR, HOUR.plusMinutes(1));
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

    private static String plain(Object value) {
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : String.valueOf(value);
    }
}

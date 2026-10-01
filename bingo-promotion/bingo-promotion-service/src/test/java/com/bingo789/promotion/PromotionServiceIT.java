package com.bingo789.promotion;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.promotion.common.PromotionErrorCode;
import com.bingo789.promotion.domain.PromotionStatus;
import com.bingo789.promotion.service.ActivityService;
import com.bingo789.promotion.service.BonusGrantService;
import com.bingo789.promotion.service.PromotionAdminService;
import com.bingo789.promotion.service.PromotionCatalog;
import com.bingo789.promotion.service.RebateService;
import com.bingo789.promotion.service.ValidBetService;
import com.bingo789.promotion.terms.RebateTerms;
import com.bingo789.promotion.web.dto.ActivityView;
import com.bingo789.promotion.web.dto.PromotionAdminView;
import com.bingo789.promotion.web.dto.PromotionCreateRequest;
import com.bingo789.promotion.web.dto.PromotionStatusRequest;
import com.bingo789.promotion.web.dto.PromotionUpdateRequest;
import com.bingo789.user.api.UserClient;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.WalletResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.bingo789.promotion.PromotionFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Promotions against the real schema (deploy/sql/08_promotion.sql): JSON columns, line filtering, optimistic
 * versions, and the programmes applied per line. Requires Docker: {@code mvn verify -Pit}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "bingo.outbox.relay-enabled=false",
        "bingo.mybatis.force-master=false",
        "bingo.mybatis.worker-id=1",
        "bingo.promotion.first-deposit.enabled=true"
})
class PromotionServiceIT {

    private static final String OPERATOR = "op-it";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final Instant FROM = LocalDateTime.of(2026, 1, 1, 0, 0).toInstant(BingoTime.ZONE);
    private static final Instant TO = LocalDateTime.of(2100, 1, 1, 0, 0).toInstant(BingoTime.ZONE);
    private static final String DISPLAYED_FIRST_DEPOSIT = """
            {"percent":100,"maxAmount":1000,"turnover":{"multiplier":10,"scope":"GAME_TYPE","scopeValue":"SLOT"},
             "display":{"title":"Welcome bonus"}}""";

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("bingo_promotion")
            .withCommand("--default-time-zone=+08:00")
            // same session settings as the application's JDBC URL (%2B = '+')
            .withUrlParam("connectionTimeZone", "%2B08:00")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../../deploy/sql/08_promotion.sql"),
                    "/docker-entrypoint-initdb.d/08_promotion.sql");

    @MockitoBean
    UserClient userClient;

    @MockitoBean
    WalletClient walletClient;

    @Autowired
    PromotionAdminService adminService;

    @Autowired
    PromotionCatalog catalog;

    @Autowired
    ActivityService activityService;

    @Autowired
    BonusGrantService bonusGrantService;

    @Autowired
    ValidBetService validBetService;

    @Autowired
    RebateService rebateService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void platformTimeZone() {
        BingoTime.applyJvmDefault();
    }

    @BeforeEach
    void cleanUp() {
        for (String table : List.of("valid_bet_daily", "promotion_round_applied", "rebate_record", "bonus_grant", "mq_outbox")) {
            jdbc.update("DELETE FROM " + table);
        }
        // keep the seeded example programme, back in its seeded state
        jdbc.update("DELETE FROM promotion WHERE id <> 1");
        jdbc.update("UPDATE promotion SET status = 'DRAFT', version = 1 WHERE id = 1");
        when(walletClient.platformTxn(any())).thenReturn(WalletResult.success(1L, "PHP", BigDecimal.TEN, BigDecimal.TEN));
    }

    @Test
    void theSeededRebateProgrammeIsADraftForLine1WithValidTerms() {
        Map<String, Object> seed = jdbc.queryForMap("SELECT promo_type, status, user_lines, config_json FROM promotion WHERE id = 1");

        assertThat(seed.get("promo_type")).isEqualTo("REBATE");
        assertThat(seed.get("status")).isEqualTo("DRAFT");
        assertThat(json(String.valueOf(seed.get("user_lines")))).isEqualTo(json("[1]"));
        RebateTerms terms = RebateTerms.parse(json(String.valueOf(seed.get("config_json"))));
        assertThat(terms.defaultRate()).isEqualByComparingTo("0.005");
        assertThat(terms.defaultDailyCap()).isNull();
        assertThat(terms.turnover().multiplier()).isEqualByComparingTo("1");

        // a DRAFT programme pays nothing
        validBetService.applySettledRounds(List.of(round("DEMO", "r1", 701, 1, "1000", 10)));
        assertThat(rebateService.computeRebates(DAY)).isZero();
    }

    @Test
    void playersSeeOnlyOnlineLiveActivitiesOfTheirLineAndOnlyTheirDisplay() {
        PromotionAdminView line1 = online(create("Rebate line 1", "REBATE", List.of(1), FROM, TO, 0, PromotionFixtures.REBATE_CONFIG));
        PromotionAdminView line2 = online(create("Welcome line 2", "FIRST_DEPOSIT", List.of(2), FROM, TO, 0, DISPLAYED_FIRST_DEPOSIT));
        PromotionAdminView both = online(create("Both lines", "FIRST_DEPOSIT", List.of(1, 2), FROM, TO, 9, DISPLAYED_FIRST_DEPOSIT));
        create("Draft line 2", "FIRST_DEPOSIT", List.of(2), FROM, TO, 0, DISPLAYED_FIRST_DEPOSIT);
        PromotionAdminView offline = online(create("Offline line 2", "FIRST_DEPOSIT", List.of(2), FROM, TO, 0, DISPLAYED_FIRST_DEPOSIT));
        adminService.changeStatus(Long.parseLong(offline.id()), new PromotionStatusRequest("OFFLINE", offline.version()), OPERATOR);
        Instant tomorrow = Instant.now().plusSeconds(86_400);
        online(create("Future line 2", "FIRST_DEPOSIT", List.of(2), tomorrow, TO, 0, DISPLAYED_FIRST_DEPOSIT));
        catalog.refresh();
        when(userClient.playerStatus(501L)).thenReturn(PromotionFixtures.player(501L, 2));
        when(userClient.playerStatus(502L)).thenReturn(PromotionFixtures.player(502L, 1));

        List<ActivityView> line2Activities = activityService.listFor(501L);
        List<ActivityView> line1Activities = activityService.listFor(502L);

        assertThat(line2Activities).extracting(ActivityView::id).containsExactly(both.id(), line2.id());
        assertThat(line1Activities).extracting(ActivityView::id).containsExactly(both.id(), line1.id());
        assertThat(line2Activities.get(1).display().get("title").stringValue()).isEqualTo("Welcome bonus");
        assertThat(JsonUtils.toJson(line2Activities)).doesNotContain("maxAmount", "turnover", "SLOT");
    }

    @Test
    void theAdminListShowsPromotionsWhoseLinesIntersectTheViewersLines() {
        PromotionAdminView line2 = create("Line 2", "REBATE", List.of(2), FROM, TO, 0, PromotionFixtures.REBATE_CONFIG);
        PromotionAdminView lines23 = create("Lines 2 and 3", "REBATE", List.of(2, 3), FROM, TO, 0, PromotionFixtures.REBATE_CONFIG);
        PromotionAdminView line4 = create("Line 4", "FIRST_DEPOSIT", List.of(4), FROM, TO, 0, PromotionFixtures.FIRST_DEPOSIT_CONFIG);

        assertThat(ids(LineScope.parse("2"), null)).containsExactlyInAnyOrder(line2.id(), lines23.id());
        assertThat(ids(LineScope.parse("1,3"), null)).containsExactlyInAnyOrder("1", lines23.id());
        assertThat(ids(LineScope.parse("*"), null)).containsExactlyInAnyOrder("1", line2.id(), lines23.id(), line4.id());
        assertThat(ids(LineScope.parse("*"), "FIRST_DEPOSIT")).containsExactly(line4.id());
        assertThat(adminService.list(LineScope.parse("2,3"), "DRAFT", null, 1, 20).total()).isEqualTo(2);
    }

    @Test
    void aStaleVersionIsRefused() {
        PromotionAdminView created = create("Rebate", "REBATE", List.of(1), FROM, TO, 0, PromotionFixtures.REBATE_CONFIG);
        long id = Long.parseLong(created.id());

        PromotionAdminView updated = adminService.update(id, new PromotionUpdateRequest("Rebate v2", List.of(1, 2), FROM, TO, 3,
                json(PromotionFixtures.REBATE_CONFIG), created.version()), "op-2");

        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(updated.name()).isEqualTo("Rebate v2");
        assertThat(updated.userLines()).containsExactly(1, 2);
        assertThat(updated.createdBy()).isEqualTo(OPERATOR);
        assertThat(updated.updatedBy()).isEqualTo("op-2");
        // a second editor still holding the first version
        assertThatThrownBy(() -> adminService.update(id, new PromotionUpdateRequest("Rebate v3", List.of(1), FROM, TO, 0,
                json(PromotionFixtures.REBATE_CONFIG), created.version()), "op-3"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PromotionErrorCode.VERSION_CONFLICT));
        assertThatThrownBy(() -> adminService.changeStatus(id, new PromotionStatusRequest("ONLINE", created.version()), "op-3"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PromotionErrorCode.VERSION_CONFLICT));

        PromotionAdminView live = adminService.changeStatus(id, new PromotionStatusRequest("ONLINE", updated.version()), "op-3");
        assertThat(live.status()).isEqualTo(PromotionStatus.ONLINE);
        assertThat(jdbc.queryForObject("SELECT version FROM promotion WHERE id = ?", Integer.class, id))
                .isEqualTo(updated.version() + 1);
    }

    @Test
    void aFirstDepositUsesThePromotionOfTheDepositsLine() {
        // higher sort, but line 1 only
        online(create("Welcome line 1", "FIRST_DEPOSIT", List.of(1), FROM, TO, 9, """
                {"percent":50,"maxAmount":100,"turnover":{"multiplier":5}}"""));
        PromotionAdminView line2 = online(create("Welcome line 2", "FIRST_DEPOSIT", List.of(2), FROM, TO, 0, DISPLAYED_FIRST_DEPOSIT));
        when(userClient.playerStatus(601L)).thenReturn(PromotionFixtures.player(601L, 2));
        when(userClient.playerStatus(602L)).thenReturn(PromotionFixtures.player(602L, 3));

        bonusGrantService.onFirstDeposit(deposit("D601", 601L, 2, "300"));
        bonusGrantService.onFirstDeposit(deposit("D602", 602L, 3, "300"));

        Map<String, Object> grant = jdbc.queryForMap(
                "SELECT user_line, promotion_id, amount, status, turnover_multiplier, turnover_scope, turnover_scope_value"
                        + " FROM bonus_grant WHERE biz_no = 'FDB-D601'");
        assertThat(((Number) grant.get("user_line")).intValue()).isEqualTo(2);
        assertThat(String.valueOf(grant.get("promotion_id"))).isEqualTo(line2.id());
        assertThat((BigDecimal) grant.get("amount")).isEqualByComparingTo("300");
        assertThat(grant.get("status")).isEqualTo("PAID");
        assertThat((BigDecimal) grant.get("turnover_multiplier")).isEqualByComparingTo("10");
        assertThat(grant.get("turnover_scope")).isEqualTo("GAME_TYPE");
        assertThat(grant.get("turnover_scope_value")).isEqualTo("SLOT");
        String payload = jdbc.queryForObject("SELECT payload FROM mq_outbox WHERE msg_key = 'FDB-D601'", String.class);
        assertThat(json(payload).get("userLine").intValue()).isEqualTo(2);
        assertThat(json(payload).get("turnoverScope").stringValue()).isEqualTo("GAME_TYPE");
        assertThat(json(payload).get("turnoverScopeValue").stringValue()).isEqualTo("SLOT");
        // no FIRST_DEPOSIT promotion targets line 3
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bonus_grant WHERE biz_no = 'FDB-D602'", Integer.class)).isZero();
    }

    @Test
    void rebatesUseTheProgrammeOfEachUserDaysLine() throws InterruptedException {
        // the seeded programme covers line 1: 0.5% on every provider, no cap, wagering 1x
        adminService.changeStatus(1L, new PromotionStatusRequest("ONLINE", 1), OPERATOR);
        PromotionAdminView line2 = online(create("Rebate line 2", "REBATE", List.of(2), FROM, TO, 0, """
                {"defaultRate":0.01,"providers":{"DEMO":{"rate":0.008,"dailyCap":100}},
                 "turnover":{"multiplier":2,"scope":"GAME","scopeValue":"DEMO:bingo-90"}}"""));
        validBetService.applySettledRounds(List.of(
                round("DEMO", "r1", 701, 1, "1000", 10),
                round("DEMO", "r2", 702, 2, "20000", 10),
                round("JILI", "r3", 702, 2, "1000", 11),
                round("DEMO", "r4", 703, 1, "1000", 10),
                round("JILI", "r5", 704, 3, "1000", 10)));
        // player 703 moves to line 2 and plays again later that day, in a later batch
        Thread.sleep(20);
        validBetService.applySettledRounds(List.of(round("JILI", "r6", 703, 2, "1000", 20)));
        // a redelivered batch is not counted twice
        validBetService.applySettledRounds(List.of(round("JILI", "r6", 703, 2, "1000", 20)));

        assertThat(lineOf(703, "DEMO")).isEqualTo(1);
        assertThat(lineOf(703, "JILI")).isEqualTo(2);
        assertThat(rebateService.computeRebates(DAY)).isEqualTo(3);
        assertThat(rebateService.computeRebates(DAY)).isZero();

        assertRebate(701, 1, "1", "5", "1");
        // DEMO 160 capped at 100 + JILI 10 (line-2 default rate)
        assertRebate(702, 2, line2.id(), "110", "2");
        // at the end of the day 703 is on line 2: DEMO 8 + JILI 10
        assertRebate(703, 2, line2.id(), "18", "2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rebate_record WHERE user_id = 704", Integer.class)).isZero();

        assertThat(rebateService.payPending().paid()).isEqualTo(3);
        String payload = jdbc.queryForObject(
                "SELECT o.payload FROM mq_outbox o JOIN rebate_record r ON o.msg_key = CONCAT('REBATE-', r.id) WHERE r.user_id = 703",
                String.class);
        assertThat(json(payload).get("userLine").intValue()).isEqualTo(2);
        assertThat(json(payload).get("turnoverScope").stringValue()).isEqualTo("GAME");
        assertThat(json(payload).get("turnoverScopeValue").stringValue()).isEqualTo("DEMO:bingo-90");
    }

    private PromotionAdminView create(String name, String type, List<Integer> lines, Instant start, Instant end, int sort,
                                      String config) {
        return adminService.create(new PromotionCreateRequest(name, type, lines, start, end, sort, json(config)), OPERATOR);
    }

    private PromotionAdminView online(PromotionAdminView draft) {
        return adminService.changeStatus(Long.parseLong(draft.id()), new PromotionStatusRequest("ONLINE", draft.version()), OPERATOR);
    }

    private List<String> ids(LineScope scope, String type) {
        return adminService.list(scope, null, type, 1, 100).records().stream().map(PromotionAdminView::id).toList();
    }

    private static RoundSettledEvent round(String provider, String roundId, long userId, int line, String validBet, int hour) {
        Instant settledAt = DAY.atTime(hour, 0).toInstant(BingoTime.ZONE);
        BigDecimal amount = new BigDecimal(validBet);
        return new RoundSettledEvent(provider, roundId, userId, line, "PHP", "bingo-90", "BINGO", "Bingo 90", amount,
                BigDecimal.ZERO, amount, null, "SETTLED", settledAt.minusSeconds(30), settledAt, 1);
    }

    private static DepositSucceededEvent deposit(String orderNo, long userId, int line, String amount) {
        return new DepositSucceededEvent(orderNo, userId, line, "PHP", new BigDecimal(amount), "GCASH", true, Instant.now());
    }

    private int lineOf(long userId, String provider) {
        return jdbc.queryForObject("SELECT user_line FROM valid_bet_daily WHERE stat_date = ? AND user_id = ? AND provider_code = ?",
                Integer.class, DAY, userId, provider);
    }

    private void assertRebate(long userId, int line, String promotionId, String amount, String turnoverMultiplier) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_line, promotion_id, amount, turnover_multiplier FROM rebate_record WHERE stat_date = ? AND user_id = ?",
                DAY, userId);
        assertThat(((Number) row.get("user_line")).intValue()).isEqualTo(line);
        assertThat(String.valueOf(row.get("promotion_id"))).isEqualTo(promotionId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(amount);
        assertThat((BigDecimal) row.get("turnover_multiplier")).isEqualByComparingTo(turnoverMultiplier);
    }
}

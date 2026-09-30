package com.bingo789.turnover;

import com.bingo789.common.core.game.TurnoverScope;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.turnover.api.dto.BucketView;
import com.bingo789.turnover.api.dto.UpdateTurnoverSettingCommand;
import com.bingo789.turnover.domain.SourceType;
import com.bingo789.turnover.service.BucketService;
import com.bingo789.turnover.service.BucketService.NewBucket;
import com.bingo789.turnover.service.SettingService;
import com.bingo789.turnover.service.WagerService;
import org.junit.jupiter.api.BeforeAll;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Bucket creation, the waterfall against MySQL, exactly-once per round, and the balance-based clear. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "bingo.mybatis.force-master=false",
        "bingo.mybatis.worker-id=3"
})
class TurnoverServiceIT {

    private static final String CUR = "PHP";
    private static final AtomicLong USER_SEQ = new AtomicLong(9_000_000);

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("bingo_turnover")
            .withCommand("--default-time-zone=+08:00")
            .withUrlParam("connectionTimeZone", "%2B08:00")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../../deploy/sql/10_turnover.sql"),
                    "/docker-entrypoint-initdb.d/10_turnover.sql");

    @Autowired
    BucketService buckets;

    @Autowired
    WagerService wager;

    @Autowired
    SettingService settings;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void platformTimeZone() {
        BingoTime.applyJvmDefault();
    }

    @Test
    void roundFillsGameThenTypeThenAllExactlyOnceAndLowBalanceClears() {
        long user = USER_SEQ.incrementAndGet();
        Instant t0 = Instant.now().minus(Duration.ofHours(1));
        bucket(user, TurnoverScope.GAME, "PG:fortune-tiger", "G1", "20", t0);
        bucket(user, TurnoverScope.GAME_TYPE, "SLOT", "T1", "30", t0);
        bucket(user, TurnoverScope.ALL, null, "A1", "200", t0);
        // redelivered grant: same source, no second bucket
        bucket(user, TurnoverScope.ALL, null, "A1", "200", t0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turnover_bucket WHERE user_id = ?", Integer.class, user))
                .isEqualTo(3);

        RoundSettledEvent round = round(user, "r1", "PG", "fortune-tiger", "SLOT", "150", "500", t0.plusSeconds(600));
        wager.applySettledRounds(List.of(round, round));   // duplicate inside one batch
        wager.applySettledRounds(List.of(round));          // redelivery of the batch

        Map<String, BucketView> bySource = byScope(user);
        assertThat(bySource.get("G1").status()).isEqualTo("COMPLETED");
        assertThat(bySource.get("T1").status()).isEqualTo("COMPLETED");
        assertThat(bySource.get("A1").achieved()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForList("SELECT amount FROM turnover_record WHERE user_id = ? AND record_type = 'WAGER' ORDER BY seq",
                BigDecimal.class, user)).extracting(BigDecimal::toPlainString).containsExactly("20.0000", "30.0000", "100.0000");
        // one CREATE record per bucket (the redelivered grant added none)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turnover_record WHERE user_id = ? AND record_type = 'CREATE'",
                Integer.class, user)).isEqualTo(3);
        assertThat(buckets.outstanding(user, CUR).remaining()).isEqualByComparingTo("100");

        // a round of another game and type only reaches the ALL bucket; its low balance then clears what is left
        settings.update(new UpdateTurnoverSettingCommand(1, CUR, new BigDecimal("5"), null, BigDecimal.ONE, "ops-1"));
        wager.applySettledRounds(List.of(round(user, "r2", "JILI", "ocean-king", "FISHING", "1", "2", t0.plusSeconds(700))));

        BucketView all = byScope(user).get("A1");
        assertThat(all.achieved()).isEqualByComparingTo("101");
        assertThat(all.status()).isEqualTo("CLEARED");
        assertThat(all.closeReason()).isEqualTo("BALANCE_BELOW_THRESHOLD");
        assertThat(jdbc.queryForObject("SELECT amount FROM turnover_record WHERE bucket_id = ? AND record_type = 'CLEAR'",
                BigDecimal.class, all.id())).isEqualByComparingTo("99");
        assertThat(buckets.outstanding(user, CUR).remaining()).isEqualByComparingTo("0");
    }

    @Test
    void playersWithoutBucketsCostNoWrites() {
        long user = USER_SEQ.incrementAndGet();
        wager.applySettledRounds(List.of(round(user, "r1", "PG", "g", "SLOT", "50", "1", Instant.now())));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turnover_record WHERE user_id = ?", Integer.class, user)).isZero();
    }

    private void bucket(long user, TurnoverScope scope, String value, String sourceNo, String amount, Instant at) {
        buckets.create(new NewBucket(user, 1, CUR, scope, value, SourceType.BONUS, sourceNo, new BigDecimal(amount),
                BigDecimal.ONE, at, "SYSTEM"));
    }

    private Map<String, BucketView> byScope(long user) {
        return buckets.list(user, null, com.bingo789.common.core.line.LineScope.all()).stream()
                .collect(Collectors.toMap(BucketView::sourceNo, b -> b));
    }

    private static RoundSettledEvent round(long user, String roundId, String provider, String game, String type,
                                           String validBet, String balanceAfter, Instant betTime) {
        return new RoundSettledEvent(provider, roundId, user, 1, CUR, game, type, game, new BigDecimal(validBet),
                BigDecimal.ZERO, new BigDecimal(validBet), new BigDecimal(balanceAfter), "SETTLED", betTime,
                betTime.plusSeconds(30));
    }
}

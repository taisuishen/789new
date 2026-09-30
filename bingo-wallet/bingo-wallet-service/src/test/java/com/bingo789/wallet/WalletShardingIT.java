package com.bingo789.wallet;

import com.bingo789.common.mybatis.shard.ShardMigratingException;
import com.bingo789.common.mybatis.shard.ShardProperties;
import com.bingo789.common.mybatis.shard.ShardRouter;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.OpenWalletCommand;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import com.bingo789.wallet.mapper.WalletMapper;
import com.bingo789.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wallet on two shards (two schemas of one MySQL): each user's data lives only in the shard the router picks,
 * money commands work unchanged, a migrating shard refuses writes, and unscoped data access fails fast.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "bingo.mybatis.worker-id=2",
        "management.health.db.enabled=false"
})
class WalletShardingIT {

    private static final String CUR = "PHP";
    private static final String SHARD1_DB = "bingo_wallet_s1";
    private static final AtomicLong USER_SEQ = new AtomicLong(5_000_000);

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("bingo_wallet")
            .withCommand("--default-time-zone=+08:00");

    @Autowired
    WalletService wallet;

    @Autowired
    ShardRouter router;

    @Autowired
    WalletMapper walletMapper;

    @BeforeAll
    static void createSchemas() throws Exception {
        String ddl = Files.readString(Path.of("../../deploy/sql/02_wallet.sql"));
        // the DDL has semicolons inside column comments, so run it as one multi-statement script
        String base = mysql.getJdbcUrl();
        String multi = base + (base.contains("?") ? "&" : "?") + "allowMultiQueries=true";
        try (Connection root = DriverManager.getConnection(multi, "root", mysql.getPassword());
             Statement st = root.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SHARD1_DB);
            for (String schema : List.of("bingo_wallet", SHARD1_DB)) {
                st.execute(ddl.replace("USE bingo_wallet;", "USE " + schema + ";"));
            }
        }
    }

    @DynamicPropertySource
    static void shards(DynamicPropertyRegistry registry) {
        registry.add("bingo.shard.enabled", () -> "true");
        registry.add("bingo.shard.datasources.ds0.url", () -> url("bingo_wallet"));
        registry.add("bingo.shard.datasources.ds1.url", () -> url(SHARD1_DB));
        for (String ds : List.of("ds0", "ds1")) {
            registry.add("bingo.shard.datasources." + ds + ".username", () -> "root");
            registry.add("bingo.shard.datasources." + ds + ".password", mysql::getPassword);
        }
        registry.add("bingo.shard.routes[0].shards", () -> "0-511");
        registry.add("bingo.shard.routes[0].datasource", () -> "ds0");
        registry.add("bingo.shard.routes[1].shards", () -> "512-1023");
        registry.add("bingo.shard.routes[1].datasource", () -> "ds1");
    }

    @Test
    void everyUserLivesOnlyInItsOwnShard() throws Exception {
        int onShard1 = 0;
        try (Connection shard0 = DriverManager.getConnection(url("bingo_wallet"), "root", mysql.getPassword());
             Connection shard1 = DriverManager.getConnection(url(SHARD1_DB), "root", mysql.getPassword())) {
            connections.put("bingo_wallet", shard0);
            connections.put(SHARD1_DB, shard1);
            onShard1 = checkUsers();
        } finally {
            connections.clear();
        }
        // 40 random users over two halves of the shard space: both shards must be used
        assertThat(onShard1).isBetween(1, 39);
    }

    private final Map<String, Connection> connections = new LinkedHashMap<>();

    private int checkUsers() throws Exception {
        int onShard1 = 0;
        for (int i = 0; i < 40; i++) {
            long user = fundedUser("100");
            WalletResult bet = wallet.bet(new BetCommand(user, CUR, "DEMO", user + "-b", "r-" + user, "slot", new BigDecimal("10"), false));
            assertThat(bet.isSuccess()).isTrue();
            assertThat(wallet.bet(new BetCommand(user, CUR, "DEMO", user + "-b", "r-" + user, "slot", new BigDecimal("10"), false)).replay()).isTrue();

            String expected = router.dataSourceOf(user).equals("ds0") ? "bingo_wallet" : SHARD1_DB;
            String other = expected.equals("bingo_wallet") ? SHARD1_DB : "bingo_wallet";
            assertThat(count(expected, "wallet_txn", user)).isEqualTo(2);   // deposit + bet
            assertThat(count(other, "wallet_txn", user)).isZero();
            assertThat(count(expected, "wallet", user)).isEqualTo(1);
            assertThat(count(other, "wallet", user)).isZero();
            assertThat(wallet.balance(user, CUR).balance()).isEqualByComparingTo("90");
            if (expected.equals(SHARD1_DB)) {
                onShard1++;
            }
        }
        return onShard1;
    }

    @Test
    void migratingShardRefusesWritesUntilReleased() {
        long user = fundedUser("50");
        int shard = router.logicalShard(user);
        router.reload(properties(List.of(String.valueOf(shard))));
        try {
            assertThatThrownBy(() -> wallet.bet(new BetCommand(user, CUR, "DEMO", user + "-m", "r-m", "slot", BigDecimal.ONE, false)))
                    .isInstanceOf(ShardMigratingException.class);
            // reads keep working on the old shard during the move
            assertThat(wallet.balance(user, CUR).balance()).isEqualByComparingTo("50");
        } finally {
            router.reload(properties(List.of()));
        }
        assertThat(wallet.bet(new BetCommand(user, CUR, "DEMO", user + "-m", "r-m", "slot", BigDecimal.ONE, false)).isSuccess()).isTrue();
    }

    @Test
    void dataAccessOutsideAShardScopeFailsFast() {
        assertThatThrownBy(() -> walletMapper.find(1L, CUR)).hasStackTraceContaining("no shard selected");
    }

    private long fundedUser(String amount) {
        long user = USER_SEQ.incrementAndGet() * 7919;
        wallet.open(new OpenWalletCommand(user, CUR));
        wallet.platformTxn(new PlatformTxnCommand(user, CUR, TxnType.DEPOSIT, "PAYMENT", "D-" + UUID.randomUUID(),
                new BigDecimal(amount), "test funding"));
        return user;
    }

    private long count(String schema, String table, long user) throws Exception {
        try (PreparedStatement ps = connections.get(schema).prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?")) {
            ps.setLong(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String url(String schema) {
        return mysql.getJdbcUrl().replace("/bingo_wallet", "/" + schema);
    }

    private static ShardProperties properties(List<String> migrating) {
        Map<String, ShardProperties.DataSourceSpec> datasources = new LinkedHashMap<>();
        datasources.put("ds0", new ShardProperties.DataSourceSpec(url("bingo_wallet"), "root", mysql.getPassword(), null));
        datasources.put("ds1", new ShardProperties.DataSourceSpec(url(SHARD1_DB), "root", mysql.getPassword(), null));
        return new ShardProperties(true, 1024, datasources,
                List.of(new ShardProperties.Route("0-511", "ds0"), new ShardProperties.Route("512-1023", "ds1")),
                null, migrating, null);
    }
}

package com.bingo789.wallet;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.ReplicaRoute;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.OpenWalletCommand;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.RollbackCommand;
import com.bingo789.wallet.api.dto.UpdateUserLineCommand;
import com.bingo789.wallet.api.dto.UpdateWalletStatusCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import com.bingo789.wallet.api.enums.WalletResultCode;
import com.bingo789.wallet.api.enums.WalletStatus;
import com.bingo789.wallet.domain.WalletTxn;
import com.bingo789.wallet.mapper.WalletTxnMapper;
import com.bingo789.wallet.service.WalletService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario tests for the idempotency and out-of-order rules. Requires Docker: {@code mvn verify -Pit}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "bingo.mybatis.force-master=false",
        "bingo.mybatis.worker-id=1"
})
class WalletServiceIT {

    private static final String PROVIDER = "DEMO";
    private static final String CUR = "PHP";
    private static final AtomicLong USER_SEQ = new AtomicLong(1_000);

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("bingo_wallet")
            .withCommand("--default-time-zone=+08:00")
            // same session settings as the application's JDBC URL (%2B = '+')
            .withUrlParam("connectionTimeZone", "%2B08:00")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../../deploy/sql/02_wallet.sql"),
                    "/docker-entrypoint-initdb.d/02_wallet.sql");

    @Autowired
    WalletService wallet;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WalletTxnMapper txnMapper;

    @Autowired
    ShardTemplate shards;

    @BeforeAll
    static void platformTimeZone() {
        BingoTime.applyJvmDefault();
    }

    @Test
    void databaseAndCodeAgreeOnUtcPlus8() {
        long user = fundedUser("10");

        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+08:00");
        Long skewSeconds = jdbc.queryForObject(
                "SELECT ABS(TIMESTAMPDIFF(SECOND, MAX(created_at), NOW(3))) FROM wallet_txn WHERE user_id = ?", Long.class, user);
        assertThat(skewSeconds).isLessThanOrEqualTo(5L);
    }

    @Test
    void duplicateBetIsAppliedOnce() {
        long user = fundedUser("100");
        BetCommand bet = bet(user, "b-1", "r-1", "10");

        WalletResult first = wallet.bet(bet);
        WalletResult second = wallet.bet(bet);

        assertThat(first.code()).isEqualTo(WalletResultCode.SUCCESS);
        assertThat(second.replay()).isTrue();
        assertThat(second.txnId()).isEqualTo(first.txnId());
        assertThat(balance(user)).isEqualByComparingTo("90");
    }

    @Test
    void retriedBetStillSucceedsWhenBalanceIsNowTooLow() {
        long user = fundedUser("10");
        BetCommand bet = bet(user, "b-1", "r-1", "10");
        assertThat(wallet.bet(bet).isSuccess()).isTrue();

        WalletResult retry = wallet.bet(bet);

        assertThat(retry.isSuccess()).isTrue();
        assertThat(retry.replay()).isTrue();
        assertThat(balance(user)).isEqualByComparingTo("0");
    }

    @Test
    void rollbackBeforeBetRejectsTheLateBet() {
        long user = fundedUser("100");

        WalletResult rollback = wallet.rollback(rollback(user, "rb-1", "b-late"));
        WalletResult lateBet = wallet.bet(bet(user, "b-late", "r-1", "10"));

        assertThat(rollback.isSuccess()).isTrue();
        assertThat(lateBet.code()).isEqualTo(WalletResultCode.BET_CANCELLED);
        assertThat(balance(user)).isEqualByComparingTo("100");
    }

    @Test
    void betIsRefundedOnceEvenWithDistinctRollbackRequests() {
        long user = fundedUser("100");
        wallet.bet(bet(user, "b-1", "r-1", "30"));

        WalletResult first = wallet.rollback(rollback(user, "rb-1", "b-1"));
        WalletResult second = wallet.rollback(rollback(user, "rb-2", "b-1"));

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.replay()).isTrue();
        assertThat(balance(user)).isEqualByComparingTo("100");
    }

    @Test
    void payoutWithoutBetIsRejectedButStakelessPayoutIsNot() {
        long user = fundedUser("0");

        WalletResult orphan = wallet.payout(payout(user, "w-1", "r-none", "5", TxnType.PAYOUT));
        WalletResult freeSpin = wallet.payout(payout(user, "w-2", "r-free", "5", TxnType.FREE_PAYOUT));

        assertThat(orphan.code()).isEqualTo(WalletResultCode.BET_NOT_FOUND);
        assertThat(freeSpin.isSuccess()).isTrue();
        assertThat(balance(user)).isEqualByComparingTo("5");
    }

    @Test
    void zeroPayoutIsRecordedToCloseTheRound() {
        long user = fundedUser("10");
        wallet.bet(bet(user, "b-1", "r-1", "10"));

        WalletResult zero = wallet.payout(payout(user, "w-1", "r-1", "0", TxnType.PAYOUT));

        assertThat(zero.isSuccess()).isTrue();
        assertThat(zero.txnId()).isNotNull();
        assertThat(balance(user)).isEqualByComparingTo("0");
    }

    @Test
    void concurrentBetsNeverOverdraw() throws Exception {
        long user = fundedUser("100");
        List<Future<WalletResult>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                String id = "b-" + i;
                futures.add(executor.submit(() -> wallet.bet(bet(user, id, "r-" + id, "3"))));
            }
        }
        long succeeded = 0;
        for (Future<WalletResult> f : futures) {
            if (f.get().isSuccess()) {
                succeeded++;
            }
        }
        assertThat(succeeded).isEqualTo(33);
        assertThat(balance(user)).isEqualByComparingTo("1");
    }

    @Test
    void locksAreKeyedByReasonAndStrictestWins() {
        long user = fundedUser("100");
        wallet.updateStatus(new UpdateWalletStatusCommand(user, null, WalletStatus.BET_LOCKED, "SELF_EXCLUSION"));
        wallet.updateStatus(new UpdateWalletStatusCommand(user, null, WalletStatus.FROZEN, "AML_HOLD"));

        // releasing the RG lock must not lift the AML hold
        wallet.updateStatus(new UpdateWalletStatusCommand(user, null, WalletStatus.ACTIVE, "SELF_EXCLUSION"));
        assertThat(wallet.balance(user, CUR).status()).isEqualTo(WalletStatus.FROZEN);
        assertThat(wallet.bet(bet(user, "b-1", "r-1", "10")).code()).isEqualTo(WalletResultCode.WALLET_LOCKED);

        wallet.updateStatus(new UpdateWalletStatusCommand(user, null, WalletStatus.ACTIVE, "AML_HOLD"));
        assertThat(wallet.balance(user, CUR).status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.bet(bet(user, "b-2", "r-2", "10")).isSuccess()).isTrue();
    }

    @Test
    void selfExcludedPlayerCanStillWithdrawButNotBet() {
        long user = fundedUser("100");
        wallet.updateStatus(new UpdateWalletStatusCommand(user, null, WalletStatus.BET_LOCKED, "SELF_EXCLUSION"));

        WalletResult bet = wallet.bet(bet(user, "b-1", "r-1", "10"));
        WalletResult freeze = wallet.platformTxn(new PlatformTxnCommand(user, CUR, TxnType.WITHDRAW_FREEZE, "PAYMENT",
                "W-" + UUID.randomUUID(), new BigDecimal("40"), "withdrawal"));
        WalletResult payout = wallet.payout(payout(user, "w-1", "r-free", "5", TxnType.FREE_PAYOUT));

        assertThat(bet.code()).isEqualTo(WalletResultCode.WALLET_LOCKED);
        assertThat(freeze.isSuccess()).isTrue();
        assertThat(payout.isSuccess()).isTrue();
        assertThat(balance(user)).isEqualByComparingTo("65");
    }

    @Test
    void withdrawalIsEitherPaidOutOrReturnedNeverBoth() {
        long user = fundedUser("100");
        String orderNo = "W-" + UUID.randomUUID();
        wallet.platformTxn(withdraw(user, TxnType.WITHDRAW_FREEZE, orderNo, "40"));
        // a second freeze elsewhere keeps enough frozen funds around to prove the guard is not the frozen check
        wallet.platformTxn(withdraw(user, TxnType.WITHDRAW_FREEZE, "W-" + UUID.randomUUID(), "40"));

        WalletResult confirm = wallet.platformTxn(withdraw(user, TxnType.WITHDRAW_CONFIRM, orderNo, "40"));
        WalletResult unfreeze = wallet.platformTxn(withdraw(user, TxnType.WITHDRAW_UNFREEZE, orderNo, "40"));

        assertThat(confirm.isSuccess()).isTrue();
        assertThat(unfreeze.code()).isEqualTo(WalletResultCode.INVALID_REQUEST);
        assertThat(wallet.balance(user, CUR).balance()).isEqualByComparingTo("20");
        assertThat(wallet.balance(user, CUR).frozen()).isEqualByComparingTo("40");
    }

    @Test
    void ledgerRowsCarryTheLineTheyWereWrittenWith() {
        long user = fundedUser("100");
        wallet.bet(bet(user, "l1", "r-l1", "10"));
        wallet.updateUserLine(new UpdateUserLineCommand(user, 2, 1));
        wallet.bet(bet(user, "l2", "r-l2", "10"));
        // an older version arriving late (retry, reordering) must not move the player back
        wallet.updateUserLine(new UpdateUserLineCommand(user, 1, 1));
        wallet.bet(bet(user, "l3", "r-l3", "10"));

        List<Integer> lines = jdbc.queryForList(
                "SELECT user_line FROM wallet_txn WHERE user_id = ? ORDER BY id", Integer.class, user);
        // deposit and first bet before the move keep line 1; later rows are on line 2
        assertThat(lines).containsExactly(1, 1, 2, 2);

        // a wallet opened later in another currency starts on the player's current line
        wallet.open(new OpenWalletCommand(user, "USD"));
        assertThat(jdbc.queryForObject("SELECT user_line FROM wallet WHERE user_id = ? AND currency = 'USD'",
                Integer.class, user)).isEqualTo(2);

        wallet.updateUserLine(new UpdateUserLineCommand(user, 1, 2));
        assertThat(jdbc.queryForList("SELECT user_line FROM wallet WHERE user_id = ?", Integer.class, user))
                .containsOnly(1);
    }

    @Test
    void historyIsNewestFirstWithoutTombstonesAndRetentionDeletesOnlyOlderRows() {
        long user = fundedUser("100");
        wallet.bet(bet(user, "h1", "r-h1", "10"));
        wallet.rollback(rollback(user, "h2-rb", "h2"));   // rollback before its bet: a tombstone + a zero ROLLBACK
        LocalDateTime now = BingoTime.now();

        List<WalletTxn> history = shards.forUser(user, () -> ReplicaRoute.run(
                () -> txnMapper.findHistory(user, CUR, now.minusDays(1), now.plusMinutes(1), 0, 10)));
        assertThat(history).extracting(WalletTxn::getTxnType).containsExactly("ROLLBACK", "BET", "DEPOSIT");
        assertThat(history).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(1));

        long betId = history.get(1).getId();
        int deleted;
        do {
            deleted = txnMapper.deleteOlderThan(betId, 1000);
        } while (deleted == 1000);
        assertThat(jdbc.queryForList("SELECT txn_type FROM wallet_txn WHERE user_id = ? ORDER BY id", String.class, user))
                .containsExactly("BET", "BET", "ROLLBACK");   // deposit gone; bet, tombstone, rollback kept
    }

    // ------------------------------------------------------------------ helpers

    private static PlatformTxnCommand withdraw(long user, TxnType type, String orderNo, String amount) {
        return new PlatformTxnCommand(user, CUR, type, "PAYMENT", orderNo, new BigDecimal(amount), null);
    }

    private long fundedUser(String amount) {
        long user = USER_SEQ.incrementAndGet();
        wallet.open(new OpenWalletCommand(user, CUR));
        if (new BigDecimal(amount).signum() > 0) {
            wallet.platformTxn(new PlatformTxnCommand(user, CUR, TxnType.DEPOSIT, "PAYMENT",
                    "D-" + UUID.randomUUID(), new BigDecimal(amount), "test funding"));
        }
        return user;
    }

    private BigDecimal balance(long user) {
        return wallet.balance(user, CUR).balance();
    }

    private static BetCommand bet(long user, String txnId, String roundId, String amount) {
        return new BetCommand(user, CUR, PROVIDER, user + "-" + txnId, roundId, "slot-1", new BigDecimal(amount), false);
    }

    private static PayoutCommand payout(long user, String txnId, String roundId, String amount, TxnType type) {
        return new PayoutCommand(user, CUR, PROVIDER, user + "-" + txnId, roundId, "slot-1", new BigDecimal(amount),
                type, null, true, true);
    }

    private static RollbackCommand rollback(long user, String rollbackId, String betTxnId) {
        return new RollbackCommand(user, CUR, PROVIDER, user + "-" + rollbackId, user + "-" + betTxnId, TxnType.BET, "r-1", "slot-1");
    }
}

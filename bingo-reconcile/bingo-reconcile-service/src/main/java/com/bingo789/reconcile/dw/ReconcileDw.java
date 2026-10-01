package com.bingo789.reconcile.dw;

import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.model.GgrRow;
import com.bingo789.reconcile.model.ProviderTotals;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Every figure reconciliation and GGR need comes from StarRocks (deploy/starrocks/bingo_dw.sql), which loads the Kafka
 * streams into Primary Key tables: a redelivered or re-pulled record overwrites its row, so sums are exact under
 * at-least-once delivery. Reconcile itself consumes no Kafka and keeps no aggregates of its own.
 * <p>
 * StarRocks speaks the MySQL protocol. The pool is private to this class, NOT a DataSource bean (that would replace
 * the service's own TaurusDB DataSource); it connects lazily, so the service starts while StarRocks is unreachable
 * and only the jobs fail (and are retried by their next run).
 * <p>
 * Net amounts, as everywhere in reconciliation: bet = BET - ROLLBACK; payout = payouts - PAYOUT_REVERSAL + ADJUST
 * (signed by direction). Only normal rows (status 1) count; tombstones carry no money.
 */
@Component
public class ReconcileDw implements DisposableBean {

    private static final String NET_BET = """
            SUM(CASE txn_type WHEN 'BET' THEN amount WHEN 'ROLLBACK' THEN -amount ELSE 0 END)""";
    private static final String NET_PAYOUT = """
            SUM(CASE WHEN txn_type IN ('PAYOUT', 'FREE_PAYOUT', 'JACKPOT_PAYOUT', 'PROMO_PAYOUT') THEN amount
                     WHEN txn_type = 'PAYOUT_REVERSAL' THEN -amount
                     WHEN txn_type = 'ADJUST' THEN amount * direction
                     ELSE 0 END)""";
    private static final String GAME_TXNS = """
            status = 1 AND txn_type IN ('BET', 'ROLLBACK', 'PAYOUT', 'FREE_PAYOUT', 'JACKPOT_PAYOUT', 'PROMO_PAYOUT',
                                        'PAYOUT_REVERSAL', 'ADJUST')""";
    /** Provider statuses (as normalized by game-integration) whose stake and win do not count. */
    private static final String VOID_STATUSES = "('CANCELLED', 'CANCELED', 'VOID', 'REFUNDED', 'ROLLBACK')";

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    public ReconcileDw(ReconcileProperties properties) {
        ReconcileProperties.Dw dw = properties.dw();
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("reconcile-dw");
        pool.setJdbcUrl(dw.url());
        pool.setUsername(dw.username());
        pool.setPassword(dw.password());
        pool.setMaximumPoolSize(dw.poolSize());
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(5_000);
        this.dataSource = pool;
        this.jdbc = JdbcClient.create(pool);
    }

    /** Ledger side per provider and currency over [from, to) of the transaction time, all user lines. */
    public List<ProviderTotals> platformTotals(LocalDateTime from, LocalDateTime to) {
        return jdbc.sql("SELECT provider_code, currency, " + NET_BET + " AS bet_amount, " + NET_PAYOUT + " AS payout_amount"
                        + " FROM wallet_txn WHERE created_at >= :from AND created_at < :to AND " + GAME_TXNS
                        + " GROUP BY provider_code, currency")
                .param("from", from).param("to", to)
                .query(ProviderTotals.class).list();
    }

    /** Provider side (pulled bet history) per provider and currency over [from, to) of the provider's bet time. */
    public List<ProviderTotals> providerTotals(LocalDateTime from, LocalDateTime to) {
        return jdbc.sql("SELECT provider_code, currency, SUM(bet_amount) AS bet_amount, SUM(payout_amount) AS payout_amount"
                        + " FROM provider_bet WHERE bet_at >= :from AND bet_at < :to AND UPPER(status) NOT IN " + VOID_STATUSES
                        + " GROUP BY provider_code, currency")
                .param("from", from).param("to", to)
                .query(ProviderTotals.class).list();
    }

    /** GGR inputs of one reporting day [from, to) per user line, provider, game and currency; bet_count = stakes. */
    public List<GgrRow> ggrRows(LocalDateTime from, LocalDateTime to) {
        return jdbc.sql("SELECT user_line, provider_code, COALESCE(game_code, '') AS game_code, currency, "
                        + NET_BET + " AS bet, " + NET_PAYOUT + " AS payout,"
                        + " SUM(CASE txn_type WHEN 'BET' THEN 1 ELSE 0 END) AS bet_count"
                        + " FROM wallet_txn WHERE created_at >= :from AND created_at < :to AND " + GAME_TXNS
                        + " GROUP BY user_line, provider_code, COALESCE(game_code, ''), currency")
                .param("from", from).param("to", to)
                .query(GgrRow.class).list();
    }

    /**
     * Per-record matching of one day [from, to) by the round's first bet time: settled ledger rounds (game_round, the
     * latest revision of each) against the provider's bet records of the same (provider, round, player). Only
     * differing pairs are returned, at most {@code limit}. UNION ALL + GROUP BY rather than FULL OUTER JOIN, so the
     * same SQL runs on MySQL (tests).
     */
    public List<RoundPair> mismatchedRounds(LocalDateTime from, LocalDateTime to, int limit) {
        return jdbc.sql("""
                        SELECT provider_code, round_id, user_id, currency,
                               SUM(p_bet) AS platform_bet, SUM(p_payout) AS platform_payout,
                               SUM(v_bet) AS provider_bet, SUM(v_payout) AS provider_payout,
                               SUM(p_n) AS platform_rounds, SUM(v_n) AS provider_records
                          FROM (SELECT provider_code, round_id, user_id, currency, bet_amount AS p_bet,
                                       payout_amount AS p_payout, 0 AS v_bet, 0 AS v_payout, 1 AS p_n, 0 AS v_n
                                  FROM game_round
                                 WHERE bet_at >= :from AND bet_at < :to AND status = 'SETTLED'
                                UNION ALL
                                SELECT provider_code, round_id, user_id, currency, 0, 0, bet_amount, payout_amount, 0, 1
                                  FROM provider_bet
                                 WHERE bet_at >= :from AND bet_at < :to AND round_id IS NOT NULL
                                   AND UPPER(status) NOT IN """ + VOID_STATUSES + """
                               ) x
                         GROUP BY provider_code, round_id, user_id, currency
                        HAVING SUM(p_n) = 0 OR SUM(v_n) = 0 OR SUM(p_bet) <> SUM(v_bet) OR SUM(p_payout) <> SUM(v_payout)
                         LIMIT :limit
                        """)
                .param("from", from).param("to", to).param("limit", limit)
                .query(RoundPair.class).list();
    }

    /** One (provider, round, player) of the per-record matching; counts 0 = missing on that side. */
    public record RoundPair(String providerCode, String roundId, long userId, String currency,
                            BigDecimal platformBet, BigDecimal platformPayout,
                            BigDecimal providerBet, BigDecimal providerPayout,
                            long platformRounds, long providerRecords) {
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}

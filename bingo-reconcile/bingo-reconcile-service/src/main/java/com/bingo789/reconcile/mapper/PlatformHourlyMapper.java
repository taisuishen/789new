package com.bingo789.reconcile.mapper;

import com.bingo789.reconcile.model.PlatformHourlyRow;
import com.bingo789.reconcile.model.ProviderTotals;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** recon_platform_hourly: ledger (wallet_txn) aggregates per hour, user line, provider, game and currency. */
@Mapper
public interface PlatformHourlyMapper {

    /** Rows must be sorted by HourKey (consistent lock order across consumers). */
    @Insert("""
            <script>
            INSERT INTO recon_platform_hourly (stat_hour, user_line, provider_code, game_code, currency, bet_amount,
                                               rollback_amount, payout_amount, payout_reversal_amount, adjust_amount,
                                               bet_count, txn_count)
            VALUES
            <foreach collection="rows" item="r" separator=",">
              (#{r.statHour}, #{r.userLine}, #{r.providerCode}, #{r.gameCode}, #{r.currency}, #{r.betAmount},
               #{r.rollbackAmount}, #{r.payoutAmount}, #{r.payoutReversalAmount}, #{r.adjustAmount}, #{r.betCount},
               #{r.txnCount})
            </foreach>
            AS new
            ON DUPLICATE KEY UPDATE
                bet_amount = recon_platform_hourly.bet_amount + new.bet_amount,
                rollback_amount = recon_platform_hourly.rollback_amount + new.rollback_amount,
                payout_amount = recon_platform_hourly.payout_amount + new.payout_amount,
                payout_reversal_amount = recon_platform_hourly.payout_reversal_amount + new.payout_reversal_amount,
                adjust_amount = recon_platform_hourly.adjust_amount + new.adjust_amount,
                bet_count = recon_platform_hourly.bet_count + new.bet_count,
                txn_count = recon_platform_hourly.txn_count + new.txn_count
            </script>
            """)
    int upsert(@Param("rows") List<PlatformHourlyRow> rows);

    /**
     * Net bet = bet - rollback; net payout = payout - payout reversal + adjustments. Sums every user line: the
     * provider knows nothing about lines, so it is compared with the platform as a whole.
     */
    @Select("""
            SELECT provider_code, currency,
                   SUM(bet_amount - rollback_amount) AS bet_amount,
                   SUM(payout_amount - payout_reversal_amount + adjust_amount) AS payout_amount
              FROM recon_platform_hourly
             WHERE stat_hour = #{statHour}
             GROUP BY provider_code, currency
            """)
    List<ProviderTotals> sumByProviderCurrency(@Param("statHour") LocalDateTime statHour);
}

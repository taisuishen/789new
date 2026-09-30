package com.bingo789.reconcile.mapper;

import com.bingo789.reconcile.model.ProviderHourlyRow;
import com.bingo789.reconcile.model.ProviderTotals;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * recon_provider_hourly: provider bet-history aggregates per hour (of bet time), user line, provider, game and
 * currency. The line is the one bet-record stamped on the record when it was first pulled.
 */
@Mapper
public interface ProviderHourlyMapper {

    /** Rows must be sorted by HourKey (consistent lock order across consumers). */
    @Insert("""
            <script>
            INSERT INTO recon_provider_hourly (stat_hour, user_line, provider_code, game_code, currency, bet_amount,
                                               payout_amount, record_count)
            VALUES
            <foreach collection="rows" item="r" separator=",">
              (#{r.statHour}, #{r.userLine}, #{r.providerCode}, #{r.gameCode}, #{r.currency}, #{r.betAmount},
               #{r.payoutAmount}, #{r.recordCount})
            </foreach>
            AS new
            ON DUPLICATE KEY UPDATE
                bet_amount = recon_provider_hourly.bet_amount + new.bet_amount,
                payout_amount = recon_provider_hourly.payout_amount + new.payout_amount,
                record_count = recon_provider_hourly.record_count + new.record_count
            </script>
            """)
    int upsert(@Param("rows") List<ProviderHourlyRow> rows);

    /** Sums every user line (see PlatformHourlyMapper#sumByProviderCurrency). */
    @Select("""
            SELECT provider_code, currency, SUM(bet_amount) AS bet_amount, SUM(payout_amount) AS payout_amount
              FROM recon_provider_hourly
             WHERE stat_hour = #{statHour}
             GROUP BY provider_code, currency
            """)
    List<ProviderTotals> sumByProviderCurrency(@Param("statHour") LocalDateTime statHour);
}

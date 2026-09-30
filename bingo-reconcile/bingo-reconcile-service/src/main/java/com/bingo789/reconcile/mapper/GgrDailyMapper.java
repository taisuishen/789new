package com.bingo789.reconcile.mapper;

import com.bingo789.reconcile.model.GgrRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ggr_daily: net bet, net payout and GGR per reporting day, user line, provider, game and currency. The rows are
 * kept per line for line-scoped reports; settlement, the RTP monitor and the report below sum every line.
 */
@Mapper
public interface GgrDailyMapper {

    @Delete("DELETE FROM ggr_daily WHERE stat_date = #{statDate}")
    int deleteDay(@Param("statDate") LocalDate statDate);

    /** [from, to) are the UTC+8 bounds of the reporting day. */
    @Insert("""
            INSERT INTO ggr_daily (stat_date, user_line, provider_code, game_code, currency, bet, payout, ggr, bet_count)
            SELECT #{statDate}, user_line, provider_code, game_code, currency,
                   SUM(bet_amount - rollback_amount),
                   SUM(payout_amount - payout_reversal_amount + adjust_amount),
                   SUM(bet_amount - rollback_amount - payout_amount + payout_reversal_amount - adjust_amount),
                   SUM(bet_count)
              FROM recon_platform_hourly
             WHERE stat_hour >= #{from} AND stat_hour < #{to}
             GROUP BY user_line, provider_code, game_code, currency
            """)
    int insertDayFromHourly(@Param("statDate") LocalDate statDate, @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);

    /** Back-office report: totals (all lines) per day, provider and currency; both dates inclusive. */
    @Select("""
            <script>
            SELECT stat_date, provider_code, currency,
                   SUM(bet) AS bet, SUM(payout) AS payout, SUM(ggr) AS ggr, SUM(bet_count) AS bet_count
              FROM ggr_daily
             WHERE stat_date BETWEEN #{from} AND #{to}
             <if test="providerCode != null and providerCode != ''">AND provider_code = #{providerCode}</if>
             GROUP BY stat_date, provider_code, currency
             ORDER BY stat_date, provider_code, currency
            </script>
            """)
    List<GgrRow> sumByDayProviderCurrency(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                          @Param("providerCode") String providerCode);

    /** Settlement: GGR per provider and currency over [from, to), all lines (the provider bills the whole platform). */
    @Select("""
            SELECT provider_code, currency, SUM(ggr) AS ggr
              FROM ggr_daily
             WHERE stat_date >= #{from} AND stat_date < #{to}
             GROUP BY provider_code, currency
            """)
    List<GgrRow> sumByProviderCurrency(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * RTP monitor: per-game volume over [from, to), all lines (a game's maths does not depend on the player's line),
     * only games whose total volume is above the thresholds.
     */
    @Select("""
            SELECT provider_code, game_code, currency, SUM(bet) AS bet, SUM(payout) AS payout, SUM(bet_count) AS bet_count
              FROM ggr_daily
             WHERE stat_date >= #{from} AND stat_date < #{to}
             GROUP BY provider_code, game_code, currency
            HAVING SUM(bet) >= #{minBet} AND SUM(bet_count) >= #{minRounds}
            """)
    List<GgrRow> gameVolumes(@Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("minBet") BigDecimal minBet, @Param("minRounds") long minRounds);
}

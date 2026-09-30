package com.bingo789.reconcile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.reconcile.entity.ReconDiff;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface ReconDiffMapper extends BaseMapper<ReconDiff> {

    /**
     * Creates or refreshes the ticket of one comparison. Rows still under automation control (OPEN / AUTO_FIXED)
     * take the new status and values; operator decisions (RESOLVED / IGNORED) are left untouched.
     * MySQL applies the assignments left to right, so after the first one {@code recon_diff.status = new.status}
     * means "this row is under automation control". Existing-row columns are qualified with the table name: next to
     * the row alias {@code new} an unqualified column name is ambiguous.
     */
    @Insert("""
            INSERT INTO recon_diff (level, provider_code, currency, period_start, metric, platform_value, provider_value,
                                    diff, status, note)
            VALUES (#{level}, #{providerCode}, #{currency}, #{periodStart}, #{metric}, #{platformValue}, #{providerValue},
                    #{diff}, #{status}, #{note}) AS new
            ON DUPLICATE KEY UPDATE
                status = IF(recon_diff.status IN ('OPEN', 'AUTO_FIXED'), new.status, recon_diff.status),
                platform_value = IF(recon_diff.status = new.status, new.platform_value, recon_diff.platform_value),
                provider_value = IF(recon_diff.status = new.status, new.provider_value, recon_diff.provider_value),
                diff = IF(recon_diff.status = new.status, new.diff, recon_diff.diff),
                note = IF(recon_diff.status = new.status, new.note, recon_diff.note)
            """)
    int upsert(@Param("level") String level, @Param("providerCode") String providerCode, @Param("currency") String currency,
               @Param("periodStart") LocalDateTime periodStart, @Param("metric") String metric,
               @Param("platformValue") BigDecimal platformValue, @Param("providerValue") BigDecimal providerValue,
               @Param("diff") BigDecimal diff, @Param("status") String status, @Param("note") String note);

    /** A re-run found the comparison within tolerance (late events arrived): close the automatic ticket. */
    @Update("""
            UPDATE recon_diff
               SET status = 'AUTO_FIXED', platform_value = #{platformValue}, provider_value = #{providerValue},
                   diff = #{diff}, note = #{note}
             WHERE level = #{level} AND provider_code = #{providerCode} AND currency = #{currency}
               AND period_start = #{periodStart} AND metric = #{metric} AND status = 'OPEN'
            """)
    int autoClose(@Param("level") String level, @Param("providerCode") String providerCode, @Param("currency") String currency,
                  @Param("periodStart") LocalDateTime periodStart, @Param("metric") String metric,
                  @Param("platformValue") BigDecimal platformValue, @Param("providerValue") BigDecimal providerValue,
                  @Param("diff") BigDecimal diff, @Param("note") String note);

    /** Operator decision; only tickets still under automation control can be closed. */
    @Update("""
            UPDATE recon_diff SET status = #{status}, note = #{note}, resolved_at = #{now}
             WHERE id = #{id} AND status IN ('OPEN', 'AUTO_FIXED')
            """)
    int resolve(@Param("id") long id, @Param("status") String status, @Param("note") String note,
                @Param("now") LocalDateTime now);
}

package com.bingo789.game.transfer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TransferOrderMapper extends BaseMapper<TransferOrder> {

    String COLUMNS = "id, order_no, user_id, provider_code, currency, direction, amount, status, provider_ref, attempts, last_error, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM transfer_order WHERE order_no = #{orderNo}")
    TransferOrder findByOrderNo(@Param("orderNo") String orderNo);

    /** Compare-and-set transition; 0 rows means another worker already moved the order. */
    @Update("""
            UPDATE transfer_order SET status = #{to}, provider_ref = COALESCE(#{providerRef}, provider_ref),
                   last_error = #{error}, updated_at = NOW(3)
             WHERE order_no = #{orderNo} AND status = #{from}
            """)
    int transition(@Param("orderNo") String orderNo, @Param("from") String from, @Param("to") String to,
                   @Param("providerRef") String providerRef, @Param("error") String error);

    @Update("UPDATE transfer_order SET attempts = attempts + 1, last_error = #{error}, updated_at = NOW(3) WHERE order_no = #{orderNo}")
    int recordAttempt(@Param("orderNo") String orderNo, @Param("error") String error);

    @Select("SELECT " + COLUMNS + " FROM transfer_order WHERE status IN ('INIT', 'WALLET_DEBITED', 'PROVIDER_DONE', 'UNKNOWN') "
            + "AND updated_at < #{before} ORDER BY id LIMIT #{limit}")
    List<TransferOrder> findPending(@Param("before") LocalDateTime before, @Param("limit") int limit);
}

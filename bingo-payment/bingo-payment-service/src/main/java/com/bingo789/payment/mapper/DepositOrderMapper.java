package com.bingo789.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.payment.domain.DepositOrder;
import com.bingo789.payment.domain.DepositStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Every state change is a conditional UPDATE on the expected source states; 0 rows means someone else got there first. */
@Mapper
public interface DepositOrderMapper extends BaseMapper<DepositOrder> {

    default DepositOrder selectByOrderNo(String orderNo) {
        return selectOne(Wrappers.<DepositOrder>lambdaQuery().eq(DepositOrder::getOrderNo, orderNo));
    }

    /** Recovery scan: orders still waiting for the channel, oldest first, keyset-paged by id. */
    default List<DepositOrder> selectUnsettled(LocalDateTime createdBefore, long afterId, int limit) {
        return selectList(Wrappers.<DepositOrder>lambdaQuery()
                .in(DepositOrder::getStatus, DepositStatus.CREATED, DepositStatus.PENDING)
                .lt(DepositOrder::getCreatedAt, createdBefore)
                .gt(DepositOrder::getId, afterId)
                .orderByAsc(DepositOrder::getId)
                .last("LIMIT " + limit));
    }

    /** In-flight and successful deposits count towards the responsible-gaming limits. */
    @Select("""
            SELECT COALESCE(SUM(amount), 0) FROM deposit_order
             WHERE user_id = #{userId} AND currency = #{currency}
               AND status IN ('CREATED', 'PENDING', 'SUCCEEDED') AND created_at >= #{since}
            """)
    BigDecimal sumTowardsLimits(@Param("userId") long userId, @Param("currency") String currency,
                                @Param("since") LocalDateTime since);

    @Update("""
            UPDATE deposit_order SET status = 'PENDING', channel_order_no = #{channelOrderNo,jdbcType=VARCHAR}, updated_at = NOW(3)
             WHERE id = #{id} AND status = 'CREATED'
            """)
    int markPending(@Param("id") long id, @Param("channelOrderNo") String channelOrderNo);

    /** EXPIRED is a legal source: a late payment is still credited. FAILED is not (needs manual review). */
    @Update("""
            UPDATE deposit_order
               SET status = 'SUCCEEDED', paid_at = NOW(3), updated_at = NOW(3),
                   channel_order_no = COALESCE(channel_order_no, #{channelOrderNo,jdbcType=VARCHAR})
             WHERE id = #{id} AND status IN ('CREATED', 'PENDING', 'EXPIRED')
            """)
    int markSucceeded(@Param("id") long id, @Param("channelOrderNo") String channelOrderNo);

    @Update("UPDATE deposit_order SET first_deposit = 1 WHERE id = #{id}")
    int markFirstDeposit(@Param("id") long id);

    @Update("""
            UPDATE deposit_order SET status = 'FAILED', fail_reason = #{reason,jdbcType=VARCHAR}, updated_at = NOW(3)
             WHERE id = #{id} AND status IN ('CREATED', 'PENDING')
            """)
    int markFailed(@Param("id") long id, @Param("reason") String reason);

    @Update("""
            UPDATE deposit_order SET status = 'EXPIRED', fail_reason = #{reason,jdbcType=VARCHAR}, updated_at = NOW(3)
             WHERE id = #{id} AND status IN ('CREATED', 'PENDING')
            """)
    int markExpired(@Param("id") long id, @Param("reason") String reason);
}

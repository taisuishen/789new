package com.bingo789.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.payment.domain.WithdrawOrder;
import com.bingo789.payment.domain.WithdrawStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Every state change is a conditional UPDATE on the expected source state; 0 rows means someone else got there first. */
@Mapper
public interface WithdrawOrderMapper extends BaseMapper<WithdrawOrder> {

    default WithdrawOrder selectByOrderNo(String orderNo) {
        return selectOne(Wrappers.<WithdrawOrder>lambdaQuery().eq(WithdrawOrder::getOrderNo, orderNo));
    }

    /** Recovery scan: orders idle in {@code status} since before {@code updatedBefore}, keyset-paged by id. */
    default List<WithdrawOrder> selectStale(WithdrawStatus status, boolean decidedOnly, LocalDateTime updatedBefore,
                                            long afterId, int limit) {
        return selectList(Wrappers.<WithdrawOrder>lambdaQuery()
                .eq(WithdrawOrder::getStatus, status)
                .isNotNull(decidedOnly, WithdrawOrder::getAuditDecision)
                .lt(WithdrawOrder::getUpdatedAt, updatedBefore)
                .gt(WithdrawOrder::getId, afterId)
                .orderByAsc(WithdrawOrder::getId)
                .last("LIMIT " + limit));
    }

    @Update("UPDATE withdraw_order SET status = #{to}, updated_at = NOW(3) WHERE id = #{id} AND status = #{from}")
    int transition(@Param("id") long id, @Param("from") WithdrawStatus from, @Param("to") WithdrawStatus to);

    @Update("""
            UPDATE withdraw_order SET status = 'FAILED', fail_reason = #{reason,jdbcType=VARCHAR}, updated_at = NOW(3)
             WHERE id = #{id} AND status = #{from}
            """)
    int fail(@Param("id") long id, @Param("from") WithdrawStatus from, @Param("reason") String reason);

    /** The audit decision is written once; a different later decision finds audit_decision set and is refused. */
    @Update("""
            UPDATE withdraw_order
               SET status = 'APPROVED', audit_decision = 'APPROVED', audit_reason = #{reason,jdbcType=VARCHAR},
                   auditor = #{auditor}, updated_at = NOW(3)
             WHERE id = #{id} AND status = 'PENDING_AUDIT' AND audit_decision IS NULL
            """)
    int approve(@Param("id") long id, @Param("reason") String reason, @Param("auditor") String auditor);

    /** Records the rejection only; the status moves to REJECTED after the wallet confirmed the unfreeze. */
    @Update("""
            UPDATE withdraw_order
               SET audit_decision = 'REJECTED', audit_reason = #{reason,jdbcType=VARCHAR}, auditor = #{auditor}, updated_at = NOW(3)
             WHERE id = #{id} AND status = 'PENDING_AUDIT' AND audit_decision IS NULL
            """)
    int recordRejection(@Param("id") long id, @Param("reason") String reason, @Param("auditor") String auditor);

    /**
     * Keeps updated_at (assigning it to itself suppresses ON UPDATE), so an unresolved payout keeps its age for alerting.
     */
    @Update("""
            UPDATE withdraw_order SET channel_order_no = #{channelOrderNo}, updated_at = updated_at
             WHERE id = #{id} AND channel_order_no IS NULL
            """)
    int setChannelOrderNo(@Param("id") long id, @Param("channelOrderNo") String channelOrderNo);

    @Update("""
            UPDATE withdraw_order
               SET status = 'SUCCEEDED', channel_order_no = COALESCE(channel_order_no, #{channelOrderNo,jdbcType=VARCHAR}),
                   updated_at = NOW(3)
             WHERE id = #{id} AND status = 'PAYING'
            """)
    int markPaid(@Param("id") long id, @Param("channelOrderNo") String channelOrderNo);
}

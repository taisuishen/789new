package com.bingo789.reconcile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.reconcile.entity.RtpAlert;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RtpAlertMapper extends BaseMapper<RtpAlert> {

    /** One alert per game, currency and window end date; a re-run refreshes the figures. */
    @Insert("""
            INSERT INTO rtp_alert (stat_date, provider_code, game_code, currency, window_days, actual_rtp, theoretical_rtp,
                                   bet, payout, rounds)
            VALUES (#{statDate}, #{providerCode}, #{gameCode}, #{currency}, #{windowDays}, #{actualRtp}, #{theoreticalRtp},
                    #{bet}, #{payout}, #{rounds}) AS new
            ON DUPLICATE KEY UPDATE window_days = new.window_days, actual_rtp = new.actual_rtp,
                theoretical_rtp = new.theoretical_rtp, bet = new.bet, payout = new.payout, rounds = new.rounds
            """)
    int upsert(RtpAlert alert);
}

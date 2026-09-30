package com.bingo789.reconcile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Actual RTP of a game deviating from its certified theoretical RTP over the monitoring window. RTPs in percent. */
@Getter
@Setter
@TableName("rtp_alert")
public class RtpAlert {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** Last reporting day of the window. */
    private LocalDate statDate;
    private String providerCode;
    private String gameCode;
    private String currency;
    private Integer windowDays;
    private BigDecimal actualRtp;
    private BigDecimal theoreticalRtp;
    private BigDecimal bet;
    private BigDecimal payout;
    private Long rounds;
    private LocalDateTime createdAt;
}

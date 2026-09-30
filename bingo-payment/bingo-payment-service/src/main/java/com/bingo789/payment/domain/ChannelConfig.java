package com.bingo789.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

/** Operational configuration of a payment channel. Credentials live in DEW/CSMS, referenced by {@code configRef}. */
@Getter
@Setter
@TableName("payment_channel")
public class ChannelConfig {

    @TableId(value = "code", type = IdType.INPUT)
    private String code;
    private String name;
    private ChannelDirection direction;
    private ChannelStatus status;
    /** Comma-separated ISO 4217 codes. */
    private String currencies;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private String configRef;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean supportsCurrency(String currency) {
        return currencies != null && currency != null
                && Arrays.stream(currencies.split(",")).map(String::trim).anyMatch(currency::equalsIgnoreCase);
    }
}

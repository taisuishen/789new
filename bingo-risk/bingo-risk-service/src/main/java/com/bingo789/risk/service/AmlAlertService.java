package com.bingo789.risk.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.risk.common.PageResult;
import com.bingo789.risk.config.RiskProperties;
import com.bingo789.risk.domain.AmlAlert;
import com.bingo789.risk.domain.AmlAlertStatus;
import com.bingo789.risk.domain.AmlAlertType;
import com.bingo789.risk.mapper.AmlAlertMapper;
import com.bingo789.risk.web.dto.AmlAlertView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AML alerts for compliance review. Alerts are facts for the AML officer, who decides on covered / suspicious
 * transaction reports. TODO: CTR/STR filing workflow (status OPEN -> REPORTED / CLOSED with audit trail).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmlAlertService {

    private final AmlAlertMapper alertMapper;
    private final RiskProperties properties;

    /**
     * Idempotent per (type, refNo).
     *
     * @param userLine the player's line carried by the triggering event
     */
    public void raise(AmlAlertType type, long userId, int userLine, String refNo, BigDecimal amount, String currency,
                      Map<String, ?> detail) {
        LocalDateTime now = BingoTime.now();
        AmlAlert alert = new AmlAlert();
        alert.setUserId(userId);
        alert.setUserLine(userLine);
        alert.setAlertType(type);
        alert.setRefNo(refNo);
        alert.setAmount(amount);
        alert.setCurrency(currency);
        alert.setDetail(JsonUtils.toJson(detail));
        alert.setStatus(AmlAlertStatus.OPEN);
        alert.setCreatedAt(now);
        alert.setUpdatedAt(now);
        try {
            alertMapper.insert(alert);
            log.warn("AML alert {} raised: user={}, ref={}, amount={} {}", type, userId, refNo, amount, currency);
        } catch (RuntimeException e) {
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
        }
    }

    public void checkLargeDeposit(DepositSucceededEvent event) {
        BigDecimal threshold = properties.aml().effectiveLargeDepositThreshold();
        if (event.amount() == null || event.amount().compareTo(threshold) < 0) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("threshold", threshold);
        detail.put("channelCode", event.channelCode());
        detail.put("succeededAt", event.succeededAt());
        raise(AmlAlertType.LARGE_DEPOSIT, event.userId(), event.userLine(), event.orderNo(), event.amount(), event.currency(),
                detail);
    }

    public PageResult<AmlAlertView> list(AmlAlertStatus status, long page, long size) {
        Page<AmlAlert> request = PageResult.request(page, size);
        Page<AmlAlert> result = alertMapper.selectPage(request, Wrappers.<AmlAlert>lambdaQuery()
                .eq(status != null, AmlAlert::getStatus, status)
                .orderByDesc(AmlAlert::getCreatedAt));
        return PageResult.of(result, AmlAlertView::of);
    }
}

package com.bingo789.payment.service;

import com.bingo789.common.core.BizException;
import com.bingo789.payment.channel.ChannelRegistry;
import com.bingo789.payment.common.PaymentErrorCode;
import com.bingo789.payment.domain.ChannelConfig;
import com.bingo789.payment.domain.ChannelDirection;
import com.bingo789.payment.domain.ChannelStatus;
import com.bingo789.payment.mapper.ChannelConfigMapper;
import com.bingo789.payment.web.dto.ChannelView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * A channel is usable when its row is ENABLED for the direction and currency and an adapter bean exists.
 * TODO: cache channel rows (short TTL); they change rarely and are read on every order request.
 */
@Service
@RequiredArgsConstructor
public class ChannelConfigService {

    private final ChannelConfigMapper channelMapper;
    private final ChannelRegistry channelRegistry;

    public ChannelConfig requireUsable(String code, ChannelDirection direction, String currency, BigDecimal amount) {
        ChannelConfig config = code == null ? null : channelMapper.selectById(code);
        if (config == null || !isUsable(config, direction)) {
            throw new BizException(PaymentErrorCode.CHANNEL_UNAVAILABLE);
        }
        BizException.check(config.supportsCurrency(currency), PaymentErrorCode.CURRENCY_NOT_SUPPORTED);
        if (amount.compareTo(config.getMinAmount()) < 0 || amount.compareTo(config.getMaxAmount()) > 0) {
            throw new BizException(PaymentErrorCode.AMOUNT_OUT_OF_RANGE, "amount must be between "
                    + config.getMinAmount().stripTrailingZeros().toPlainString() + " and "
                    + config.getMaxAmount().stripTrailingZeros().toPlainString());
        }
        return config;
    }

    public List<ChannelView> listUsable(ChannelDirection direction, String currency) {
        return channelMapper.selectEnabled().stream()
                .filter(c -> isUsable(c, direction))
                .filter(c -> currency == null || c.supportsCurrency(currency))
                .map(ChannelView::of)
                .toList();
    }

    private boolean isUsable(ChannelConfig config, ChannelDirection direction) {
        return config.getStatus() == ChannelStatus.ENABLED
                && config.getDirection() != null && config.getDirection().supports(direction)
                && channelRegistry.find(config.getCode()).isPresent();
    }
}

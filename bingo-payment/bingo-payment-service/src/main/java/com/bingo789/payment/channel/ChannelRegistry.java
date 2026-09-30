package com.bingo789.payment.channel;

import com.bingo789.common.core.BizException;
import com.bingo789.payment.common.PaymentErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ChannelRegistry {

    private final Map<String, PaymentChannel> channels;

    public ChannelRegistry(ObjectProvider<PaymentChannel> channels) {
        // toMap fails fast on two adapters claiming the same code
        this.channels = channels.orderedStream().collect(Collectors.toUnmodifiableMap(PaymentChannel::code, Function.identity()));
        log.info("payment channels registered: {}", this.channels.keySet());
    }

    public Optional<PaymentChannel> find(String code) {
        return Optional.ofNullable(code == null ? null : channels.get(code));
    }

    public PaymentChannel require(String code) {
        return find(code).orElseThrow(() -> new BizException(PaymentErrorCode.CHANNEL_UNAVAILABLE));
    }
}

package com.bingo789.payment.channel.mock;

import com.bingo789.common.core.crypto.Hmacs;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.payment.channel.ChannelOutcome;
import com.bingo789.payment.channel.DepositInitiation;
import com.bingo789.payment.channel.DepositResult;
import com.bingo789.payment.channel.InvalidNotifyException;
import com.bingo789.payment.channel.PaymentChannel;
import com.bingo789.payment.channel.PayoutResult;
import com.bingo789.payment.config.PaymentProperties;
import com.bingo789.payment.domain.DepositOrder;
import com.bingo789.payment.domain.WithdrawOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Local development channel. Deposits and payouts complete only when a signed notify is posted, e.g.
 * <pre>
 * body='{"orderNo":"D123","channelOrderNo":"MOCK-D123","amount":"100.0000","currency":"PHP","status":"PAID"}'
 * sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$PAYMENT_MOCK_CHANNEL_SECRET" -hex | cut -d' ' -f2)
 * curl -H "X-Mock-Signature: $sig" -d "$body" localhost:8106/notify/MOCK/deposit
 * </pre>
 * Status values: PAID / SUCCEEDED, FAILED, anything else = pending.
 */
@Component
@ConditionalOnProperty(prefix = "bingo.payment.mock-channel", name = "enabled", havingValue = "true")
public class MockPaymentChannel implements PaymentChannel {

    public static final String CODE = "MOCK";
    static final String SIGNATURE_HEADER = "X-Mock-Signature";

    private final String secret;

    public MockPaymentChannel(PaymentProperties properties) {
        String configured = properties.mockChannel().secret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("bingo.payment.mock-channel.secret must be set when the mock channel is enabled");
        }
        this.secret = configured;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DepositInitiation createDeposit(DepositOrder order) {
        return new DepositInitiation("MOCK-" + order.getOrderNo(), "https://mock-pay.localhost/pay/" + order.getOrderNo(), null);
    }

    @Override
    public DepositResult parseDepositNotify(Map<String, String> headers, String rawBody) {
        MockNotify notify = verifyAndParse(headers, rawBody);
        return new DepositResult(notify.orderNo(), notify.channelOrderNo(), notify.amount(), notify.currency(),
                outcome(notify.status()), notify.reason());
    }

    /** The mock keeps no state, so queries never resolve an order; use a notify instead. */
    @Override
    public DepositResult queryDeposit(DepositOrder order) {
        return new DepositResult(order.getOrderNo(), order.getChannelOrderNo(), null, null, ChannelOutcome.PENDING, null);
    }

    @Override
    public PayoutResult submitPayout(WithdrawOrder order) {
        return new PayoutResult(order.getOrderNo(), "MOCKP-" + order.getOrderNo(), ChannelOutcome.PENDING, null);
    }

    @Override
    public PayoutResult parsePayoutNotify(Map<String, String> headers, String rawBody) {
        MockNotify notify = verifyAndParse(headers, rawBody);
        return new PayoutResult(notify.orderNo(), notify.channelOrderNo(), outcome(notify.status()), notify.reason());
    }

    @Override
    public PayoutResult queryPayout(WithdrawOrder order) {
        return new PayoutResult(order.getOrderNo(), order.getChannelOrderNo(), ChannelOutcome.PENDING, null);
    }

    private MockNotify verifyAndParse(Map<String, String> headers, String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new InvalidNotifyException("empty body");
        }
        String expected = Hmacs.hmacSha256Hex(secret, rawBody);
        if (!Hmacs.safeEquals(expected, headers.get(SIGNATURE_HEADER))) {
            throw new InvalidNotifyException("bad signature");
        }
        try {
            return JsonUtils.fromJson(rawBody, MockNotify.class);
        } catch (RuntimeException e) {
            throw new InvalidNotifyException("malformed body", e);
        }
    }

    private static ChannelOutcome outcome(String status) {
        if ("PAID".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status)) {
            return ChannelOutcome.SUCCEEDED;
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return ChannelOutcome.FAILED;
        }
        return ChannelOutcome.PENDING;
    }

    public record MockNotify(String orderNo, String channelOrderNo, BigDecimal amount, String currency, String status, String reason) {
    }
}

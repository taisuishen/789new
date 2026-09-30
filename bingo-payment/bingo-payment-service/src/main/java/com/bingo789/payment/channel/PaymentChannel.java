package com.bingo789.payment.channel;

import com.bingo789.payment.domain.DepositOrder;
import com.bingo789.payment.domain.WithdrawOrder;

import java.util.Map;

/**
 * SPI for one payment service provider. Implementations are Spring beans collected by {@link ChannelRegistry};
 * operational settings (limits, currencies, on/off) live in the payment_channel table.
 * <p>
 * Credentials are resolved from Huawei DEW/CSMS through payment_channel.config_ref; they are never stored in the
 * database, in yml or in logs.
 * <p>
 * Contract:
 * <ul>
 *   <li>Network / protocol failures are thrown as exceptions. Callers treat them as "unknown outcome" and retry
 *   later with the same order number, so adapters must send our order number as the merchant reference.</li>
 *   <li>{@link ChannelOutcome#FAILED} only for final, provider-guaranteed failures (see {@link ChannelOutcome}).</li>
 *   <li>Notify parsers verify the signature (plus timestamp / nonce where the provider supports it) before
 *   returning anything, and throw {@link InvalidNotifyException} otherwise.</li>
 * </ul>
 */
public interface PaymentChannel {

    /** Matches payment_channel.code. */
    String code();

    DepositInitiation createDeposit(DepositOrder order);

    /** @param headers request headers, case-insensitive keys */
    DepositResult parseDepositNotify(Map<String, String> headers, String rawBody);

    DepositResult queryDeposit(DepositOrder order);

    /** Usually returns PENDING: the final result arrives by notify or query. */
    PayoutResult submitPayout(WithdrawOrder order);

    /** @param headers request headers, case-insensitive keys */
    PayoutResult parsePayoutNotify(Map<String, String> headers, String rawBody);

    PayoutResult queryPayout(WithdrawOrder order);

    /** Response body the provider expects as acknowledgement; anything else makes it re-notify. */
    default String notifyAckBody(boolean accepted) {
        return accepted ? "SUCCESS" : "FAIL";
    }
}

package com.bingo789.game.callback;

import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.provider.ProviderRuntime;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Callback admission, in order: source IP allow-list, signature, timestamp window (replay protection).
 * Provider IPs are also allow-listed at the ELB/WAF; this is the application-level second layer.
 */
@Component
public class CallbackGuard {

    public void check(ProviderRuntime provider, CallbackRequest request) {
        if (provider.config().ipWhitelistEnabled() && !provider.ipWhitelist().matches(request.sourceIp())) {
            throw CallbackException.auth("source ip " + request.sourceIp() + " is not whitelisted");
        }
        provider.adapter().verifySignature(request, provider.client());
        Instant signedAt = provider.adapter().requestTimestamp(request);
        if (signedAt != null) {
            Duration skew = Duration.between(signedAt, request.receivedAt()).abs();
            if (skew.compareTo(provider.config().timestampTolerance()) > 0) {
                throw CallbackException.auth("request timestamp outside tolerance: skew " + skew.toSeconds() + "s");
            }
        }
    }
}

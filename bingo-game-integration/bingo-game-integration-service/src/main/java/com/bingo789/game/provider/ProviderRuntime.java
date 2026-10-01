package com.bingo789.game.provider;

import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.adapter.TransferCapable;
import com.bingo789.game.config.GameIntegrationProperties.ProviderConfig;
import com.bingo789.game.security.CidrMatcher;

/** Everything needed to serve one configured provider, built once at startup. */
public record ProviderRuntime(
        String code,
        ProviderConfig config,
        ProviderAdapter adapter,
        ProviderClient client,
        CidrMatcher ipWhitelist) {

    public boolean supportsCurrency(String currency) {
        return config.currencies().isEmpty() || config.currencies().contains(currency);
    }

    /** For providers whose callbacks carry no currency: the first configured one (null when none is configured). */
    public String defaultCurrency() {
        return config.currencies().isEmpty() ? null : config.currencies().getFirst();
    }

    public TransferCapable transferApi() {
        if (adapter instanceof TransferCapable transfer) {
            return transfer;
        }
        throw new IllegalStateException("adapter " + adapter.name() + " of provider " + code + " does not support transfer wallet");
    }
}

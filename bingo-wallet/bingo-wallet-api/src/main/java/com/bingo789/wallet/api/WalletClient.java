package com.bingo789.wallet.api;

import org.springframework.cloud.openfeign.FeignClient;

/**
 * Feign client for {@link WalletApi}. Timeouts are configured per consumer under
 * {@code spring.cloud.openfeign.client.config.walletClient} (the contextId); keep them well below the providers'
 * callback timeout and never enable Feign retries (retries belong to the provider, with the same key).
 */
@FeignClient(name = "bingo-wallet", contextId = "walletClient")
public interface WalletClient extends WalletApi {
}

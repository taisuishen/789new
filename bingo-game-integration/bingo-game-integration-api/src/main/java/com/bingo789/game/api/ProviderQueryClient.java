package com.bingo789.game.api;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "bingo-game-integration", contextId = "providerQueryClient")
public interface ProviderQueryClient extends ProviderQueryApi {
}

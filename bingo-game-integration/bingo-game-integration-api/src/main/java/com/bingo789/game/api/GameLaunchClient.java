package com.bingo789.game.api;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "bingo-game-integration", contextId = "gameLaunchClient")
public interface GameLaunchClient extends GameLaunchApi {
}

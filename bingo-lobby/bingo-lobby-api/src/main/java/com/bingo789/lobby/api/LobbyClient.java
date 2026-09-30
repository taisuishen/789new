package com.bingo789.lobby.api;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "bingo-lobby", contextId = "lobbyClient")
public interface LobbyClient extends LobbyApi {
}

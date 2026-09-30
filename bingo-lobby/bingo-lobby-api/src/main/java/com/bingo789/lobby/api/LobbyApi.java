package com.bingo789.lobby.api;

import com.bingo789.lobby.api.dto.GameView;
import com.bingo789.lobby.api.dto.ProviderStatusCommand;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

public interface LobbyApi {

    String PREFIX = "/internal/lobby";

    @PostMapping(PREFIX + "/providers/status")
    void updateProviderStatus(@RequestBody ProviderStatusCommand command);

    /** Full catalogue of one provider, including theoretical RTP; used by the RTP monitor. */
    @GetMapping(PREFIX + "/providers/{providerCode}/games")
    List<GameView> providerGames(@PathVariable("providerCode") String providerCode);
}

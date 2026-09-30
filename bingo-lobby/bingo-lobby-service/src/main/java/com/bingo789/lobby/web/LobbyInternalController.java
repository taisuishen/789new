package com.bingo789.lobby.web;

import com.bingo789.lobby.api.LobbyApi;
import com.bingo789.lobby.api.dto.GameView;
import com.bingo789.lobby.api.dto.ProviderStatusCommand;
import com.bingo789.lobby.service.CatalogService;
import com.bingo789.lobby.service.ProviderStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Internal RPC (not routed by the gateway). */
@RestController
@RequiredArgsConstructor
public class LobbyInternalController implements LobbyApi {

    private final ProviderStatusService providerStatusService;
    private final CatalogService catalogService;

    @Override
    public void updateProviderStatus(@Valid @RequestBody ProviderStatusCommand command) {
        providerStatusService.applyAutomatic(command);
    }

    @Override
    public List<GameView> providerGames(@PathVariable("providerCode") String providerCode) {
        return catalogService.providerGames(providerCode);
    }
}

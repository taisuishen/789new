package com.bingo789.game.query;

import com.bingo789.game.api.ProviderQueryApi;
import com.bingo789.game.api.dto.BetPullPage;
import com.bingo789.game.api.dto.BetPullQuery;
import com.bingo789.game.api.dto.ProviderGameView;
import com.bingo789.game.api.dto.ResolveRoundCommand;
import com.bingo789.game.api.dto.RoundResolutionView;
import com.bingo789.game.provider.ProviderRegistry;
import com.bingo789.game.provider.ProviderRuntime;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ProviderQueryController implements ProviderQueryApi {

    private final ProviderRegistry registry;
    private final RoundResolver roundResolver;

    @Override
    public List<ProviderGameView> listGames(@PathVariable("providerCode") String providerCode) {
        ProviderRuntime provider = registry.require(providerCode);
        return provider.client().execute(() -> provider.adapter().listGames(provider.client()));
    }

    @Override
    public RoundResolutionView resolveRound(@Valid @RequestBody ResolveRoundCommand command) {
        return roundResolver.resolve(command);
    }

    /** Callers (bet-record) must respect provider rate limits; the bulkhead caps our concurrency per provider. */
    @Override
    public BetPullPage pullBetRecords(@Valid @RequestBody BetPullQuery query) {
        ProviderRuntime provider = registry.require(query.providerCode());
        return provider.client().execute(() -> provider.adapter().pullBetRecords(query, provider.client()));
    }
}

package com.bingo789.game.api;

import com.bingo789.game.api.dto.BetPullPage;
import com.bingo789.game.api.dto.BetPullQuery;
import com.bingo789.game.api.dto.ProviderGameView;
import com.bingo789.game.api.dto.ResolveRoundCommand;
import com.bingo789.game.api.dto.RoundResolutionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/** Outbound provider queries, each running inside that provider's bulkhead and circuit breaker. */
public interface ProviderQueryApi {

    @GetMapping("/internal/game/providers/{providerCode}/games")
    List<ProviderGameView> listGames(@PathVariable("providerCode") String providerCode);

    @PostMapping("/internal/game/rounds/resolve")
    RoundResolutionView resolveRound(@RequestBody ResolveRoundCommand command);

    @PostMapping("/internal/game/bet-records/pull")
    BetPullPage pullBetRecords(@RequestBody BetPullQuery query);
}

package com.bingo789.lobby.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.lobby.service.CatalogService;
import com.bingo789.lobby.service.LaunchService;
import com.bingo789.lobby.web.dto.CategoryView;
import com.bingo789.lobby.web.dto.GameCard;
import com.bingo789.lobby.web.dto.LaunchRequest;
import com.bingo789.lobby.web.dto.LaunchResponse;
import com.bingo789.lobby.web.dto.PageView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Player API. Browsing is public (served from the local cache); launching requires a logged-in player. */
@RestController
@RequestMapping("/api/lobby")
@RequiredArgsConstructor
public class LobbyController {

    private final CatalogService catalogService;
    private final LaunchService launchService;

    @GetMapping("/categories")
    public Result<List<CategoryView>> categories() {
        return Result.ok(catalogService.categories());
    }

    @GetMapping("/games")
    public Result<PageView<GameCard>> games(@RequestParam(name = "category", required = false) String category,
                                            @RequestParam(name = "provider", required = false) String provider,
                                            @RequestParam(name = "page", defaultValue = "1") int page,
                                            @RequestParam(name = "size", defaultValue = "50") int size) {
        return Result.ok(catalogService.availableGames(category, provider, page, size));
    }

    @PostMapping("/games/{gameId}/launch")
    public Result<LaunchResponse> launch(@PathVariable("gameId") long gameId, @Valid @RequestBody LaunchRequest request) {
        long userId = CurrentUser.requireUserId();
        return Result.ok(launchService.launch(userId, gameId, request, CurrentUser.get().clientIp()));
    }
}

package com.bingo789.lobby.web;

import com.bingo789.common.core.Result;
import com.bingo789.lobby.entity.GameStatus;
import com.bingo789.lobby.service.GameAdminService;
import com.bingo789.lobby.service.ProviderStatusService;
import com.bingo789.lobby.web.dto.GameAdminView;
import com.bingo789.lobby.web.dto.GameStatusRequest;
import com.bingo789.lobby.web.dto.PageView;
import com.bingo789.lobby.web.dto.ProviderAdminView;
import com.bingo789.lobby.web.dto.ProviderStatusRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Back-office API, exposed only on the back-office ingress. */
// TODO: RBAC + audit log
@RestController
@RequestMapping("/admin/lobby")
@RequiredArgsConstructor
public class LobbyAdminController {

    private final ProviderStatusService providerStatusService;
    private final GameAdminService gameAdminService;

    @GetMapping("/providers")
    public Result<List<ProviderAdminView>> providers() {
        return Result.ok(gameAdminService.providers());
    }

    @PutMapping("/providers/{code}/status")
    public Result<Void> setProviderStatus(@PathVariable("code") String code, @Valid @RequestBody ProviderStatusRequest request) {
        providerStatusService.applyOperator(code, request.status(), request.reason());
        return Result.ok();
    }

    @GetMapping("/games")
    public Result<PageView<GameAdminView>> games(@RequestParam(name = "provider", required = false) String provider,
                                                 @RequestParam(name = "status", required = false) GameStatus status,
                                                 @RequestParam(name = "page", defaultValue = "1") int page,
                                                 @RequestParam(name = "size", defaultValue = "50") int size) {
        return Result.ok(gameAdminService.games(provider, status, page, size));
    }

    @PutMapping("/games/{id}/status")
    public Result<Void> setGameStatus(@PathVariable("id") long id, @Valid @RequestBody GameStatusRequest request) {
        gameAdminService.setGameStatus(id, request.status());
        return Result.ok();
    }
}

package com.bingo789.lobby.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.common.core.BizException;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.lobby.LobbyErrorCode;
import com.bingo789.lobby.config.LobbyProperties;
import com.bingo789.lobby.entity.Game;
import com.bingo789.lobby.entity.GameProvider;
import com.bingo789.lobby.entity.GameStatus;
import com.bingo789.lobby.mapper.GameMapper;
import com.bingo789.lobby.mapper.GameProviderMapper;
import com.bingo789.lobby.web.dto.GameAdminView;
import com.bingo789.lobby.web.dto.PageView;
import com.bingo789.lobby.web.dto.ProviderAdminView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameAdminService {

    private final GameMapper gameMapper;
    private final GameProviderMapper providerMapper;
    private final CatalogCache catalogCache;
    private final LobbyProperties properties;

    public List<ProviderAdminView> providers() {
        return providerMapper.selectList(Wrappers.<GameProvider>lambdaQuery().orderByAsc(GameProvider::getSort))
                .stream().map(ProviderAdminView::of).toList();
    }

    /** Back-office listing, e.g. OFFLINE games awaiting review after a catalogue sync. */
    public PageView<GameAdminView> games(String providerCode, GameStatus status, int page, int size) {
        int pageSize = Math.clamp(size, 1, properties.maxPageSize());
        IPage<Game> result = gameMapper.selectPage(new Page<>(Math.max(page, 1), pageSize), Wrappers.<Game>lambdaQuery()
                .eq(providerCode != null && !providerCode.isBlank(), Game::getProviderCode, providerCode)
                .eq(status != null, Game::getStatus, status)
                .orderByDesc(Game::getId));
        return new PageView<>(result.getRecords().stream().map(GameAdminView::of).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public void setGameStatus(long gameId, GameStatus status) {
        int updated = gameMapper.setStatus(gameId, status.name(), LocalDateTime.now(BingoTime.ZONE));
        BizException.check(updated == 1, LobbyErrorCode.GAME_NOT_FOUND);
        log.warn("game {} set to {} by operator", gameId, status);
        catalogCache.invalidate();
    }
}

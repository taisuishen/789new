package com.bingo789.lobby.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.lobby.api.enums.ProviderStatus;
import com.bingo789.lobby.api.dto.GameView;
import com.bingo789.lobby.config.LobbyProperties;
import com.bingo789.lobby.entity.Game;
import com.bingo789.lobby.entity.GameProvider;
import com.bingo789.lobby.entity.GameStatus;
import com.bingo789.lobby.mapper.GameMapper;
import com.bingo789.lobby.mapper.GameProviderMapper;
import com.bingo789.lobby.web.dto.CategoryView;
import com.bingo789.lobby.web.dto.GameCard;
import com.bingo789.lobby.web.dto.PageView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CatalogCache catalogCache;
    private final GameMapper gameMapper;
    private final GameProviderMapper providerMapper;
    private final LobbyProperties properties;

    public List<CategoryView> categories() {
        return catalogCache.snapshot().categories();
    }

    /** Available games only, paged in memory over the cached list. */
    public PageView<GameCard> availableGames(String category, String providerCode, int page, int size) {
        int pageNo = Math.max(page, 1);
        int pageSize = Math.clamp(size, 1, properties.maxPageSize());
        List<GameCard> games = catalogCache.snapshot().games(blankToNull(category), blankToNull(providerCode));
        long from = (long) (pageNo - 1) * pageSize;
        List<GameCard> items = from >= games.size() ? List.of()
                : games.subList((int) from, (int) Math.min(from + pageSize, games.size()));
        return new PageView<>(items, games.size(), pageNo, pageSize);
    }

    /** Full catalogue of one provider (any status) with theoretical RTP; read from the database, not the cache. */
    public List<GameView> providerGames(String providerCode) {
        GameProvider provider = providerMapper.selectById(providerCode);
        boolean providerActive = provider != null && provider.getStatus() == ProviderStatus.ACTIVE;
        return gameMapper.selectList(Wrappers.<Game>lambdaQuery()
                        .eq(Game::getProviderCode, providerCode)
                        .orderByAsc(Game::getId))
                .stream()
                .map(g -> new GameView(g.getId(), g.getProviderCode(), g.getGameCode(), g.getName(), g.getCategory(), g.getGameType(),
                        g.getTheoreticalRtp(), g.getThumbnailUrl(), providerActive && g.getStatus() == GameStatus.ONLINE))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

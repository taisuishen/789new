package com.bingo789.lobby.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.lobby.api.enums.ProviderStatus;
import com.bingo789.lobby.config.LobbyProperties;
import com.bingo789.lobby.entity.Game;
import com.bingo789.lobby.entity.GameCategory;
import com.bingo789.lobby.entity.GameProvider;
import com.bingo789.lobby.entity.GameStatus;
import com.bingo789.lobby.mapper.GameCategoryMapper;
import com.bingo789.lobby.mapper.GameMapper;
import com.bingo789.lobby.mapper.GameProviderMapper;
import com.bingo789.lobby.web.dto.CategoryView;
import com.bingo789.lobby.web.dto.GameCard;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Node-local catalogue cache (Caffeine). The whole catalogue is small (thousands of games), so it is loaded
 * as one snapshot: refreshed in the background after {@code cache-ttl}, served stale while the database is
 * unreachable (up to {@code cache-max-stale}), and dropped explicitly when a status changes on this node.
 * Browsing only: the launch path re-checks game and provider status against the database.
 */
@Slf4j
@Component
public class CatalogCache {

    private static final String KEY = "catalog";

    private final GameProviderMapper providerMapper;
    private final GameMapper gameMapper;
    private final GameCategoryMapper categoryMapper;
    private final LoadingCache<String, CatalogSnapshot> cache;

    public CatalogCache(GameProviderMapper providerMapper, GameMapper gameMapper, GameCategoryMapper categoryMapper,
                        LobbyProperties properties) {
        this.providerMapper = providerMapper;
        this.gameMapper = gameMapper;
        this.categoryMapper = categoryMapper;
        this.cache = Caffeine.newBuilder()
                .refreshAfterWrite(properties.cacheTtl())
                .expireAfterWrite(properties.cacheMaxStale())
                .build(key -> load());
    }

    public CatalogSnapshot snapshot() {
        return cache.get(KEY);
    }

    /**
     * Drops the snapshot on this node only; other nodes pick the change up within {@code cache-ttl}.
     * TODO: broadcast invalidation to the other lobby nodes via Redis pub/sub.
     */
    public void invalidate() {
        cache.invalidateAll();
    }

    private CatalogSnapshot load() {
        Map<String, GameProvider> providers = providerMapper.selectList(Wrappers.<GameProvider>lambdaQuery()).stream()
                .collect(Collectors.toMap(GameProvider::getCode, Function.identity()));
        List<CategoryView> categories = categoryMapper.selectList(Wrappers.<GameCategory>lambdaQuery()
                        .orderByAsc(GameCategory::getSort))
                .stream()
                .map(c -> new CategoryView(c.getCode(), c.getName()))
                .toList();
        List<CatalogSnapshot.Entry> games = gameMapper.selectList(Wrappers.<Game>lambdaQuery()
                        .eq(Game::getStatus, GameStatus.ONLINE))
                .stream()
                .filter(g -> {
                    GameProvider provider = providers.get(g.getProviderCode());
                    return provider != null && provider.getStatus() == ProviderStatus.ACTIVE;
                })
                .sorted(Comparator.comparing((Game g) -> g.getSort() == null ? 0 : g.getSort())
                        .thenComparing(g -> g.getName() == null ? "" : g.getName()))
                .map(g -> new CatalogSnapshot.Entry(g.getCategory(), g.getProviderCode(), toCard(g)))
                .toList();
        log.debug("catalogue loaded: {} providers, {} categories, {} available games", providers.size(), categories.size(), games.size());
        return new CatalogSnapshot(providers, categories, games);
    }

    private static GameCard toCard(Game g) {
        return new GameCard(String.valueOf(g.getId()), g.getProviderCode(), g.getGameCode(), g.getName(),
                g.getCategory(), g.getTheoreticalRtp(), g.getThumbnailUrl());
    }
}

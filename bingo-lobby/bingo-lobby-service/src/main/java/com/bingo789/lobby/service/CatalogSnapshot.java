package com.bingo789.lobby.service;

import com.bingo789.lobby.entity.GameProvider;
import com.bingo789.lobby.web.dto.CategoryView;
import com.bingo789.lobby.web.dto.GameCard;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Immutable view of the catalogue at load time. Filtered game lists are memoized per snapshot, so they
 * expire together with it and never outlive a provider status change.
 */
public final class CatalogSnapshot {

    private final Map<String, GameProvider> providers;
    private final List<CategoryView> categories;
    private final Set<String> categoryCodes;
    /** Games that are ONLINE and whose provider is ACTIVE, in display order. */
    private final List<Entry> availableGames;
    private final ConcurrentMap<Filter, List<GameCard>> filtered = new ConcurrentHashMap<>();

    CatalogSnapshot(Map<String, GameProvider> providers, List<CategoryView> categories, List<Entry> availableGames) {
        this.providers = Map.copyOf(providers);
        this.categories = List.copyOf(categories);
        this.categoryCodes = categories.stream().map(CategoryView::code).collect(Collectors.toUnmodifiableSet());
        this.availableGames = List.copyOf(availableGames);
    }

    public List<CategoryView> categories() {
        return categories;
    }

    public GameProvider provider(String code) {
        return providers.get(code);
    }

    /** Null filter values mean "any". Unknown codes yield an empty list and are not memoized (keeps the memo bounded). */
    public List<GameCard> games(String category, String providerCode) {
        if ((category != null && !categoryCodes.contains(category))
                || (providerCode != null && !providers.containsKey(providerCode))) {
            return List.of();
        }
        return filtered.computeIfAbsent(new Filter(category, providerCode), f -> availableGames.stream()
                .filter(e -> f.category() == null || Objects.equals(f.category(), e.category()))
                .filter(e -> f.providerCode() == null || Objects.equals(f.providerCode(), e.providerCode()))
                .map(Entry::card)
                .toList());
    }

    record Entry(String category, String providerCode, GameCard card) {
    }

    private record Filter(String category, String providerCode) {
    }
}

package com.bingo789.promotion.service;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.web.dto.ActivityView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Player-facing activity list. Players under a responsible-gaming restriction (self-exclusion, cool-off) or with a
 * suspended / closed account get an empty list: no marketing reaches them (bonuses are never granted to them either).
 */
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final PromotionCatalog catalog;
    private final PlayerLineCache playerLines;

    /** Promotions of the player's current line that are live now, highest sort first, then newest. */
    public List<ActivityView> listFor(long userId) {
        PlayerLineCache.Audience audience = playerLines.audienceOf(userId);
        if (!audience.marketable()) {
            return List.of();
        }
        int line = audience.line();
        LocalDateTime now = BingoTime.now();
        return catalog.entries().stream()
                .filter(entry -> entry.visibleOn(line) && entry.activeAt(now))
                .map(ActivityView::of)
                .toList();
    }
}

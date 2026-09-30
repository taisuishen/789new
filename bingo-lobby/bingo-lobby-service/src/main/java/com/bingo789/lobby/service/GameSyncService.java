package com.bingo789.lobby.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.common.core.game.GameType;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.game.api.dto.ProviderGameView;
import com.bingo789.lobby.entity.Game;
import com.bingo789.lobby.mapper.GameMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pulls a provider's catalogue and upserts provider-owned game fields. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameSyncService {

    private final ProviderQueryClient providerQueryClient;
    private final GameMapper gameMapper;
    private final SnowflakeIdGenerator idGenerator;

    public record SyncResult(int inserted, int updated, int unchanged, int missing) {
    }

    public SyncResult syncProvider(String providerCode) {
        List<ProviderGameView> remote = providerQueryClient.listGames(providerCode);
        Map<String, Game> existing = gameMapper.selectList(Wrappers.<Game>lambdaQuery().eq(Game::getProviderCode, providerCode))
                .stream().collect(Collectors.toMap(Game::getGameCode, Function.identity()));
        LocalDateTime now = LocalDateTime.now(BingoTime.ZONE);
        Set<String> seen = new HashSet<>();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (ProviderGameView view : remote == null ? List.<ProviderGameView>of() : remote) {
            if (view == null || view.gameCode() == null || view.gameCode().isBlank() || !seen.add(view.gameCode())) {
                continue;
            }
            Game candidate = toEntity(providerCode, view, now);
            Game current = existing.get(view.gameCode());
            if (current == null) {
                gameMapper.upsertFromProvider(candidate);
                inserted++;
                log.info("new game {}/{} inserted OFFLINE, pending ops review", providerCode, view.gameCode());
            } else if (changed(current, candidate)) {
                if (!sameDecimal(current.getTheoreticalRtp(), candidate.getTheoreticalRtp())) {
                    // a different RTP means a different certified game version
                    // TODO: route RTP changes of live games to compliance review instead of only logging
                    log.warn("theoretical RTP of {}/{} changed {} -> {} (status {}): verify certification",
                            providerCode, view.gameCode(), current.getTheoreticalRtp(), candidate.getTheoreticalRtp(), current.getStatus());
                }
                gameMapper.upsertFromProvider(candidate);
                updated++;
            } else {
                unchanged++;
            }
        }
        int missing = (int) existing.keySet().stream().filter(code -> !seen.contains(code)).count();
        if (missing > 0) {
            // TODO: decide with ops whether games dropped by the provider are taken OFFLINE automatically
            log.warn("{} games of provider {} are no longer in the provider catalogue", missing, providerCode);
        }
        return new SyncResult(inserted, updated, unchanged, missing);
    }

    private Game toEntity(String providerCode, ProviderGameView view, LocalDateTime now) {
        Game game = new Game();
        game.setId(idGenerator.nextId());
        game.setProviderCode(providerCode);
        game.setGameCode(view.gameCode());
        game.setName(truncate(view.name() == null || view.name().isBlank() ? view.gameCode() : view.name(), 255));
        // TODO: map provider categories to game_category codes (mapping table); stored upper-cased as-is for now
        game.setCategory(view.category() == null ? "" : truncate(view.category().trim().toUpperCase(Locale.ROOT), 32));
        // first guess only (insert); ops correct it during the certification review
        game.setGameType(GameType.parse(game.getCategory()).name());
        game.setTheoreticalRtp(view.theoreticalRtp() == null ? null : view.theoreticalRtp().setScale(3, RoundingMode.HALF_UP));
        game.setThumbnailUrl(truncate(view.thumbnailUrl(), 512));
        game.setMobileSupported(view.mobileSupported());
        game.setDesktopSupported(view.desktopSupported());
        game.setUpdatedAt(now);
        return game;
    }

    private static boolean changed(Game current, Game candidate) {
        return !Objects.equals(current.getName(), candidate.getName())
                || !Objects.equals(current.getCategory(), candidate.getCategory())
                || !sameDecimal(current.getTheoreticalRtp(), candidate.getTheoreticalRtp())
                || !Objects.equals(current.getThumbnailUrl(), candidate.getThumbnailUrl())
                || !Objects.equals(current.getMobileSupported(), candidate.getMobileSupported())
                || !Objects.equals(current.getDesktopSupported(), candidate.getDesktopSupported());
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

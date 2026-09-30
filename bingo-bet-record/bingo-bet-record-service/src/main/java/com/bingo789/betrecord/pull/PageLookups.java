package com.bingo789.betrecord.pull;

import com.bingo789.betrecord.catalog.GameInfo;
import com.bingo789.common.core.line.UserLine;

import java.util.Map;

/**
 * Player lines and game attributes of one pulled page, resolved once per page before it is stored (outside the
 * per-shard transactions, so remote calls never hold a database connection).
 *
 * @param userLines userId -> current line, for every player of the page
 * @param games     game code ("" when the provider sent none) -> catalogue attributes
 */
public record PageLookups(Map<Long, Integer> userLines, Map<String, GameInfo> games) {

    public PageLookups {
        userLines = Map.copyOf(userLines);
        games = Map.copyOf(games);
    }

    public int lineOf(long userId) {
        return userLines.getOrDefault(userId, UserLine.DEFAULT);
    }

    public GameInfo gameOf(String gameCode) {
        GameInfo game = gameCode == null ? null : games.get(gameCode);
        return game != null ? game : GameInfo.unknown(gameCode);
    }
}

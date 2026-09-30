package com.bingo789.betrecord.catalog;

import com.bingo789.common.core.game.GameType;
import com.bingo789.lobby.api.dto.GameView;

/**
 * Catalogue attributes copied onto game_round / provider_bet_record when the row is created (a snapshot).
 *
 * @param gameType {@link GameType} name, OTHER when the game or its type is unknown
 * @param gameName catalogue name, the game code when the game is unknown
 */
public record GameInfo(String gameType, String gameName) {

    /** game_name column length (same as the lobby's game.name). */
    static final int MAX_NAME_LENGTH = 255;

    public static GameInfo unknown(String gameCode) {
        return new GameInfo(GameType.OTHER.name(), gameCode == null ? "" : gameCode);
    }

    static GameInfo of(GameView game) {
        String name = game.name() == null || game.name().isBlank() ? game.gameCode() : game.name();
        return new GameInfo(GameType.parse(game.gameType()).name(),
                name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name);
    }
}

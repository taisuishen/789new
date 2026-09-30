package com.bingo789.common.mq.event;

import com.bingo789.common.core.line.UserLine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param bonusType          REBATE, FIRST_DEPOSIT, CAMPAIGN ...
 * @param turnoverMultiplier wagering requirement = amount x multiplier (0 = no requirement)
 * @param turnoverScope      {@link com.bingo789.common.core.game.TurnoverScope} name of that requirement (null = ALL)
 * @param turnoverScopeValue GAME_TYPE: a GameType name; GAME: "PROVIDER:GAME_CODE"; ALL: null
 */
public record BonusGrantedEvent(
        String bizNo,
        long userId,
        Integer userLine,
        String currency,
        BigDecimal amount,
        String bonusType,
        BigDecimal turnoverMultiplier,
        String turnoverScope,
        String turnoverScopeValue,
        Instant grantedAt) {

    /** A message written before the field existed carries no line: it belongs to line 1. */
    public BonusGrantedEvent {
        userLine = UserLine.orDefault(userLine);
    }
}

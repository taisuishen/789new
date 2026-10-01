package com.bingo789.promotion.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** What valid_bet_daily already holds for one round (promotion_round_applied). */
@Getter
@Setter
@NoArgsConstructor
public class PromotionRoundApplied {

    private String roundKey;
    private Integer revision;
    private BigDecimal validBet;

    public PromotionRoundApplied(String roundKey, int revision, BigDecimal validBet) {
        this.roundKey = roundKey;
        this.revision = revision;
        this.validBet = validBet;
    }
}

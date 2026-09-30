package com.bingo789.game.api.dto;

/**
 * @param outcome STILL_OPEN (provider says the round is in progress), SETTLED (missing payouts applied),
 *                CANCELLED (bets refunded) or UNKNOWN (provider unreachable / round unknown -> retry later, alert after N)
 */
public record RoundResolutionView(String outcome, String message) {

    public static final String STILL_OPEN = "STILL_OPEN";
    public static final String SETTLED = "SETTLED";
    public static final String CANCELLED = "CANCELLED";
    public static final String UNKNOWN = "UNKNOWN";
}

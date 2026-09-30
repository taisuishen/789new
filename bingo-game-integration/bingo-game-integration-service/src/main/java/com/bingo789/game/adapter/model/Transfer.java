package com.bingo789.game.adapter.model;

import java.math.BigDecimal;

/** Transfer-wallet protocol types. The provider must accept our orderNo as its idempotency key and let us query by it. */
public final class Transfer {

    private Transfer() {
    }

    public record Request(String orderNo, String playerId, String currency, BigDecimal amount) {
    }

    /** NOT_FOUND from a query means the provider never applied the order. */
    public enum State { SUCCEEDED, FAILED, NOT_FOUND, UNKNOWN }

    public record Result(State state, String providerRef, String message) {

        public static Result unknown(String message) {
            return new Result(State.UNKNOWN, null, message);
        }
    }
}

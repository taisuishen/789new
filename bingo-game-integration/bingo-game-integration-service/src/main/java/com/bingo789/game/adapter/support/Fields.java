package com.bingo789.game.adapter.support;

import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.provider.ProviderClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Input checks and amount conversions shared by the adapters; bad input is always {@code CallbackException.badRequest}. */
public final class Fields {

    private Fields() {
    }

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw CallbackException.badRequest(field + " is required");
        }
        return value;
    }

    public static String required(Map<String, String> fields, String field) {
        return required(fields.get(field), field);
    }

    public static BigDecimal requiredAmount(BigDecimal amount, String field) {
        if (amount == null) {
            throw CallbackException.badRequest(field + " is required");
        }
        return amount;
    }

    /** Decimal amount sent as text, e.g. "12.50". */
    public static BigDecimal amount(String value, String field) {
        try {
            return new BigDecimal(required(value, field).strip());
        } catch (NumberFormatException e) {
            throw CallbackException.badRequest(field + " is not a number: " + value);
        }
    }

    public static long longValue(String value, String field) {
        try {
            return Long.parseLong(required(value, field).strip());
        } catch (NumberFormatException e) {
            throw CallbackException.badRequest(field + " is not an integer: " + value);
        }
    }

    /** Integer minor units (cents) to currency units. */
    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    /** Currency units to integer cents, rounded DOWN (a provider must never see more than the player has). */
    public static long toCents(BigDecimal amount) {
        return amount == null ? 0 : amount.setScale(2, RoundingMode.DOWN).movePointRight(2).longValueExact();
    }

    /** Balance text at the provider's scale, rounded DOWN; null stays null. */
    public static String balance(BigDecimal balance, int scale) {
        return balance == null ? null : balance.setScale(scale, RoundingMode.DOWN).toPlainString();
    }

    /** For timestamps found inside an encrypted body: same rule as CallbackGuard for signed timestamps. */
    public static void checkTimestamp(Instant signedAt, CallbackRequest request, ProviderClient client) {
        Duration skew = Duration.between(signedAt, request.receivedAt()).abs();
        if (skew.compareTo(client.config().timestampTolerance()) > 0) {
            throw CallbackException.auth("request timestamp outside tolerance: skew " + skew.toSeconds() + "s");
        }
    }
}

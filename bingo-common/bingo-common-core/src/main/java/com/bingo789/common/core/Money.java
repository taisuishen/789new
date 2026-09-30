package com.bingo789.common.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money rules: always {@link BigDecimal} in Java, DECIMAL(20,4) in the database, never double.
 * <p>
 * Amounts coming from outside (providers, payment channels) are normalized with
 * {@link RoundingMode#UNNECESSARY}: an amount with more precision than we store is rejected,
 * never silently rounded.
 */
public final class Money {

    public static final int SCALE = 4;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {
    }

    /** Normalizes to storage scale; throws {@link BizException} for null, negative or over-precise amounts. */
    public static BigDecimal normalizeNonNegative(BigDecimal amount) {
        BizException.check(amount != null, CommonErrorCode.INVALID_AMOUNT, "amount is required");
        BizException.check(amount.signum() >= 0, CommonErrorCode.INVALID_AMOUNT, "amount must not be negative");
        return toScale(amount);
    }

    public static BigDecimal normalizePositive(BigDecimal amount) {
        BigDecimal normalized = normalizeNonNegative(amount);
        BizException.check(normalized.signum() > 0, CommonErrorCode.INVALID_AMOUNT, "amount must be positive");
        return normalized;
    }

    /** Signed amounts are only legal for adjustments. */
    public static BigDecimal normalizeSigned(BigDecimal amount) {
        BizException.check(amount != null, CommonErrorCode.INVALID_AMOUNT, "amount is required");
        return toScale(amount);
    }

    public static boolean isZero(BigDecimal amount) {
        return amount.signum() == 0;
    }

    private static BigDecimal toScale(BigDecimal amount) {
        try {
            return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BizException(CommonErrorCode.INVALID_AMOUNT, "amount precision exceeds " + SCALE + " decimals");
        }
    }
}

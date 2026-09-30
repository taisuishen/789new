package com.bingo789.promotion.terms;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Set;

/** Strict readers for config_json values; every failure is an IllegalArgumentException naming the setting. */
final class ConfigReader {

    /** MySQL keeps JSON numbers with a fraction as doubles: more significant digits would not survive storage. */
    private static final int MAX_SIGNIFICANT_DIGITS = 15;

    private ConfigReader() {
    }

    /** Unknown keys are rejected so a misspelt setting (e.g. a cap) is never silently ignored. */
    static void onlyKeys(JsonNode object, String path, Set<String> allowed) {
        for (String key : object.propertyNames()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(path + "." + key + " is not a known setting");
            }
        }
    }

    /** @return the object, or null when absent or JSON null and not required */
    static JsonNode object(JsonNode parent, String field, String path, boolean required) {
        JsonNode node = present(parent, field, path, required);
        if (node != null && !node.isObject()) {
            throw new IllegalArgumentException(path + "." + field + " must be an object");
        }
        return node;
    }

    /** @return the array, or null when absent or JSON null and not required */
    static JsonNode array(JsonNode parent, String field, String path, boolean required) {
        JsonNode node = present(parent, field, path, required);
        if (node != null && !node.isArray()) {
            throw new IllegalArgumentException(path + "." + field + " must be an array");
        }
        return node;
    }

    /** @return the string, or null when absent or JSON null */
    static String text(JsonNode parent, String field, String path) {
        JsonNode node = present(parent, field, path, false);
        if (node == null) {
            return null;
        }
        if (!node.isString()) {
            throw new IllegalArgumentException(path + "." + field + " must be a string");
        }
        return node.stringValue();
    }

    /**
     * A number with at most {@code maxScale} decimals and at least {@code min} ({@code min} excluded when
     * {@code minExclusive}).
     *
     * @return the value, or null when absent or JSON null and not required
     */
    static BigDecimal decimal(JsonNode parent, String field, String path, boolean required, BigDecimal min,
                              boolean minExclusive, int maxScale) {
        JsonNode node = present(parent, field, path, required);
        if (node == null) {
            return null;
        }
        String name = path + "." + field;
        if (!node.isNumber()) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        BigDecimal value = node.decimalValue();
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > maxScale) {
            throw new IllegalArgumentException(name + " allows at most " + maxScale + " decimals");
        }
        if (stripped.precision() > MAX_SIGNIFICANT_DIGITS) {
            throw new IllegalArgumentException(name + " allows at most " + MAX_SIGNIFICANT_DIGITS + " significant digits");
        }
        int cmp = value.compareTo(min);
        if (cmp < 0 || (minExclusive && cmp == 0)) {
            throw new IllegalArgumentException(name + " must be " + (minExclusive ? "greater than " : "at least ")
                    + min.toPlainString());
        }
        return value;
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static JsonNode present(JsonNode parent, String field, String path, boolean required) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw new IllegalArgumentException(path + "." + field + " is required");
            }
            return null;
        }
        return node;
    }
}

package com.bingo789.common.core.json;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson 3 mapper for message payloads and provider protocols.
 * Floats are read as BigDecimal so provider amounts never pass through double.
 */
public final class JsonUtils {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private JsonUtils() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static byte[] toJsonBytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static <T> T fromJson(byte[] json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        return MAPPER.readValue(json, type);
    }
}

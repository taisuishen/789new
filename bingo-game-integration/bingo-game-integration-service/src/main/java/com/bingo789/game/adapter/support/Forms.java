package com.bingo789.game.adapter.support;

import com.bingo789.game.adapter.model.CallbackRequest;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** application/x-www-form-urlencoded bodies and query strings, in arrival order, URL-decoded (UTF-8). */
public final class Forms {

    private Forms() {
    }

    /** First occurrence of a name wins; a name without '=' maps to "". */
    public static Map<String, String> parse(String encoded) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return fields;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            fields.putIfAbsent(name, value);
        }
        return fields;
    }

    public static Map<String, String> body(CallbackRequest request) {
        return parse(request.bodyAsString());
    }

    public static Map<String, String> query(CallbackRequest request) {
        return parse(request.queryString());
    }

    /** Query string first, then body fields (a field in both keeps the query value). */
    public static Map<String, String> queryAndBody(CallbackRequest request) {
        Map<String, String> fields = query(request);
        body(request).forEach(fields::putIfAbsent);
        return fields;
    }

    /** Encodes in iteration order (use a LinkedHashMap / TreeMap for a defined order); null values are skipped. */
    public static String encode(Map<String, ?> fields) {
        return fields.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(String.valueOf(e.getValue())))
                .collect(Collectors.joining("&"));
    }

    public static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

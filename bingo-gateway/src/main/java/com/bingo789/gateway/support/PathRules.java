package com.bingo789.gateway.support;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parsed list of "PATTERN" or "METHOD PATTERN" entries, e.g. {@code GET /api/lobby/games}. Patterns use Spring
 * {@code PathPattern} syntax: Ant-style {@code ?}, {@code *} (one segment) and {@code **} (remaining segments).
 */
public final class PathRules {

    private static final PathRules NONE = new PathRules(List.of());

    private final List<Rule> rules;

    private PathRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static PathRules none() {
        return NONE;
    }

    /** @throws org.springframework.web.util.pattern.PatternParseException on an invalid pattern */
    public static PathRules of(List<String> entries) {
        List<Rule> rules = new ArrayList<>(entries.size());
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space < 0) {
                rules.add(new Rule(null, PathPatternParser.defaultInstance.parse(trimmed)));
            } else {
                HttpMethod method = HttpMethod.valueOf(trimmed.substring(0, space).toUpperCase(Locale.ROOT));
                rules.add(new Rule(method, PathPatternParser.defaultInstance.parse(trimmed.substring(space + 1).trim())));
            }
        }
        return new PathRules(List.copyOf(rules));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public boolean matches(ServerHttpRequest request) {
        if (rules.isEmpty()) {
            return false;
        }
        PathContainer path = request.getPath().pathWithinApplication();
        HttpMethod method = request.getMethod();
        for (Rule rule : rules) {
            if (rule.matches(method, path)) {
                return true;
            }
        }
        return false;
    }

    /** @param method null matches any method */
    private record Rule(HttpMethod method, PathPattern pattern) {

        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return (method == null || method.equals(requestMethod)) && pattern.matches(path);
        }
    }
}

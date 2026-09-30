package com.bingo789.promotion.domain;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.promotion.terms.PromotionTerms;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parsed, read-only form of a {@link Promotion} row, as the catalog and the programme selection use it.
 *
 * @param type   {@link PromotionType} name (possibly one this version does not know)
 * @param config the parsed config_json; never modified
 */
public record PromotionEntry(long id, String name, String type, Set<Integer> userLines, LocalDateTime startTime,
                             LocalDateTime endTime, int sort, JsonNode config) {

    /** Among several matching promotions: the highest sort wins, then the newest (largest) id. */
    public static final Comparator<PromotionEntry> PRIORITY = (a, b) -> a.sort() != b.sort()
            ? Integer.compare(b.sort(), a.sort())
            : Long.compare(b.id(), a.id());

    /** @throws IllegalStateException when a JSON column does not have the expected shape */
    public static PromotionEntry of(Promotion row) {
        JsonNode config = row.getConfigJson() == null ? null : JsonUtils.mapper().readTree(row.getConfigJson());
        if (config == null || !config.isObject()) {
            throw new IllegalStateException("promotion " + row.getId() + ": config_json is not a JSON object");
        }
        Set<Integer> lines;
        try {
            lines = Set.copyOf(parseLines(row.getUserLines()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("promotion " + row.getId() + ": " + e.getMessage(), e);
        }
        return new PromotionEntry(row.getId(), row.getName(), row.getPromoType(), lines, row.getStartTime(),
                row.getEndTime(), row.getSort() == null ? 0 : row.getSort(), config);
    }

    /**
     * Parses a user_lines JSON array into ascending, distinct lines.
     *
     * @throws IllegalArgumentException unless it is a non-empty array of valid lines
     */
    public static List<Integer> parseLines(String json) {
        JsonNode node = json == null ? null : JsonUtils.mapper().readTree(json);
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("user_lines must be a non-empty JSON array");
        }
        Set<Integer> lines = new TreeSet<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber() || !item.canConvertToInt() || !UserLine.isValid(item.intValue())) {
                throw new IllegalArgumentException("user_lines must hold lines " + UserLine.MIN + ".." + UserLine.MAX);
            }
            lines.add(item.intValue());
        }
        return List.copyOf(lines);
    }

    /** In [startTime, endTime); the status is checked by whoever loaded the row. */
    public boolean activeAt(LocalDateTime at) {
        return !at.isBefore(startTime) && at.isBefore(endTime);
    }

    public boolean visibleOn(int line) {
        return userLines.contains(line);
    }

    /** The player-facing part of config_json, or null. Nothing else of config_json may leave the server. */
    public JsonNode display() {
        JsonNode display = config.get(PromotionTerms.DISPLAY);
        return display != null && display.isObject() ? display : null;
    }
}

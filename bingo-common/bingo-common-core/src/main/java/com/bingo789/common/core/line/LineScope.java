package com.bingo789.common.core.line;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The user lines a viewer (back-office operator, report, internal query) may see. Data of other lines must not
 * be returned: filter rows with {@code user_line IN (lines)}, or skip the filter when {@link #all()}.
 *
 * @param lines visible lines; {@code null} means every line (super administrators, platform-level jobs)
 */
public record LineScope(Set<Integer> lines) {

    private static final LineScope ALL = new LineScope(null);

    public LineScope {
        if (lines != null) {
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("a line scope needs at least one line");
            }
            lines.forEach(UserLine::require);
            lines = Set.copyOf(lines);
        }
    }

    public static LineScope all() {
        return ALL;
    }

    public static LineScope of(Integer... lines) {
        return new LineScope(Set.copyOf(Arrays.asList(lines)));
    }

    public static LineScope of(Collection<Integer> lines) {
        return new LineScope(Set.copyOf(lines));
    }

    /** Parses "1,2" (the wire format of internal query parameters); blank or "*" = every line. */
    public static LineScope parse(String csv) {
        if (csv == null || csv.isBlank() || csv.trim().equals("*")) {
            return ALL;
        }
        return new LineScope(Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::valueOf)
                .collect(Collectors.toSet()));
    }

    public boolean isAll() {
        return lines == null;
    }

    public boolean contains(int line) {
        return lines == null || lines.contains(line);
    }

    /** True when any of {@code other} is visible, e.g. a promotion shown on several lines. */
    public boolean intersects(Collection<Integer> other) {
        return lines == null || other.stream().anyMatch(lines::contains);
    }

    /** Wire format, inverse of {@link #parse}. */
    public String toCsv() {
        return lines == null ? "*" : lines.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}

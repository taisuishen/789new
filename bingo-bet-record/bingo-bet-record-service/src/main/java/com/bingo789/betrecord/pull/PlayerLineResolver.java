package com.bingo789.betrecord.pull;

import com.bingo789.common.core.line.UserLine;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.UserLineView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Current line of the players on a pulled page. Provider data carries no line, so a provider bet record is stored
 * with the line its player has when the record is first pulled (a snapshot, like every other player row).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayerLineResolver {

    /** user-service answers at most this many ids per call. */
    static final int MAX_IDS_PER_CALL = 1000;
    private static final int MAX_LOGGED_IDS = 20;

    private final UserClient userClient;

    /**
     * One user-service call per {@value #MAX_IDS_PER_CALL} distinct ids. A failed call is not caught: the pull run
     * fails and its window is retried, like any other failure there.
     *
     * @return a line for every given id; ids unknown to user-service get {@link UserLine#DEFAULT}
     */
    public Map<Long, Integer> linesOf(String providerCode, Collection<Long> userIds) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(userIds));
        Map<Long, Integer> lines = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += MAX_IDS_PER_CALL) {
            List<Long> chunk = distinct.subList(from, Math.min(from + MAX_IDS_PER_CALL, distinct.size()));
            List<UserLineView> answer = userClient.userLines(chunk);
            if (answer == null) {
                throw new IllegalStateException("user-service returned no player lines");
            }
            for (UserLineView view : answer) {
                lines.put(view.userId(), view.userLine());
            }
        }
        List<Long> unknown = distinct.stream().filter(id -> !lines.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            log.warn("provider {} reported {} players unknown to user-service, stored on line {}: {}", providerCode,
                    unknown.size(), UserLine.DEFAULT, unknown.subList(0, Math.min(unknown.size(), MAX_LOGGED_IDS)));
            unknown.forEach(id -> lines.put(id, UserLine.DEFAULT));
        }
        return lines;
    }
}

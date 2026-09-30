package com.bingo789.betrecord.pull;

import com.bingo789.common.core.line.UserLine;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.UserLineView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerLineResolverTest {

    private static final long UNKNOWN_PLAYER = 7L;

    private final UserClient userClient = mock(UserClient.class);
    private final PlayerLineResolver resolver = new PlayerLineResolver(userClient);

    @Test
    void asksOncePerThousandDistinctPlayersAndPutsUnknownOnesOnTheDefaultLine() {
        // every player is on line 2, except one user-service does not know
        when(userClient.userLines(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().filter(id -> id != UNKNOWN_PLAYER).map(id -> new UserLineView(id, 2)).toList();
        });
        List<Long> userIds = new ArrayList<>(LongStream.rangeClosed(1, 1500).boxed().toList());
        userIds.addAll(List.of(1L, UNKNOWN_PLAYER, 1500L)); // players with several records on the page

        Map<Long, Integer> lines = resolver.linesOf("DEMO", userIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> calls = ArgumentCaptor.forClass(List.class);
        verify(userClient, times(2)).userLines(calls.capture());
        assertThat(calls.getAllValues().stream().map(List::size).toList()).containsExactly(1000, 500);
        assertThat(lines).hasSize(1500);
        assertThat(lines.get(1L)).isEqualTo(2);
        assertThat(lines.get(1500L)).isEqualTo(2);
        assertThat(lines.get(UNKNOWN_PLAYER)).isEqualTo(UserLine.DEFAULT);
    }

    @Test
    void missingAnswerFailsInsteadOfDefaultingEveryone() {
        when(userClient.userLines(anyList())).thenReturn(null);

        assertThatThrownBy(() -> resolver.linesOf("DEMO", List.of(1L))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedCallIsNotSwallowed() {
        when(userClient.userLines(anyList())).thenThrow(new IllegalStateException("user-service down"));

        assertThatThrownBy(() -> resolver.linesOf("DEMO", List.of(1L))).hasMessage("user-service down");
    }

    @Test
    void emptyPageAsksNothing() {
        assertThat(resolver.linesOf("DEMO", List.of())).isEmpty();

        verify(userClient, never()).userLines(anyList());
    }
}

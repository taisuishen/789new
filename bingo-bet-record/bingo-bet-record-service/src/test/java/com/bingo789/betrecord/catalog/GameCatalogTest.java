package com.bingo789.betrecord.catalog;

import com.bingo789.lobby.api.LobbyClient;
import com.bingo789.lobby.api.dto.GameView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameCatalogTest {

    private static final String PROVIDER = "DEMO";

    private final LobbyClient lobby = mock(LobbyClient.class);
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    /** Background refreshes run inline. */
    private final GameCatalog catalog = new GameCatalog(lobby, nanos::get, Runnable::run);

    @Test
    void gamesAreServedFromOneCatalogueLoad() {
        when(lobby.providerGames(PROVIDER)).thenReturn(List.of(game("tiger", "Fortune Tiger", "slot"),
                game("crash", "", "SOME_NEW_TYPE")));

        assertThat(catalog.lookup(PROVIDER, "tiger")).isEqualTo(new GameInfo("SLOT", "Fortune Tiger"));
        // unknown type -> OTHER, blank name -> game code
        assertThat(catalog.lookup(PROVIDER, "crash")).isEqualTo(new GameInfo("OTHER", "crash"));
        verify(lobby, times(1)).providerGames(PROVIDER);
    }

    @Test
    void missingGameReloadsTheProviderAtMostOncePerMinute() {
        when(lobby.providerGames(PROVIDER)).thenReturn(
                List.of(game("tiger", "Fortune Tiger", "SLOT")),
                List.of(game("tiger", "Fortune Tiger", "SLOT"), game("ox", "Fortune Ox", "SLOT")));

        assertThat(catalog.lookup(PROVIDER, "ox")).isEqualTo(GameInfo.unknown("ox"));
        assertThat(catalog.lookup(PROVIDER, "ox")).isEqualTo(GameInfo.unknown("ox"));
        verify(lobby, times(1)).providerGames(PROVIDER);

        advance(GameCatalog.RETRY_INTERVAL);
        assertThat(catalog.lookup(PROVIDER, "ox")).isEqualTo(new GameInfo("SLOT", "Fortune Ox"));
        verify(lobby, times(2)).providerGames(PROVIDER);
    }

    @Test
    void staleCatalogueIsRefreshedWhileTheCachedCopyIsServed() {
        when(lobby.providerGames(PROVIDER)).thenReturn(
                List.of(game("tiger", "Fortune Tiger", "SLOT")),
                List.of(game("tiger", "Fortune Tiger II", "SLOT")));
        catalog.lookup(PROVIDER, "tiger");

        advance(GameCatalog.REFRESH_INTERVAL);
        // the lookup that finds the copy stale still answers from it and triggers the refresh
        assertThat(catalog.lookup(PROVIDER, "tiger").gameName()).isEqualTo("Fortune Tiger");
        assertThat(catalog.lookup(PROVIDER, "tiger").gameName()).isEqualTo("Fortune Tiger II");
        verify(lobby, times(2)).providerGames(PROVIDER);
    }

    @Test
    void lobbyOutageFallsBackToCachedAndUnknownValues() {
        when(lobby.providerGames(PROVIDER))
                .thenReturn(List.of(game("tiger", "Fortune Tiger", "SLOT")))
                .thenThrow(new IllegalStateException("lobby down"));
        catalog.lookup(PROVIDER, "tiger");

        advance(GameCatalog.REFRESH_INTERVAL);
        assertThat(catalog.lookup(PROVIDER, "tiger")).isEqualTo(new GameInfo("SLOT", "Fortune Tiger"));
        // the failed refresh was this minute's attempt: a missing game does not hit the lobby again
        assertThat(catalog.lookup(PROVIDER, "ox")).isEqualTo(GameInfo.unknown("ox"));
        verify(lobby, times(2)).providerGames(PROVIDER);
    }

    @Test
    void lobbyUnreachableOnFirstUseYieldsUnknownGames() {
        when(lobby.providerGames(PROVIDER)).thenThrow(new IllegalStateException("lobby down"));

        assertThat(catalog.lookup(PROVIDER, "tiger")).isEqualTo(new GameInfo("OTHER", "tiger"));
        assertThat(catalog.lookup(PROVIDER, "")).isEqualTo(new GameInfo("OTHER", ""));
        verify(lobby, times(1)).providerGames(PROVIDER);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private static GameView game(String gameCode, String name, String gameType) {
        return new GameView(1L, PROVIDER, gameCode, name, "slots", gameType, new BigDecimal("96.5"), null, true);
    }
}

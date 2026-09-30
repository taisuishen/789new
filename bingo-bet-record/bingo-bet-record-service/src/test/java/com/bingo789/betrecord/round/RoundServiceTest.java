package com.bingo789.betrecord.round;

import com.bingo789.betrecord.catalog.GameInfo;
import com.bingo789.betrecord.config.BetRecordProperties;
import com.bingo789.betrecord.entity.GameRound;
import com.bingo789.betrecord.mapper.GameRoundMapper;
import com.bingo789.betrecord.mapper.RoundTxnMapper;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.event.RoundSettledEvent;
import com.bingo789.common.mq.event.WalletTxnEvent;
import com.bingo789.game.api.dto.RoundResolutionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoundServiceTest {

    private static final String PROVIDER = "DEMO";
    private static final String ROUND = "r-1";
    private static final long USER = 42L;
    private static final long ROUND_PK = 1001L;
    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 9, 30, 13, 0);
    private static final GameInfo TIGER = new GameInfo("SLOT", "Fortune Tiger");

    private GameRoundMapper roundMapper;
    private RoundEventPublisher publisher;
    private RoundService service;

    @BeforeEach
    void setUp() {
        roundMapper = mock(GameRoundMapper.class);
        publisher = mock(RoundEventPublisher.class);
        BetRecordProperties properties = new BetRecordProperties(Duration.ofMinutes(30), Map.of(), 12, 200, 1, 7,
                Duration.ofMinutes(2), new BetRecordProperties.Pull(Duration.ofMinutes(5), Duration.ofMinutes(2),
                Duration.ofMinutes(30), 500, Duration.ofMillis(200), 2000), 2);
        service = new RoundService(roundMapper, mock(RoundTxnMapper.class), publisher, properties);
        doAnswer(invocation -> {
            invocation.<GameRound>getArgument(0).setId(ROUND_PK);
            return 1;
        }).when(roundMapper).insert(any(GameRound.class));
        when(roundMapper.close(anyLong(), any(), anyString(), any(), any())).thenReturn(1);
    }

    @Test
    void roundKeepsTheLineAndGameOfItsFirstEvent() {
        GameRound round = open(3);
        assertThat(round.getUserLine()).isEqualTo(3);
        assertThat(round.getGameType()).isEqualTo("SLOT");
        assertThat(round.getGameName()).isEqualTo("Fortune Tiger");

        // the player was moved to line 5 (and the catalogue changed) before the payout: the round is not affected
        when(roundMapper.findForUpdate(eq(PROVIDER), eq(ROUND), eq(USER), any(), any())).thenReturn(round);
        service.apply(txn(2, "PAYOUT", 5, "25", "115", true, STARTED.plusSeconds(3)), RoundEffect.PAYOUT,
                new GameInfo("OTHER", "renamed"));

        verify(roundMapper, times(1)).insert(any(GameRound.class));
        verify(roundMapper).close(eq(ROUND_PK), eq(STARTED.toLocalDate()), eq("SETTLED"), any(), eq(new BigDecimal("115")));
        RoundSettledEvent event = RoundEventPublisher.toEvent(published());
        assertThat(event.userLine()).isEqualTo(3);
        assertThat(event.gameCode()).isEqualTo("fortune-tiger");
        assertThat(event.gameType()).isEqualTo("SLOT");
        assertThat(event.gameName()).isEqualTo("Fortune Tiger");
        assertThat(event.status()).isEqualTo("SETTLED");
        assertThat(event.betAmount()).isEqualByComparingTo("10");
        assertThat(event.payoutAmount()).isEqualByComparingTo("25");
        assertThat(event.validBet()).isEqualByComparingTo("10");
        // balance after the payout that closed the round
        assertThat(event.balanceAfter()).isEqualByComparingTo("115");
    }

    @Test
    void roundClosedByTheResolverHasNoBalanceAfter() {
        GameRound round = open(2);
        when(roundMapper.lockById(ROUND_PK, STARTED.toLocalDate())).thenReturn(round);

        assertThat(service.closeAfterResolution(ROUND_PK, STARTED.toLocalDate(), RoundResolutionView.SETTLED)).isTrue();

        verify(roundMapper).close(eq(ROUND_PK), eq(STARTED.toLocalDate()), eq("SETTLED"), any(), isNull());
        RoundSettledEvent event = RoundEventPublisher.toEvent(published());
        assertThat(event.userLine()).isEqualTo(2);
        assertThat(event.balanceAfter()).isNull();
    }

    /** Opens the round with a bet of 10 by a player on {@code line}; returns the inserted row. */
    private GameRound open(int line) {
        service.apply(txn(1, "BET", line, "10", "90", false, STARTED), RoundEffect.BET, TIGER);
        ArgumentCaptor<GameRound> inserted = ArgumentCaptor.forClass(GameRound.class);
        verify(roundMapper).insert(inserted.capture());
        return inserted.getValue();
    }

    private GameRound published() {
        ArgumentCaptor<GameRound> closed = ArgumentCaptor.forClass(GameRound.class);
        verify(publisher).publishAfterCommit(closed.capture());
        return closed.getValue();
    }

    private static WalletTxnEvent txn(long id, String type, int line, String amount, String balanceAfter,
                                      boolean roundClosed, LocalDateTime at) {
        return new WalletTxnEvent(id, USER, line, "PHP", type, "BET".equals(type) ? -1 : 1, new BigDecimal(amount),
                new BigDecimal(balanceAfter), PROVIDER, "ptx-" + id, ROUND, "fortune-tiger", null, roundClosed, 1,
                at.toInstant(BingoTime.ZONE));
    }
}

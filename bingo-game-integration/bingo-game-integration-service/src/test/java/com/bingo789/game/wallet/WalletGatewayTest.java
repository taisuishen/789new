package com.bingo789.game.wallet;

import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.OpenBet;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.provider.ProviderRuntime;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.GameTokenView;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.TakeAllBetCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import com.bingo789.wallet.api.enums.WalletResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WalletGatewayTest {

    private static final long USER = 1001L;
    private static final String PLAYER = PlayerIds.encode(USER);

    private WalletClient wallet;
    private UserClient users;
    private OpenBetLedger openBets;
    private WalletGateway gateway;
    private ProviderRuntime provider;
    private ProviderRuntime tracking;

    @BeforeEach
    void setUp() {
        wallet = mock(WalletClient.class);
        users = mock(UserClient.class);
        openBets = mock(OpenBetLedger.class);
        gateway = new WalletGateway(wallet, users, new GameSessions(users), openBets);
        provider = new ProviderRuntime("PS", AdapterTests.config("op", List.of("PHP"), Map.of()), null,
                AdapterTests.client("PS", "op", null, Map.of(), Map.of()), null);
        ProviderAdapter adapter = mock(ProviderAdapter.class);
        when(adapter.tracksOpenBets()).thenReturn(true);
        tracking = new ProviderRuntime("YGR", AdapterTests.config("op", List.of("PHP"), Map.of()), adapter,
                AdapterTests.client("YGR", "op", null, Map.of(), Map.of()), null);
    }

    @Test
    void takeAllTakesTheBalanceAtTheProvidersScaleAndAnswersTheAmountTaken() {
        when(wallet.betAll(any())).thenReturn(WalletResult.success(1, "PHP", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("12.3400")));

        CommandOutcome outcome = gateway.execute(provider, new WalletCommand.TakeAll(PLAYER, "PHP", "t-1", "r-1", "fish"));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.amount()).isEqualByComparingTo("12.34");
        ArgumentCaptor<TakeAllBetCommand> command = ArgumentCaptor.forClass(TakeAllBetCommand.class);
        verify(wallet).betAll(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(USER);
        assertThat(command.getValue().providerTxnId()).isEqualTo("t-1");
        assertThat(command.getValue().scale()).isEqualTo(2);
    }

    @Test
    void aTrackedStakeIsRecordedBeforeAndAfterItsWalletCall() {
        when(users.verifyGameToken("tok")).thenReturn(token("YGR"));
        when(wallet.bet(any())).thenReturn(WalletResult.success(1, "PHP", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN));
        WalletCommand.Bet bet = new WalletCommand.Bet(null, null, "t-1", "r-1", "g", BigDecimal.TEN, false);

        CommandOutcome outcome = gateway.execute(tracking, new WalletCommand.Session("tok", bet));

        assertThat(outcome.isSuccess()).isTrue();
        InOrder order = inOrder(openBets, wallet);
        order.verify(openBets).pending("YGR", "tok", bet, USER, "PHP");
        order.verify(wallet).bet(any());
        order.verify(openBets).record("YGR", bet, outcome);
    }

    @Test
    void untrackedProvidersRecordNothingAndCannotBeAskedForOpenBets() {
        when(users.verifyGameToken("tok")).thenReturn(token("PS"));
        when(wallet.bet(any())).thenReturn(WalletResult.success(1, "PHP", BigDecimal.ONE, BigDecimal.ONE));

        gateway.execute(provider, new WalletCommand.Session("tok",
                new WalletCommand.Bet(null, null, "t-1", "r-1", "g", BigDecimal.TEN, false)));
        CommandOutcome query = gateway.execute(provider, new WalletCommand.OpenBets(Instant.EPOCH, Instant.now()));

        assertThat(query.code()).isEqualTo(CommandOutcome.Code.INVALID_REQUEST);
        verifyNoInteractions(openBets);
    }

    @Test
    void openBetsAreAnsweredFromTheLedger() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        OpenBet open = new OpenBet("t-1", "r-1", BigDecimal.TEN, "tok", "g", from.plusSeconds(5), false);
        when(openBets.open("YGR", from, to)).thenReturn(List.of(open));

        CommandOutcome outcome = gateway.execute(tracking, new WalletCommand.OpenBets(from, to));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.openBets()).containsExactly(open);
        verifyNoInteractions(wallet);
    }

    @Test
    void anExpiredTokenStillSettlesTheStakesPlacedWithItButCannotStake() {
        when(users.verifyGameToken("old")).thenReturn(GameTokenView.invalid());
        when(openBets.holder("YGR", "old")).thenReturn(new OpenBetLedger.Holder(USER, "PHP"));
        when(wallet.payout(any())).thenReturn(WalletResult.success(1, "PHP", BigDecimal.ONE, BigDecimal.ONE));
        WalletCommand.Payout rollIn = new WalletCommand.Payout(null, null, "w-1", "r-1", null, BigDecimal.ONE,
                TxnType.PAYOUT, null, true);

        CommandOutcome win = gateway.execute(tracking, new WalletCommand.Session("old", rollIn));
        CommandOutcome stake = gateway.execute(tracking, new WalletCommand.Session("old",
                new WalletCommand.Bet(null, null, "t-2", "r-2", "g", BigDecimal.TEN, false)));
        CommandOutcome untracked = gateway.execute(provider, new WalletCommand.Session("old", rollIn));

        assertThat(win.isSuccess()).isTrue();
        assertThat(win.playerId()).isEqualTo(PLAYER);
        verify(openBets).record("YGR", rollIn, win);
        assertThat(stake.code()).isEqualTo(CommandOutcome.Code.INVALID_TOKEN);
        assertThat(untracked.code()).isEqualTo(CommandOutcome.Code.INVALID_TOKEN);
        verify(wallet, never()).bet(any());
        verify(wallet, times(1)).payout(any());
    }

    @Test
    void sessionCommandsRunForTheTokensPlayerInTheTokensCurrency() {
        when(users.verifyGameToken("tok")).thenReturn(token("PS"));
        when(wallet.bet(any())).thenReturn(WalletResult.success(1, "PHP", BigDecimal.ONE, BigDecimal.ONE));

        CommandOutcome outcome = gateway.execute(provider, new WalletCommand.Session("tok",
                new WalletCommand.Bet(null, null, "t-1", "r-1", "g", BigDecimal.TEN, false)));
        gateway.execute(provider, new WalletCommand.Session("tok",
                new WalletCommand.Bet(null, null, "t-2", "r-1", "g", BigDecimal.TEN, false)));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.playerId()).isEqualTo(PLAYER);
        ArgumentCaptor<BetCommand> bet = ArgumentCaptor.forClass(BetCommand.class);
        verify(wallet, times(2)).bet(bet.capture());
        assertThat(bet.getValue().userId()).isEqualTo(USER);
        assertThat(bet.getValue().currency()).isEqualTo("PHP");
        // the second call was served from the session cache
        verify(users, times(1)).verifyGameToken("tok");
    }

    @Test
    void aTokenOfAnotherPlayerOrProviderIsRejected() {
        when(users.verifyGameToken("tok")).thenReturn(token("PS"));
        when(users.verifyGameToken("other")).thenReturn(token("PP"));

        CommandOutcome wrongPlayer = gateway.execute(provider, new WalletCommand.Session("tok",
                new WalletCommand.GetBalance(PlayerIds.encode(42), null)));
        CommandOutcome wrongProvider = gateway.execute(provider, new WalletCommand.Session("other",
                new WalletCommand.GetBalance(null, null)));

        assertThat(wrongPlayer.code()).isEqualTo(CommandOutcome.Code.INVALID_TOKEN);
        assertThat(wrongProvider.code()).isEqualTo(CommandOutcome.Code.INVALID_TOKEN);
        verifyNoInteractions(wallet);
    }

    @Test
    void aPlayerWhoMayNoLongerPlayIsStillPaidButCannotStake() {
        when(users.verifyGameToken("tok")).thenReturn(token("PS", false));
        when(wallet.payout(any())).thenReturn(WalletResult.success(1, "PHP", BigDecimal.ONE, BigDecimal.ONE));

        CommandOutcome bet = gateway.execute(provider, new WalletCommand.Session("tok",
                new WalletCommand.Bet(null, null, "t-1", "r-1", "g", BigDecimal.TEN, false)));
        CommandOutcome win = gateway.execute(provider, new WalletCommand.Session("tok", payout("w-1")));

        assertThat(bet.code()).isEqualTo(CommandOutcome.Code.PLAYER_LOCKED);
        assertThat(win.isSuccess()).isTrue();
        verify(wallet, never()).bet(any());
    }

    @Test
    void aBatchStopsAtTheFirstFailure() {
        when(wallet.payout(any()))
                .thenReturn(WalletResult.success(1, "PHP", BigDecimal.ONE, BigDecimal.ONE))
                .thenReturn(WalletResult.reject(WalletResultCode.BET_NOT_FOUND, "PHP", BigDecimal.ONE, "no bet"));

        CommandOutcome outcome = gateway.execute(provider, new WalletCommand.Batch(PLAYER, "PHP", List.of(
                payout("p-1"), payout("p-2"), payout("p-3"))));

        assertThat(outcome.code()).isEqualTo(CommandOutcome.Code.BET_NOT_FOUND);
        verify(wallet, times(2)).payout(any(PayoutCommand.class));
    }

    @Test
    void anAckMovesNoMoneyAndAMissingCurrencyFallsBackToTheProvidersFirst() {
        assertThat(gateway.execute(provider, new WalletCommand.Ack(null, null)).isSuccess()).isTrue();
        assertThat(gateway.execute(provider, new WalletCommand.Ack(PLAYER, null)).currency()).isEqualTo("PHP");
        verify(wallet, never()).balance(anyLong(), any());
    }

    @Test
    void idsLongerThanTheWalletColumnsAreHashedDeterministically() {
        String longId = "x".repeat(200);
        assertThat(WalletGateway.fit("short")).isEqualTo("short");
        assertThat(WalletGateway.fit(longId)).startsWith("sha256:").hasSize(71).isEqualTo(WalletGateway.fit(longId));
    }

    private static WalletCommand.Payout payout(String id) {
        return new WalletCommand.Payout(PLAYER, "PHP", id, "r-1", "g", BigDecimal.ONE, TxnType.PAYOUT, null, true);
    }

    private static GameTokenView token(String providerCode) {
        return token(providerCode, true);
    }

    private static GameTokenView token(String providerCode, boolean playAllowed) {
        return new GameTokenView(true, "tok", USER, providerCode, "g", "PHP", Instant.now().plusSeconds(3600), playAllowed);
    }
}

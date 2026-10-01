package com.bingo789.game.adapter.pg;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.CommandOutcome.Code;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.provider.ProviderClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgAdapterTest {

    private static final String OPERATOR_TOKEN = "op-token";
    private static final String SECRET_KEY = "secret-key";
    private static final String SESSION = "Zk3v9pQ2m8Xn4Rt6Yw1Ab5Cd7Ef0Gh2Ij4Kl6Mn8Op0";
    private static final String CREDENTIALS = "operator_token=" + OPERATOR_TOKEN + "&secret_key=" + SECRET_KEY;
    private static final String SPIN = CREDENTIALS + "&operator_player_session=" + SESSION + "&player_name=b7891001"
            + "&game_id=126&parent_bet_id=1000&bet_id=1001&currency_code=PHP&bet_amount=1.00&win_amount=2.50"
            + "&transfer_amount=1.50&real_transfer_amount=1.50&transaction_id=1001-1000-106-7&wallet_type=C&bet_type=1"
            + "&create_time=1759300000000&updated_time=1759300000123&is_round_end=false";

    private final PgAdapter adapter = new PgAdapter();
    private final ProviderClient client = AdapterTests.client("PG", OPERATOR_TOKEN, SECRET_KEY, Map.of(),
            Map.of("launchUrl", "https://m.pg.example/", "historyUrl", "https://history.pg.example"));

    @Test
    void operatorCredentialsMustMatchTheAccount() {
        adapter.verifySignature(post("Cash/Get", CREDENTIALS + "&operator_player_session=" + SESSION), client);

        assertThatThrownBy(() -> adapter.verifySignature(post("Cash/Get",
                "operator_token=" + OPERATOR_TOKEN + "&secret_key=guessed"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> adapter.verifySignature(post("Cash/Get", "secret_key=" + SECRET_KEY), client))
                .isInstanceOf(CallbackException.class);
    }

    @Test
    void sessionCallsRunOnTheGameToken() {
        assertThat(adapter.parse(post("VerifySession", CREDENTIALS + "&operator_player_session=" + SESSION + "&game_id=126"), client))
                .isEqualTo(new WalletCommand.Authenticate(SESSION, null));
        assertThat(adapter.parse(post("Cash/Get", CREDENTIALS + "&operator_player_session=" + SESSION + "&player_name=b7891001"), client))
                .isEqualTo(new WalletCommand.Session(SESSION, new WalletCommand.GetBalance("b7891001", null)));
    }

    @Test
    void transferInOutIsOneBetAndPayoutKeyedWithoutTheBalanceId() {
        WalletCommand command = adapter.parse(post("Cash/TransferInOut", SPIN + "&is_validate_bet=false&is_adjustment=false"), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Session.class, session -> {
            assertThat(session.token()).isEqualTo(SESSION);
            assertThat(session.command()).isInstanceOfSatisfying(WalletCommand.BetAndPayout.class, bp -> {
                assertThat(bp.playerId()).isEqualTo("b7891001");
                assertThat(bp.currency()).isEqualTo("PHP");
                assertThat(bp.betTxnId()).isEqualTo("1001-1000-106");
                assertThat(bp.payoutTxnId()).isEqualTo("1001-1000-106");
                assertThat(bp.roundId()).isEqualTo("1000");
                assertThat(bp.gameCode()).isEqualTo("126");
                assertThat(bp.betAmount()).isEqualByComparingTo("1.00");
                assertThat(bp.payoutAmount()).isEqualByComparingTo("2.50");
                assertThat(bp.roundClosed()).isFalse();
            });
        });
        // a resend after a timeout skips the session (it may have ended meanwhile)
        assertThat(adapter.parse(post("Cash/TransferInOut", SPIN + "&is_validate_bet=true&is_adjustment=false"), client))
                .isInstanceOf(WalletCommand.BetAndPayout.class);
    }

    @Test
    void transactionKeyCutsOnlyTheBalanceId() {
        assertThat(PgAdapter.transactionKey("1001-1000-106-7")).isEqualTo("1001-1000-106");
        // the old replaceAll("-6", "") also removed the parent segment here
        assertThat(PgAdapter.transactionKey("123-6-106-6")).isEqualTo("123-6-106");
        assertThat(PgAdapter.transactionKey("1001-1000-106")).isEqualTo("1001-1000-106");
    }

    @Test
    void inconsistentAmountsAreBadRequests() {
        assertThatThrownBy(() -> adapter.parse(post("Cash/TransferInOut", SPIN.replace("transfer_amount=1.50&real", "transfer_amount=9.99&real")), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));
        assertThatThrownBy(() -> adapter.parse(post("Cash/TransferInOut", SPIN.replace("real_transfer_amount=1.50", "real_transfer_amount=1500")), client))
                .isInstanceOf(CallbackException.class);
        assertThatThrownBy(() -> adapter.parse(post("Cash/Unknown", CREDENTIALS), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.UNKNOWN_ACTION));
    }

    @Test
    void adjustmentIsASignedAdjustAndItsReplyCarriesBothBalances() {
        CallbackRequest request = post("Cash/Adjustment", CREDENTIALS + "&player_name=b7891001&currency_code=PHP"
                + "&transfer_amount=-5.00&real_transfer_amount=-5.00&adjustment_id=A1&adjustment_transaction_id=A1-901"
                + "&adjustment_time=1759300000999&transaction_type=901");
        WalletCommand command = adapter.parse(request, client);
        assertThat(command).isInstanceOfSatisfying(WalletCommand.Adjust.class, a -> {
            assertThat(a.txnId()).isEqualTo("A1-901");
            assertThat(a.refTxnId()).isEqualTo("A1");
            assertThat(a.signedAmount()).isEqualByComparingTo("-5.00");
        });

        JsonNode data = body(adapter.render(request, command, new CommandOutcome(Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("95.009"), 3L, false, null), client)).path("data");
        assertThat(data.path("balance_after").decimalValue()).isEqualByComparingTo("95.00");
        assertThat(data.path("balance_before").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(data.path("updated_time").asLong()).isEqualTo(1759300000999L);
    }

    @Test
    void successEchoesTheRequestAndRoundsTheBalanceDown() {
        CallbackRequest request = post("Cash/TransferInOut", SPIN);
        CommandOutcome first = new CommandOutcome(Code.SUCCESS, "b7891001", "PHP", new BigDecimal("12.349"), 9L, false, null);
        CommandOutcome replay = new CommandOutcome(Code.SUCCESS, "b7891001", "PHP", new BigDecimal("12.349"), 9L, true, null);

        JsonNode body = body(adapter.render(request, null, first, client));
        assertThat(body.path("error").isNull()).isTrue();
        JsonNode data = body.path("data");
        assertThat(data.path("currency_code").asString()).isEqualTo("PHP");
        assertThat(data.path("balance_amount").decimalValue()).isEqualByComparingTo("12.34");
        assertThat(data.path("updated_time").asLong()).isEqualTo(1759300000123L);
        assertThat(data.path("real_transfer_amount").decimalValue()).isEqualByComparingTo("1.50");
        // a duplicate is answered like the first success
        assertThat(body(adapter.render(request, null, replay, client))).isEqualTo(body);

        assertThat(body(adapter.render(post("Cash/UpdateBetDetail", CREDENTIALS), null,
                CommandOutcome.of(Code.SUCCESS, null, null, null), client)).path("data").path("is_success").asBoolean()).isTrue();
    }

    @Test
    void failuresAreRenderedWithPgCodes() {
        CallbackRequest request = post("Cash/TransferInOut", SPIN);
        assertThat(error(adapter.render(request, null, outcome(Code.INSUFFICIENT_FUNDS), client))).isEqualTo("3202");
        assertThat(error(adapter.render(request, null, outcome(Code.INVALID_TOKEN), client))).isEqualTo("1034");
        assertThat(error(adapter.render(request, null, outcome(Code.PLAYER_NOT_FOUND), client))).isEqualTo("3004");
        assertThat(body(adapter.render(request, null, outcome(Code.INSUFFICIENT_FUNDS), client)).path("data").isNull()).isTrue();
        assertThat(error(adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE))).isEqualTo("1200");
        assertThat(error(adapter.renderError(request, CallbackError.AUTH_FAILED))).isEqualTo("1034");
    }

    @Test
    void launchUrlCarriesTheGameTokenAsOperatorPlayerSession() {
        LaunchCommand command = new LaunchCommand(1001L, 1, "PG", "126", "PHP", SESSION, "en", "MOBILE", null, "1.2.3.4", false);

        assertThat(adapter.launch(command, "b7891001", client).url())
                .isEqualTo("https://m.pg.example/126/index.html?btt=1&ot=" + OPERATOR_TOKEN + "&ops=" + SESSION + "&l=en");
    }

    @Test
    void historyPagesMapBetsToTheirParentRound() {
        String json = """
                {"data":[
                  {"betId":1001,"parentBetId":1000,"playerName":"b7891001","gameId":126,"currency":"PHP",
                   "betAmount":1.00,"winAmount":2.50,"betTime":1759300000000,"betEndTime":1759300001000,"rowVersion":1},
                  {"betId":1002,"parentBetId":1002,"playerName":"d789555","gameId":126,"currency":"PHP",
                   "betAmount":1,"winAmount":0,"betTime":1759300002000,"betEndTime":1759300003000}
                ],"error":null}""";

        PgAdapter.HistoryPage page = PgAdapter.parseHistory(json, "PG");

        assertThat(page.rows()).isEqualTo(2);
        assertThat(page.lastEndTime()).isEqualTo(1759300003000L);
        assertThat(page.records()).singleElement().satisfies(r -> {
            assertThat(r.providerBetId()).isEqualTo("1001");
            assertThat(r.roundId()).isEqualTo("1000");
            assertThat(r.userId()).isEqualTo(1001L);
            assertThat(r.gameCode()).isEqualTo("126");
            assertThat(r.betAmount()).isEqualByComparingTo("1.00");
            assertThat(r.payoutAmount()).isEqualByComparingTo("2.50");
            assertThat(r.status()).isEqualTo("SETTLED");
            assertThat(r.betTime()).isEqualTo(Instant.ofEpochMilli(1759300000000L));
            assertThat(r.settleTime()).isEqualTo(Instant.ofEpochMilli(1759300001000L));
        });
        assertThatThrownBy(() -> PgAdapter.parseHistory("{\"data\":null,\"error\":{\"code\":\"1034\",\"message\":\"Invalid request\"}}", "PG"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static CallbackRequest post(String action, String form) {
        return AdapterTests.post("PG", action, form);
    }

    private static CommandOutcome outcome(Code code) {
        return CommandOutcome.of(code, "b7891001", "PHP", BigDecimal.TEN);
    }

    private static JsonNode body(CallbackResponse response) {
        return JsonUtils.mapper().readTree(response.body());
    }

    private static String error(CallbackResponse response) {
        return body(response).path("error").path("code").asString();
    }
}

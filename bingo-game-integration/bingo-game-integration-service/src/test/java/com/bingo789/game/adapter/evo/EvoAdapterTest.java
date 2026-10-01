package com.bingo789.game.adapter.evo;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.wallet.api.enums.TxnType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvoAdapterTest {

    private static final String AUTH_TOKEN = "test-auth-token";
    private static final String UUID = "5373b655-c3a4-41d3-abf3-8b68847251d5";

    private final EvoAdapter adapter = new EvoAdapter();
    private final ProviderClient client = AdapterTests.client("EVO", "casino1", AUTH_TOKEN, Map.of(), Map.of());

    @Test
    void authTokenQueryParameterIsTheCredential() {
        adapter.verifySignature(request("balance", AUTH_TOKEN, "{}"), client);

        assertThatThrownBy(() -> adapter.verifySignature(request("balance", "wrong", "{}"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> adapter.verifySignature(request("balance", null, "{}"), client))
                .isInstanceOf(CallbackException.class);
        assertThat(status(adapter.renderError(request("balance", "wrong", trade(1)), CallbackError.AUTH_FAILED))).isEqualTo("INVALID_TOKEN_ID");
    }

    @Test
    void debitIsASessionBetKeyedByRefId() {
        WalletCommand command = adapter.parse(request("debit", AUTH_TOKEN, trade(10)), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Session.class, session -> {
            assertThat(session.token()).isEqualTo("sid-1");
            assertThat(session.command()).isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> {
                assertThat(bet.playerId()).isEqualTo("b7891001");
                assertThat(bet.currency()).isEqualTo("PHP");
                assertThat(bet.txnId()).isEqualTo("ref-1");
                assertThat(bet.roundId()).isEqualTo("game-1");
                assertThat(bet.gameCode()).isEqualTo("table-9");
                assertThat(bet.amount()).isEqualByComparingTo("10.11");
            });
        });
    }

    @Test
    void creditCancelAndPromoMapping() {
        assertThat(adapter.parse(request("credit", AUTH_TOKEN, trade(20)), client))
                .isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
                    // settlements are accepted with an expired sid: no session
                    assertThat(p.txnId()).isEqualTo("ref-1");
                    assertThat(p.betTxnId()).isEqualTo("ref-1");
                    assertThat(p.payoutType()).isEqualTo(TxnType.PAYOUT);
                    assertThat(p.roundClosed()).isTrue();
                });
        assertThat(adapter.parse(request("cancel", AUTH_TOKEN, trade(30)), client))
                .isInstanceOfSatisfying(WalletCommand.Rollback.class, r -> {
                    assertThat(r.rollbackTxnId()).isEqualTo("tx-30");
                    assertThat(r.targetTxnId()).isEqualTo("ref-1");
                    assertThat(r.targetType()).isEqualTo(TxnType.BET);
                });
        String promo = """
                {"sid":"sid-1","userId":"b7891001","uuid":"u","currency":"PHP",
                 "promoTransaction":{"type":"JackpotWin","id":"promo-1","amount":500.5}}""";
        assertThat(adapter.parse(request("promo_payout", AUTH_TOKEN, promo), client))
                .isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
                    assertThat(p.txnId()).isEqualTo("promo-1");
                    assertThat(p.roundId()).isEqualTo("promo:promo-1");
                    assertThat(p.payoutType()).isEqualTo(TxnType.JACKPOT_PAYOUT);
                    assertThat(p.amount()).isEqualByComparingTo("500.5");
                });
    }

    @Test
    void negativeCreditIsAnInvalidParameter() {
        String body = trade(40).replace("10.11", "-1");
        assertThatThrownBy(() -> adapter.parse(request("credit", AUTH_TOKEN, body), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));
        assertThat(status(adapter.renderError(request("credit", AUTH_TOKEN, body), CallbackError.BAD_REQUEST))).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void checkReturnsTheSidAndEchoesTheUuid() {
        CallbackRequest request = request("check", AUTH_TOKEN, "{\"sid\":\"sid-1\",\"userId\":\"b7891001\",\"uuid\":\"" + UUID + "\"}");
        WalletCommand command = adapter.parse(request, client);
        assertThat(command).isEqualTo(new WalletCommand.Session("sid-1", new WalletCommand.GetBalance("b7891001", null)));

        JsonNode body = json(adapter.render(request, command, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN), client));
        assertThat(body.path("status").asString()).isEqualTo("OK");
        assertThat(body.path("sid").asString()).isEqualTo("sid-1");
        assertThat(body.path("uuid").asString()).isEqualTo(UUID);
        assertThat(body.has("balance")).isFalse();
    }

    @Test
    void duplicatesAndFailuresUseEvolutionStatuses() {
        CallbackRequest request = request("debit", AUTH_TOKEN, trade(1));
        WalletCommand debit = adapter.parse(request, client);
        WalletCommand credit = adapter.parse(request("credit", AUTH_TOKEN, trade(2)), client);

        JsonNode ok = json(adapter.render(request, debit, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", new BigDecimal("99.999")), client));
        assertThat(ok.path("status").asString()).isEqualTo("OK");
        assertThat(ok.path("balance").decimalValue()).isEqualByComparingTo("99.99");
        assertThat(ok.path("uuid").asString()).isEqualTo(UUID);

        assertThat(EvoAdapter.status(debit, replay())).isEqualTo("BET_ALREADY_EXIST");
        assertThat(EvoAdapter.status(credit, replay())).isEqualTo("BET_ALREADY_SETTLED");
        assertThat(EvoAdapter.status(debit, outcome(CommandOutcome.Code.INSUFFICIENT_FUNDS))).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(EvoAdapter.status(debit, outcome(CommandOutcome.Code.INVALID_TOKEN))).isEqualTo("INVALID_SID");
        assertThat(EvoAdapter.status(debit, outcome(CommandOutcome.Code.TXN_CANCELLED))).isEqualTo("FINAL_ERROR_ACTION_FAILED");
        assertThat(EvoAdapter.status(debit, outcome(CommandOutcome.Code.PLAYER_LOCKED))).isEqualTo("ACCOUNT_LOCKED");
        assertThat(EvoAdapter.status(credit, outcome(CommandOutcome.Code.BET_NOT_FOUND))).isEqualTo("BET_DOES_NOT_EXIST");
        assertThat(EvoAdapter.status(credit, outcome(CommandOutcome.Code.PLAYER_NOT_FOUND))).isEqualTo("INVALID_PARAMETER");

        JsonNode retry = json(adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE));
        assertThat(retry.path("status").asString()).isEqualTo("TEMPORARY_ERROR");
        assertThat(retry.path("uuid").asString()).isEqualTo(UUID);
        assertThat(status(adapter.renderError(request("debit", AUTH_TOKEN, "not json"), CallbackError.RATE_LIMITED))).isEqualTo("TEMPORARY_ERROR");
    }

    @Test
    void gameHistoryParticipantsAreRecords() {
        String json = """
                {"data":[
                  {"date":"2026-10-01","games":[
                    {"id":"g-1","status":"Resolved","gameType":"baccarat","table":{"id":"tbl-1","name":"Speed Baccarat"},
                     "startedAt":"2026-10-01T08:00:00Z","settledAt":"2026-10-01T08:00:40Z",
                     "participants":[
                       {"playerId":"b7891001","playerGameId":"pg-1","currency":"PHP",
                        "bets":[{"code":"BAC_Player","stake":10,"payout":20,"placedOn":"2026-10-01T08:00:10Z"},
                                {"code":"BAC_Tie","stake":5,"payout":0,"placedOn":"2026-10-01T08:00:05Z"}]},
                       {"playerId":"someone-else","playerGameId":"pg-x","currency":"PHP","bets":[]}]}]},
                  {"date":"2026-10-02","games":[
                    {"id":"g-2","status":"Cancelled","table":{"id":"tbl-2"},"startedAt":"2026-10-02T00:00:00Z",
                     "participants":[{"playerId":"b7891002","playerGameId":"pg-2","currency":"PHP",
                        "bets":[{"stake":3,"payout":3,"placedOn":"2026-10-02T00:00:01Z"}]}]}]}
                ]}""";

        List<ProviderBetRecordView> records = EvoAdapter.parseHistory(json, "EVO");

        assertThat(records).extracting(ProviderBetRecordView::providerBetId).containsExactly("pg-1", "pg-2");
        ProviderBetRecordView first = records.getFirst();
        assertThat(first.roundId()).isEqualTo("g-1");
        assertThat(first.gameCode()).isEqualTo("tbl-1");
        assertThat(first.userId()).isEqualTo(1001L);
        assertThat(first.betAmount()).isEqualByComparingTo("15");
        assertThat(first.payoutAmount()).isEqualByComparingTo("20");
        assertThat(first.status()).isEqualTo("SETTLED");
        assertThat(first.betTime()).isEqualTo(Instant.parse("2026-10-01T08:00:05Z"));
        assertThat(first.settleTime()).isEqualTo(Instant.parse("2026-10-01T08:00:40Z"));
        assertThat(records.get(1).status()).isEqualTo("CANCELLED");
    }

    private static CommandOutcome replay() {
        return new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.ONE, 7L, true, null);
    }

    private static CommandOutcome outcome(CommandOutcome.Code code) {
        return CommandOutcome.of(code, "b7891001", "PHP", null);
    }

    private static String trade(int id) {
        return """
                {"sid":"sid-1","userId":"b7891001","uuid":"%s","currency":"PHP",
                 "game":{"id":"game-1","type":"baccarat","details":{"table":{"id":"table-9","vid":null}}},
                 "transaction":{"id":"tx-%d","refId":"ref-1","amount":10.11}}""".formatted(UUID, id);
    }

    private static CallbackRequest request(String action, String authToken, String body) {
        return new CallbackRequest("EVO", action, Map.of(), authToken == null ? null : "authToken=" + authToken,
                body.getBytes(StandardCharsets.UTF_8), "127.0.0.1", Instant.now());
    }

    private static JsonNode json(CallbackResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        return JsonUtils.mapper().readTree(response.body());
    }

    private static String status(CallbackResponse response) {
        return json(response).path("status").asString();
    }
}

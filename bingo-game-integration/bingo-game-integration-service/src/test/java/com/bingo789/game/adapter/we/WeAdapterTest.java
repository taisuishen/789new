package com.bingo789.game.adapter.we;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.adapter.support.Ciphers;
import com.bingo789.game.adapter.support.Forms;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.wallet.api.enums.TxnType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeAdapterTest {

    private static final String OPERATOR = "op-1";
    private static final String SECRET = "app-secret";
    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

    private final WeAdapter adapter = new WeAdapter();
    private final ProviderClient client = AdapterTests.client("WE", OPERATOR, SECRET, Map.of(), Map.of());

    @Test
    void merchantCredentialsAreCheckedOnEveryCallAndEveryItem() {
        adapter.verifySignature(form("debit", Map.of("operatorID", OPERATOR, "appSecret", SECRET)), client);
        adapter.verifySignature(form("credit", Map.of("data", "[" + creditItem("b7891001", "B1", 100) + "]")), client);
        adapter.verifySignature(form("netcheck", Map.of()), client);

        assertThatThrownBy(() -> adapter.verifySignature(form("debit", Map.of("operatorID", OPERATOR, "appSecret", "wrong")), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> adapter.verifySignature(form("rollback", Map.of("appSecret", SECRET)), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        String tampered = "[" + creditItem("b7891001", "B1", 100) + ","
                + creditItem("b7891001", "B2", 100).replace(SECRET, "guess") + "]";
        assertThatThrownBy(() -> adapter.verifySignature(form("credit", Map.of("data", tampered)), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
    }

    @Test
    void debitIsATokenSessionKeyedByBetIdInCents() {
        WalletCommand command = adapter.parse(form("debit", Map.of("token", TOKEN, "operatorID", OPERATOR, "appSecret", SECRET,
                "playerID", "b7891001", "betID", "B1", "gameID", "BAC01", "amount", "1050", "currency", "PHP", "type", "bet",
                "time", "1759300000")), client);

        assertThat(command).isEqualTo(new WalletCommand.Session(TOKEN,
                new WalletCommand.Bet("b7891001", null, "B1", "B1", "BAC01", new BigDecimal("10.50"), false)));
    }

    @Test
    void creditItemsBecomeOneBatchOfPayoutsOfOnePlayer() {
        WalletCommand single = adapter.parse(form("credit", Map.of("data", "[" + creditItem("b7891001", "B1", 250) + "]")), client);
        assertThat(single).isEqualTo(new WalletCommand.Payout("b7891001", null, "B1", "B1", "BAC01",
                new BigDecimal("2.50"), TxnType.PAYOUT, "B1", true));

        WalletCommand batch = adapter.parse(form("credit", Map.of("data", "[" + creditItem("b7891001", "B1", 250) + ","
                + creditItem("b7891001", "B2", 0) + "]")), client);
        assertThat(batch).isInstanceOfSatisfying(WalletCommand.Batch.class, b -> {
            assertThat(b.playerId()).isEqualTo("b7891001");
            assertThat(b.steps()).extracting(s -> ((WalletCommand.Payout) s).txnId()).containsExactly("B1", "B2");
        });

        assertThatThrownBy(() -> adapter.parse(form("credit", Map.of("data", "[" + creditItem("b7891001", "B1", 1) + ","
                + creditItem("b7891002", "B2", 1) + "]")), client)).isInstanceOf(CallbackException.class);
        assertThatThrownBy(() -> adapter.parse(form("credit", Map.of("data", "[]")), client)).isInstanceOf(CallbackException.class);
    }

    @Test
    void rollbackWithoutAmountReversesStakeAndWin() {
        Map<String, String> fields = new LinkedHashMap<>(Map.of("operatorID", OPERATOR, "appSecret", SECRET, "playerID", "b7891001",
                "betID", "B1", "gameID", "BAC01", "currency", "PHP", "type", "cancel", "time", "1"));
        fields.put("amount", "0");
        WalletCommand whole = adapter.parse(form("rollback", fields), client);
        assertThat(whole).isInstanceOfSatisfying(WalletCommand.Batch.class, b -> assertThat(b.steps())
                .extracting(s -> ((WalletCommand.Rollback) s).targetType()).containsExactly(TxnType.PAYOUT, TxnType.BET));

        fields.put("amount", "1050");
        assertThat(adapter.parse(form("rollback", fields), client))
                .isEqualTo(new WalletCommand.Rollback("b7891001", null, "cancel:B1", "B1", TxnType.BET, "B1", "BAC01"));

        fields.put("type", "refund");
        assertThatThrownBy(() -> adapter.parse(form("rollback", fields), client)).isInstanceOf(CallbackException.class);

        // no win to reverse: the stake was reversed, which is a success
        CallbackResponse response = adapter.render(form("rollback", Map.of()), whole,
                CommandOutcome.of(CommandOutcome.Code.TXN_NOT_FOUND, "b7891001", "PHP", BigDecimal.TEN), client);
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(body(response).path("balance").asLong()).isEqualTo(1000);
    }

    @Test
    void resettlementIsASignedAdjustPerResettlement() {
        String item = "{\"operatorID\":\"" + OPERATOR + "\",\"appSecret\":\"" + SECRET + "\",\"playerID\":\"b7891001\","
                + "\"betID\":\"B1\",\"gameID\":\"BAC01\",\"amount\":100,\"currency\":\"PHP\",\"type\":\"resettle\",\"time\":1,"
                + "\"validBetAmount\":100,\"resettleTime\":1759300000,\"resettleAmount\":%d}";

        assertThat(adapter.parse(form("resettlement", Map.of("data", "[" + item.formatted(-250) + "]")), client))
                .isEqualTo(new WalletCommand.Adjust("b7891001", null, "B1:1759300000", "B1", "B1", new BigDecimal("-2.50"), "resettlement"));
        assertThat(adapter.parse(form("resettlement", Map.of("data", "[" + item.formatted(0) + "]")), client))
                .isEqualTo(new WalletCommand.GetBalance("b7891001", null));
    }

    @Test
    void outcomesAreRenderedWithWeCodesAsHttpStatus() {
        CallbackRequest debit = form("debit", Map.of());
        CallbackResponse ok = adapter.render(debit, null, new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("12.349"), 77L, false, null), client);
        assertThat(ok.httpStatus()).isEqualTo(200);
        JsonNode okBody = body(ok);
        assertThat(okBody.path("code").asInt()).isEqualTo(200);
        assertThat(okBody.path("balance").asLong()).isEqualTo(1234);
        assertThat(okBody.path("refID").asString()).isEqualTo("77");

        CommandOutcome replay = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 77L, true, null);
        assertThat(adapter.render(debit, null, replay, client).httpStatus()).isEqualTo(409);
        assertThat(adapter.render(form("balance", Map.of()), null, replay, client).httpStatus()).isEqualTo(200);
        assertThat(adapter.render(debit, null, CommandOutcome.of(CommandOutcome.Code.INSUFFICIENT_FUNDS, "b7891001", "PHP", null), client)
                .httpStatus()).isEqualTo(402);
        assertThat(adapter.render(form("credit", Map.of()), null, CommandOutcome.of(CommandOutcome.Code.BET_NOT_FOUND, "b7891001", "PHP", null), client)
                .httpStatus()).isEqualTo(410);
        assertThat(adapter.render(debit, null, CommandOutcome.of(CommandOutcome.Code.INVALID_TOKEN, null, null, null), client)
                .httpStatus()).isEqualTo(404);
        CallbackResponse locked = adapter.render(debit, null, CommandOutcome.of(CommandOutcome.Code.PLAYER_LOCKED, "b7891001", "PHP", null), client);
        assertThat(locked.httpStatus()).isEqualTo(403);
        assertThat(body(locked).path("code").asInt()).isEqualTo(11013);
        assertThat(adapter.renderError(debit, CallbackError.SYSTEM_RETRYABLE).httpStatus()).isEqualTo(500);
        assertThat(adapter.renderError(debit, CallbackError.AUTH_FAILED).httpStatus()).isEqualTo(401);

        JsonNode netcheck = body(adapter.render(form("netcheck", Map.of()), new WalletCommand.Ack(null, null),
                CommandOutcome.of(CommandOutcome.Code.SUCCESS, null, null, null), client));
        assertThat(netcheck.path("operatorID").asString()).isEqualTo(OPERATOR);
    }

    @Test
    void reportSignatureIsMd5OfTheValuesInKeyOrder() {
        Map<String, String> params = new TreeMap<>(Map.of("startTime", "100", "endTime", "200", "limit", "5000",
                "requestTime", "150", "operatorID", OPERATOR, "isSettlementTime", "true"));

        assertThat(WeAdapter.signature(params, SECRET))
                .isEqualTo(Ciphers.md5Hex(SECRET + "200" + "true" + "5000" + OPERATOR + "150" + "100"));
    }

    @Test
    void reportRowsMapStatusAndCents() {
        String json = """
                {"dataCount": 3, "data": [
                  {"betID": "B1", "playerID": "B7891001", "betDateTime": 1759300000, "settlementTime": 1759300060,
                   "betStatus": "complete", "validBetAmount": "1000", "betAmount": 1000, "winlossAmount": 950,
                   "gameType": "BAC", "gameRoundID": "R1", "category": "Live"},
                  {"betID": "B2", "playerID": "b7891001", "betDateTime": 1759300000, "settlementTime": 0,
                   "betStatus": "cancel", "betAmount": 500, "winlossAmount": 0, "gameType": "BAC"},
                  {"betID": "B3", "playerID": "stranger", "betDateTime": 1759300000, "betStatus": "new", "betAmount": 1}
                ]}
                """;

        List<ProviderBetRecordView> records = WeAdapter.parseReport(JsonUtils.mapper().readTree(json), "WE", "PHP");

        assertThat(records).hasSize(2);
        assertThat(records.getFirst().userId()).isEqualTo(1001L);
        assertThat(records.getFirst().roundId()).isEqualTo("B1");
        assertThat(records.getFirst().betAmount()).isEqualByComparingTo("10.00");
        assertThat(records.getFirst().payoutAmount()).isEqualByComparingTo("19.50");
        assertThat(records.getFirst().settleTime()).isEqualTo(Instant.ofEpochSecond(1759300060));
        assertThat(records.get(1).status()).isEqualTo("CANCELLED");
        assertThat(records.get(1).settleTime()).isNull();
    }

    private static String creditItem(String player, String bet, long amount) {
        return "{\"operatorID\":\"" + OPERATOR + "\",\"appSecret\":\"" + SECRET + "\",\"playerID\":\"" + player + "\","
                + "\"gameID\":\"BAC01\",\"betID\":\"" + bet + "\",\"amount\":" + amount + ",\"validBetAmount\":100,"
                + "\"currency\":\"PHP\",\"type\":\"win\",\"time\":1759300000}";
    }

    private static CallbackRequest form(String action, Map<String, String> fields) {
        return AdapterTests.post("WE", action, Forms.encode(fields));
    }

    private static JsonNode body(CallbackResponse response) {
        return JsonUtils.mapper().readTree(response.body());
    }
}

package com.bingo789.game.adapter.ygr;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.adapter.support.Ciphers;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.wallet.api.enums.TxnType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YgrAdapterTest {

    private static final String AUTH = "test-authorization";
    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
    private static final String CONNECT = TOKEN + ".10001";

    private final YgrAdapter adapter = new YgrAdapter();
    private final ProviderClient client = AdapterTests.client("YGR", "789_BETBINGO_PHP", AUTH, Map.of("agentKey", "ak"),
            Map.of("manageApiUrl", "https://manage.example"));

    @Test
    void theAuthorizationHeaderMustMatch() {
        adapter.verifySignature(post("transaction/rollIn", AUTH, "{}"), client);

        assertThatThrownBy(() -> adapter.verifySignature(post("transaction/rollIn", AUTH + "x", "{}"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> adapter.verifySignature(AdapterTests.post("YGR", "transaction/rollIn", "{}"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
    }

    @Test
    void addGameResultIsOneBetAndPayoutOfTheTokenOwner() {
        WalletCommand command = adapter.parse(post("transaction/addGameResult", AUTH, """
                {"connectToken":"%s","transID":"T1","roundID":"R1","betAmount":1.00,"payoutAmount":2.50,
                 "winLoseAmount":1.50,"freeGame":0,"wagersTime":"2026-10-01T08:00:00.000+08:00"}
                """.formatted(CONNECT)), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Session.class, s -> {
            assertThat(s.token()).isEqualTo(TOKEN);
            assertThat(s.command()).isInstanceOfSatisfying(WalletCommand.BetAndPayout.class, bp -> {
                assertThat(bp.playerId()).isNull();
                assertThat(bp.betTxnId()).isEqualTo("T1");
                assertThat(bp.payoutTxnId()).isEqualTo("R1");
                assertThat(bp.roundId()).isEqualTo("R1");
                assertThat(bp.betAmount()).isEqualByComparingTo("1");
                assertThat(bp.payoutAmount()).isEqualByComparingTo("2.5");
                assertThat(bp.roundClosed()).isTrue();
            });
        });

        assertThatThrownBy(() -> adapter.parse(post("transaction/addGameResult", AUTH, """
                {"connectToken":"%s","transID":"T1","roundID":"R1","betAmount":1,"payoutAmount":2.5,"winLoseAmount":2,
                 "wagersTime":"2026-10-01T08:00:00.000+08:00"}
                """.formatted(CONNECT)), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));
    }

    @Test
    void fishingIsBetPayoutAndRefundOfTheRollOut() {
        WalletCommand rollOut = adapter.parse(post("transaction/rollOut", AUTH,
                "{\"connectToken\":\"" + CONNECT + "\",\"transID\":\"O1\",\"roundID\":\"F1\",\"amount\":100,\"takeAll\":false,"
                        + "\"rollTime\":\"2026-10-01T08:00:00+08:00\"}"), client);
        WalletCommand rollIn = adapter.parse(post("transaction/rollIn", AUTH,
                "{\"connectToken\":\"" + CONNECT + "\",\"transID\":\"I1\",\"roundID\":\"F1\",\"amount\":80.5,\"betAmount\":30,"
                        + "\"payoutAmount\":10.5,\"winLoseAmount\":-19.5,\"rollTime\":\"2026-10-01T08:10:00+08:00\"}"), client);
        WalletCommand refund = adapter.parse(post("transaction/refund", AUTH,
                "{\"connectToken\":\"" + CONNECT + "\",\"transID\":\"O1\",\"refTime\":\"2026-10-01T08:00:05+08:00\"}"), client);

        assertThat(((WalletCommand.Session) rollOut).command()).isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> {
            assertThat(bet.txnId()).isEqualTo("O1");
            assertThat(bet.roundId()).isEqualTo("F1");
            assertThat(bet.amount()).isEqualByComparingTo("100");
            assertThat(bet.roundClosed()).isFalse();
        });
        assertThat(((WalletCommand.Session) rollIn).command()).isInstanceOfSatisfying(WalletCommand.Payout.class, payout -> {
            assertThat(payout.txnId()).isEqualTo("I1");
            assertThat(payout.roundId()).isEqualTo("F1");
            assertThat(payout.amount()).isEqualByComparingTo("80.5");
            assertThat(payout.payoutType()).isEqualTo(TxnType.PAYOUT);
            assertThat(payout.betTxnId()).isNull();
            assertThat(payout.roundClosed()).isTrue();
        });
        assertThat(((WalletCommand.Session) refund).command())
                .isEqualTo(new WalletCommand.Rollback(null, null, "refund:O1", "O1", TxnType.BET, null, null));

        assertThatThrownBy(() -> adapter.parse(post("transaction/rollOut", AUTH,
                "{\"connectToken\":\"" + CONNECT + "\",\"transID\":\"O2\",\"roundID\":\"F2\",\"amount\":0,\"takeAll\":true}"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));
        assertThatThrownBy(() -> adapter.parse(post("betSlip/roundCheck", AUTH, "{}"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.UNKNOWN_ACTION));
    }

    @Test
    void tokenCallsUseTheGameTokenWithoutTheGameSuffix() {
        WalletCommand balance = adapter.parse(AdapterTests.get("YGR", "token/getConnectTokenAmount", "connectToken=" + CONNECT), client);
        WalletCommand auth = adapter.parse(post("token/authorizationConnectToken", AUTH, "{\"connectToken\":\"" + CONNECT + "\"}"), client);

        assertThat(balance).isEqualTo(new WalletCommand.Session(TOKEN, new WalletCommand.GetBalance(null, null)));
        assertThat(auth).isEqualTo(new WalletCommand.Authenticate(TOKEN, null));
        assertThat(YgrAdapter.sessionToken(TOKEN)).isEqualTo(TOKEN);
    }

    @Test
    void outcomesAreRenderedWithYgrStatusCodes() {
        CallbackRequest spin = post("transaction/addGameResult", AUTH, "{}");
        JsonNode ok = body(adapter.render(spin, null, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("10.129")), client));
        assertThat(ok.path("status").path("code").asString()).isEqualTo("0");
        assertThat(ok.path("data").path("balance").decimalValue()).isEqualByComparingTo("10.12");
        assertThat(ok.path("data").path("currency").asString()).isEqualTo("PHP");
        assertThat(ok.path("status").path("dateTime").asString()).endsWith("+08:00");

        CommandOutcome replay = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 1L, true, null);
        assertThat(code(adapter.render(spin, null, replay, client))).isEqualTo("208");
        assertThat(code(adapter.render(post("transaction/rollIn", AUTH, "{}"), null, replay, client))).isEqualTo("203");
        JsonNode insufficient = body(adapter.render(spin, null, CommandOutcome.of(CommandOutcome.Code.INSUFFICIENT_FUNDS,
                "b7891001", "PHP", BigDecimal.ONE), client));
        assertThat(insufficient.path("status").path("code").asString()).isEqualTo("204");
        assertThat(insufficient.path("data").isMissingNode()).isTrue();
        assertThat(code(adapter.render(spin, null, CommandOutcome.of(CommandOutcome.Code.INVALID_TOKEN, null, null, null), client)))
                .isEqualTo("401");
        assertThat(code(adapter.render(spin, null, CommandOutcome.of(CommandOutcome.Code.BET_NOT_FOUND, null, "PHP", null), client)))
                .isEqualTo("404");
        assertThat(code(adapter.renderError(spin, CallbackError.SYSTEM_RETRYABLE))).isEqualTo("999");
        assertThat(code(adapter.renderError(spin, CallbackError.AUTH_FAILED))).isEqualTo("401");
    }

    @Test
    void authorizationAnswersThePlayerAndTheGameOfTheToken() {
        CallbackRequest request = post("token/authorizationConnectToken", AUTH, "{\"connectToken\":\"" + CONNECT + "\"}");
        JsonNode data = body(adapter.render(request, new WalletCommand.Authenticate(TOKEN, null),
                CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", new BigDecimal("5")), client)).path("data");

        assertThat(data.path("userId").asString()).isEqualTo("b7891001");
        assertThat(data.path("gameId").asString()).isEqualTo("10001");
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("5");

        CallbackRequest rollOut = post("transaction/rollOut", AUTH, "{}");
        WalletCommand command = new WalletCommand.Session(TOKEN, new WalletCommand.Bet(null, null, "O1", "F1", null, new BigDecimal("100"), false));
        JsonNode rolled = body(adapter.render(rollOut, command, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("900")), client)).path("data");
        assertThat(rolled.path("amount").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    void betRecordKeyAndRows() {
        String keyG = Ciphers.md5Hex("26101" + "789_BETBINGO_PHP" + "ak");
        assertThat(YgrAdapter.recordSign("2026-10-01T08:00:00", "2026-10-01T08:30:00", 1, "789_BETBINGO_PHP", "ak",
                LocalDate.of(2026, 10, 1)))
                .isEqualTo(Ciphers.md5Hex("StartTime=2026-10-01T08:00:00&EndTime=2026-10-01T08:30:00&Page=1&PageLimit=10000"
                        + "&AgentId=789_BETBINGO_PHP" + keyG));

        String json = """
                {"ErrorCode":0,"Message":"OK","Data":{"result":[
                  {"Account":"b7891001","WagersId":"R1","GameId":10001,"WagersTime":"2026-10-01T08:00:00.000",
                   "BetAmount":1.00,"PayoffAmount":1.50,"Status":1,"SettlementTime":"2026-10-01T08:00:01.000",
                   "GameCategoryId":1,"ValidAmount":1.00,"Currency":"PHP"},
                  {"Account":"b7891002","WagersId":"R2","GameId":10001,"WagersTime":"2026-10-01T08:00:00",
                   "BetAmount":2.00,"PayoffAmount":null,"Status":null,"Currency":"RMB"},
                  {"Account":"","WagersId":"R3","BetAmount":1}
                ],"Pagination":{"CurrentPage":1,"TotalPages":2}}}
                """;
        List<ProviderBetRecordView> records = YgrAdapter.parseRecords(JsonUtils.mapper().readTree(json), "YGR");

        assertThat(records).hasSize(2);
        assertThat(records.getFirst().roundId()).isEqualTo("R1");
        assertThat(records.getFirst().gameCode()).isEqualTo("10001");
        assertThat(records.getFirst().payoutAmount()).isEqualByComparingTo("2.50");
        assertThat(records.getFirst().status()).isEqualTo("SETTLED");
        assertThat(records.getFirst().betTime()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(records.get(1).status()).isEqualTo("OPEN");
        assertThat(records.get(1).currency()).isEqualTo("CNY");
        assertThat(records.get(1).settleTime()).isNull();
    }

    @Test
    void launchResponseCarriesTheGameUrl() {
        assertThat(YgrAdapter.launchUrl("{\"ErrorCode\":0,\"Data\":{\"Url\":\"https://game.example/x\"}}")).isEqualTo("https://game.example/x");
        assertThat(YgrAdapter.launchUrl("https://game.example/y")).isEqualTo("https://game.example/y");
        assertThatThrownBy(() -> YgrAdapter.launchUrl("{\"ErrorCode\":5,\"Message\":\"bad\"}")).isInstanceOf(IllegalStateException.class);
    }

    private static CallbackRequest post(String action, String authorization, String body) {
        return AdapterTests.post("YGR", action, Map.of("Authorization", authorization), body);
    }

    private static JsonNode body(CallbackResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        return JsonUtils.mapper().readTree(response.body());
    }

    private static String code(CallbackResponse response) {
        return body(response).path("status").path("code").asString();
    }
}

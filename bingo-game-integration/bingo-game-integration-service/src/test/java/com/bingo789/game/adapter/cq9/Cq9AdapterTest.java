package com.bingo789.game.adapter.cq9;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Cq9AdapterTest {

    private static final String WTOKEN = "test-wtoken";
    private static final String TIME = "2026-10-01T08:00:00-04:00";

    private final Cq9Adapter adapter = new Cq9Adapter();
    private final ProviderClient client = AdapterTests.client("CQ9", null, WTOKEN, Map.of("apiToken", "api"), Map.of());

    @Test
    void wtokenHeaderIsTheCredential() {
        adapter.verifySignature(AdapterTests.post("CQ9", "transaction/game/bet", Map.of("wtoken", WTOKEN), ""), client);

        assertThatThrownBy(() -> adapter.verifySignature(
                AdapterTests.post("CQ9", "transaction/game/bet", Map.of("wtoken", WTOKEN + "x"), ""), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> adapter.verifySignature(AdapterTests.post("CQ9", "transaction/game/bet", ""), client))
                .isInstanceOf(CallbackException.class);
    }

    @Test
    void betAndRolloutAreBetsKeyedByMtcode() {
        for (String action : List.of("transaction/game/bet", "transaction/game/rollout")) {
            WalletCommand command = adapter.parse(form(action, Map.of("account", "b7891001", "eventTime", TIME,
                    "gamehall", "cq9", "gamecode", "GB1", "roundid", "r-1", "amount", "2.50", "mtcode", "m-1")), client);

            assertThat(command).isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> {
                assertThat(bet.playerId()).isEqualTo("b7891001");
                assertThat(bet.txnId()).isEqualTo("m-1");
                assertThat(bet.roundId()).isEqualTo("r-1");
                assertThat(bet.gameCode()).isEqualTo("GB1");
                assertThat(bet.amount()).isEqualByComparingTo("2.50");
                assertThat(bet.currency()).isNull();
            });
        }
    }

    @Test
    void endRoundPaysEveryDataItemAndClosesTheRoundWithTheLast() {
        String data = "[{\"mtcode\":\"w-1\",\"amount\":1.5,\"eventtime\":\"" + TIME + "\"},"
                + "{\"mtcode\":\"w-2\",\"amount\":0,\"eventtime\":\"" + TIME + "\"}]";
        WalletCommand command = adapter.parse(form("transaction/game/endround", Map.of("account", "b7891001",
                "gamehall", "cq9", "gamecode", "GB1", "roundid", "r-1", "data", data, "createTime", TIME)), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> {
            assertThat(batch.steps()).hasSize(2);
            WalletCommand.Payout first = (WalletCommand.Payout) batch.steps().get(0);
            WalletCommand.Payout last = (WalletCommand.Payout) batch.steps().get(1);
            assertThat(first.txnId()).isEqualTo("w-1");
            assertThat(first.amount()).isEqualByComparingTo("1.5");
            assertThat(first.payoutType()).isEqualTo(TxnType.PAYOUT);
            assertThat(first.roundClosed()).isFalse();
            assertThat(last.txnId()).isEqualTo("w-2");
            assertThat(last.roundClosed()).isTrue();
        });
    }

    @Test
    void endRoundDataMayBeEscapedAndFreeTicketWinsNeedNoBet() {
        WalletCommand command = adapter.parse(form("transaction/game/endround", Map.of("account", "b7891001",
                "roundid", "r-2", "data", "[{\\\"mtcode\\\":\\\"w-9\\\",\\\"amount\\\":\\\"3\\\"}]", "freeticket", "true")), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Payout.class, payout -> {
            assertThat(payout.txnId()).isEqualTo("w-9");
            assertThat(payout.payoutType()).isEqualTo(TxnType.FREE_PAYOUT);
            assertThat(payout.roundClosed()).isTrue();
        });
        assertThatThrownBy(() -> Cq9Adapter.endRoundData("[]")).isInstanceOf(CallbackException.class);
    }

    @Test
    void refundCreditDebitRollinAndPayoffMapping() {
        assertThat(adapter.parse(form("transaction/game/refund", Map.of("account", "b7891001", "mtcode", "m-1")), client))
                .isInstanceOfSatisfying(WalletCommand.Rollback.class, r -> {
                    assertThat(r.rollbackTxnId()).isEqualTo("refund:m-1");
                    assertThat(r.targetTxnId()).isEqualTo("m-1");
                    assertThat(r.targetType()).isEqualTo(TxnType.BET);
                });
        assertThat(adapter.parse(form("transaction/game/debit", Map.of("account", "b7891001", "roundid", "r-1",
                "amount", "1.25", "mtcode", "d-1", "eventTime", TIME)), client))
                .isInstanceOfSatisfying(WalletCommand.Adjust.class, a -> assertThat(a.signedAmount()).isEqualByComparingTo("-1.25"));
        assertThat(adapter.parse(form("transaction/game/credit", Map.of("account", "b7891001", "roundid", "r-1",
                "amount", "1.25", "mtcode", "c-1")), client))
                .isInstanceOfSatisfying(WalletCommand.Adjust.class, a -> assertThat(a.signedAmount()).isEqualByComparingTo("1.25"));
        assertThat(adapter.parse(form("transaction/game/rollin", Map.of("account", "b7891001", "roundid", "r-3",
                "amount", "8", "mtcode", "ri-1", "bet", "10", "win", "-2")), client))
                .isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
                    assertThat(p.roundId()).isEqualTo("r-3");
                    assertThat(p.betTxnId()).isNull();
                    assertThat(p.amount()).isEqualByComparingTo("8");
                });
        assertThat(adapter.parse(form("transaction/user/payoff", Map.of("account", "b7891001", "amount", "5",
                "mtcode", "p-1", "promoid", "promo7")), client))
                .isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
                    assertThat(p.roundId()).isEqualTo("promo:promo7");
                    assertThat(p.payoutType()).isEqualTo(TxnType.PROMO_PAYOUT);
                });
    }

    @Test
    void takeAllTakesTheWholeBalanceAndAnswersTheAmountTaken() {
        CallbackRequest request = form("transaction/game/takeall", Map.of("account", "b7891001", "eventTime", TIME,
                "gamehall", "cq9", "gamecode", "AB3", "roundid", "r-ta", "mtcode", "t-1"));
        WalletCommand command = adapter.parse(request, client);

        assertThat(command).isEqualTo(new WalletCommand.TakeAll("b7891001", null, "t-1", "r-ta", "AB3"));
        CommandOutcome taken = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", new BigDecimal("0.0040"), 1L,
                false, null, new BigDecimal("600210.1200"), List.of());
        JsonNode ok = json(adapter.render(request, command, taken, client));
        assertThat(ok.path("status").path("code").asString()).isEqualTo("0");
        assertThat(ok.path("data").path("amount").decimalValue()).isEqualByComparingTo("600210.12");
        assertThat(ok.path("data").path("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(ok.path("data").path("currency").asString()).isEqualTo("PHP");
        assertThat(code(adapter.render(request, command, outcome(CommandOutcome.Code.INSUFFICIENT_FUNDS), client))).isEqualTo("1005");
    }

    @Test
    void unknownActionsAreRefused() {
        assertThatThrownBy(() -> adapter.parse(form("transaction/game/takesome", Map.of("account", "b7891001",
                "mtcode", "t-1", "roundid", "r")), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.UNKNOWN_ACTION));
        assertThat(code(adapter.renderError(form("transaction/game/takesome", Map.of()), CallbackError.UNKNOWN_ACTION))).isEqualTo("1002");
    }

    @Test
    void badTimeIsAnsweredWithTheTimeFormatCode() {
        CallbackRequest request = form("transaction/game/bet", Map.of("account", "b7891001", "eventTime", "2026/10/01",
                "roundid", "r", "amount", "1", "mtcode", "m"));
        assertThatThrownBy(() -> adapter.parse(request, client)).isInstanceOf(CallbackException.class);

        assertThat(code(adapter.renderError(request, CallbackError.BAD_REQUEST))).isEqualTo("1004");
        assertThat(code(adapter.renderError(form("transaction/game/bet", Map.of("amount", "0")), CallbackError.BAD_REQUEST))).isEqualTo("1003");
    }

    @Test
    void checkPlayerAnswersTrueOrFalse() {
        CallbackRequest request = AdapterTests.get("CQ9", "player/check/b7891001", null);
        WalletCommand command = adapter.parse(request, client);
        assertThat(command).isEqualTo(new WalletCommand.GetBalance("b7891001", null));

        JsonNode known = json(adapter.render(request, command, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.ONE), client));
        JsonNode unknown = json(adapter.render(request, command, CommandOutcome.of(CommandOutcome.Code.PLAYER_NOT_FOUND, "b7891001", "PHP", null), client));
        assertThat(known.path("data").asBoolean()).isTrue();
        assertThat(unknown.path("data").asBoolean()).isFalse();
        assertThat(unknown.path("status").path("code").asString()).isEqualTo("0");
    }

    @Test
    void outcomesAreRenderedWithCq9Codes() {
        CallbackRequest request = form("transaction/game/bet", Map.of());
        WalletCommand bet = new WalletCommand.Bet("b7891001", null, "m", "r", "g", BigDecimal.ONE, false);

        JsonNode ok = json(adapter.render(request, bet, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", new BigDecimal("12.3456")), client));
        assertThat(ok.path("status").path("code").asString()).isEqualTo("0");
        assertThat(ok.path("data").path("balance").decimalValue()).isEqualByComparingTo("12.34");
        assertThat(ok.path("data").path("currency").asString()).isEqualTo("PHP");
        assertThat(ok.path("status").path("datetime").asString()).endsWith("-04:00");

        CommandOutcome duplicate = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 1L, true, null);
        assertThat(code(adapter.render(request, bet, duplicate, client))).isEqualTo("0");
        assertThat(code(adapter.render(request, bet, outcome(CommandOutcome.Code.INSUFFICIENT_FUNDS), client))).isEqualTo("1005");
        assertThat(code(adapter.render(request, bet, outcome(CommandOutcome.Code.TXN_CANCELLED), client))).isEqualTo("1005");
        assertThat(code(adapter.render(request, bet, outcome(CommandOutcome.Code.PLAYER_NOT_FOUND), client))).isEqualTo("1006");
        assertThat(code(adapter.render(request, bet, outcome(CommandOutcome.Code.BET_NOT_FOUND), client))).isEqualTo("1014");
        WalletCommand refund = new WalletCommand.Rollback("b7891001", null, "refund:m", "m", TxnType.BET, null, null);
        assertThat(code(adapter.render(request, refund, outcome(CommandOutcome.Code.TXN_NOT_FOUND), client))).isEqualTo("0");
        assertThat(json(adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE)).path("data").isNull()).isTrue();
        assertThat(code(adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE))).isEqualTo("1100");
        assertThat(code(adapter.renderError(request, CallbackError.AUTH_FAILED))).isEqualTo("1003");
    }

    @Test
    void historyRowsAreRecords() {
        JsonNode rows = JsonUtils.mapper().readTree("""
                [
                  {"gametype":"slot","gamehall":"cq9","gamecode":"GB1","account":"b7891001","round":"R1","balance":10,
                   "win":2.5,"bet":1,"validbet":1,"rake":0,"status":"complete","currency":"PHP",
                   "bettime":"2026-10-01T08:00:00-04:00","endroundtime":"2026-10-01T08:00:05-04:00","createtime":"2026-10-01T08:00:06-04:00"},
                  {"gametype":"table","gamecode":"AB3","account":"b7891002","round":"R2","win":0,"bet":5,"status":"running",
                   "currency":"PHP","bettime":"2026-10-01T08:01:00-04:00","createtime":"2026-10-01T08:01:00-04:00"},
                  {"gametype":"slot","gamecode":"GB1","account":"other1","round":"R3","win":0,"bet":1,"status":"complete",
                   "currency":"PHP","bettime":"2026-10-01T08:02:00-04:00","createtime":"2026-10-01T08:02:00-04:00"}
                ]""");

        List<ProviderBetRecordView> records = Cq9Adapter.parseRecords(rows, "CQ9", false);

        assertThat(records).extracting(ProviderBetRecordView::providerBetId).containsExactly("R1", "R2");
        ProviderBetRecordView settled = records.getFirst();
        assertThat(settled.userId()).isEqualTo(1001L);
        assertThat(settled.betAmount()).isEqualByComparingTo("1");
        assertThat(settled.payoutAmount()).isEqualByComparingTo("2.5");
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.betTime()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
        assertThat(settled.settleTime()).isEqualTo(Instant.parse("2026-10-01T12:00:06Z"));
        assertThat(records.get(1).status()).isEqualTo("OPEN");
        assertThat(records.get(1).settleTime()).isNull();

        JsonNode lotto = JsonUtils.mapper().readTree("""
                [{"account":"b7891003","roundid":"L1","bets":10,"wins":null,"currency":"PHP","bettime":"2026-10-01T08:00:00-04:00"},
                 {"account":"b7891003","roundid":"L2","betamount":10,"wins":25,"currency":"PHP",
                  "bettime":"2026-10-01T08:00:00-04:00","finaltime":"2026-10-01T09:00:00-04:00"}]""");
        List<ProviderBetRecordView> lottoRecords = Cq9Adapter.parseRecords(lotto, "CQ9", true);
        assertThat(lottoRecords).extracting(ProviderBetRecordView::status).containsExactly("OPEN", "SETTLED");
        assertThat(lottoRecords.get(1).betAmount()).isEqualByComparingTo("10");
        assertThat(lottoRecords.get(1).payoutAmount()).isEqualByComparingTo("25");
    }

    @Test
    void languagesAreCq9Codes() {
        assertThat(Cq9Adapter.language(null)).isEqualTo("en");
        assertThat(Cq9Adapter.language("zh_CN")).isEqualTo("zh-cn");
        assertThat(Cq9Adapter.language("vi")).isEqualTo("vn");
        assertThat(Cq9Adapter.language("th-TH")).isEqualTo("th");
    }

    private static CommandOutcome outcome(CommandOutcome.Code code) {
        return CommandOutcome.of(code, "b7891001", "PHP", null);
    }

    private static CallbackRequest form(String action, Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        new LinkedHashMap<>(fields).forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return AdapterTests.post("CQ9", action, Map.of("wtoken", WTOKEN), body.toString());
    }

    private static JsonNode json(CallbackResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        return JsonUtils.mapper().readTree(response.body());
    }

    private static String code(CallbackResponse response) {
        return json(response).path("status").path("code").asString();
    }
}

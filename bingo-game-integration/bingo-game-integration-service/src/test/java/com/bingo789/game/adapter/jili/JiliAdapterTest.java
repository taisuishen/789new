package com.bingo789.game.adapter.jili;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.CommandOutcome.Code;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JiliAdapterTest {

    private static final String AGENT = "agent-1";
    private static final String AGENT_KEY = "agentKey";
    private static final String BIG_ROUND = "17238050501001102002";

    private final JiliAdapter adapter = new JiliAdapter();
    private final ProviderClient client = AdapterTests.client("JILI", AGENT, AGENT_KEY, Map.of(), Map.of());

    @Test
    void keySignsTheMandatoryFieldsWithTheUtcMinus4Date() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        // days 1-9 are signed without the leading zero
        String keyG = Ciphers.md5Hex("26101" + AGENT + AGENT_KEY);
        assertThat(JiliAdapter.keyG(AGENT, AGENT_KEY, day)).isEqualTo(keyG);
        assertThat(JiliAdapter.keyG(AGENT, AGENT_KEY, LocalDate.of(2026, 10, 15))).isEqualTo(Ciphers.md5Hex("261015" + AGENT + AGENT_KEY));

        String signed = "StartTime=2026-10-01T00:00:00&EndTime=2026-10-01T00:30:00&Page=1&PageLimit=5000";
        String key = JiliAdapter.key(signed, AGENT, AGENT_KEY, day);
        assertThat(key).hasSize(6 + 32 + 6);
        assertThat(key.substring(6, 38)).isEqualTo(Ciphers.md5Hex(signed + "&AgentId=" + AGENT + keyG));
        // a tampered field or another key gives another signature
        assertThat(JiliAdapter.key(signed.replace("Page=1", "Page=2"), AGENT, AGENT_KEY, day).substring(6, 38))
                .isNotEqualTo(key.substring(6, 38));
        assertThat(JiliAdapter.key(signed, AGENT, "otherKey", day).substring(6, 38)).isNotEqualTo(key.substring(6, 38));
        // no mandatory fields: only AgentId is signed
        assertThat(JiliAdapter.key("", AGENT, AGENT_KEY, day).substring(6, 38)).isEqualTo(Ciphers.md5Hex("AgentId=" + AGENT + keyG));
    }

    @Test
    void authPresentsTheGameToken() {
        WalletCommand command = adapter.parse(post("auth", "{\"reqId\":\"r1\",\"token\":\"tok-1\"}"), client);

        assertThat(command).isEqualTo(new WalletCommand.Authenticate("tok-1", null));
    }

    @Test
    void betIsOneBetAndPayoutOnTheTokenKeyedByTheRoundAsText() {
        WalletCommand command = adapter.parse(post("bet", """
                {"reqId":"r1","token":"tok-1","currency":"PHP","game":80,"round":%s,"wagersTime":1759300000,
                 "betAmount":10,"winloseAmount":1.5,"userId":"b7891001","statementType":1}""".formatted(BIG_ROUND)), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Session.class, session -> {
            assertThat(session.token()).isEqualTo("tok-1");
            assertThat(session.command()).isInstanceOfSatisfying(WalletCommand.BetAndPayout.class, bp -> {
                assertThat(bp.playerId()).isNull();
                assertThat(bp.currency()).isEqualTo("PHP");
                assertThat(bp.betTxnId()).isEqualTo(BIG_ROUND);
                assertThat(bp.payoutTxnId()).isEqualTo(BIG_ROUND);
                assertThat(bp.roundId()).isEqualTo(BIG_ROUND);
                assertThat(bp.gameCode()).isEqualTo("80");
                assertThat(bp.betAmount()).isEqualByComparingTo("10");
                assertThat(bp.payoutAmount()).isEqualByComparingTo("1.5");
                assertThat(bp.roundClosed()).isTrue();
            });
        });
    }

    @Test
    void stakelessJackpotIsAJackpotPayoutOfTheUser() {
        WalletCommand command = adapter.parse(post("bet", """
                {"reqId":"r2","token":"tok-1","currency":"PHP","game":80,"round":55,"wagersTime":1759300000,
                 "betAmount":0,"winloseAmount":500,"statementType":36,"userId":"b7891001"}"""), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
            assertThat(p.playerId()).isEqualTo("b7891001");
            assertThat(p.payoutType()).isEqualTo(TxnType.JACKPOT_PAYOUT);
            assertThat(p.txnId()).isEqualTo("55");
            assertThat(p.amount()).isEqualByComparingTo("500");
        });
    }

    @Test
    void offlineDrawWithAStakeAndNegativeAmountsAreBadRequests() {
        assertThatThrownBy(() -> adapter.parse(post("bet", """
                {"token":"t","currency":"PHP","game":80,"round":1,"betAmount":1,"winloseAmount":0,"isFreeRound":true}"""), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));
        assertThatThrownBy(() -> adapter.parse(post("bet", """
                {"token":"t","currency":"PHP","game":80,"round":1,"betAmount":-1,"winloseAmount":0}"""), client))
                .isInstanceOf(CallbackException.class);
        assertThatThrownBy(() -> adapter.parse(post("somethingElse", "{}"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.UNKNOWN_ACTION));
    }

    @Test
    void cancelBetReversesPayoutThenBetOfTheUsersRound() {
        WalletCommand command = adapter.parse(post("cancelBet", """
                {"reqId":"r3","currency":"PHP","game":80,"round":77,"betAmount":10,"winloseAmount":0,
                 "userId":"b7891001","token":"expired-token"}"""), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> {
            assertThat(batch.playerId()).isEqualTo("b7891001");
            assertThat(batch.steps()).hasSize(2);
            WalletCommand.Rollback win = (WalletCommand.Rollback) batch.steps().get(0);
            WalletCommand.Rollback bet = (WalletCommand.Rollback) batch.steps().get(1);
            assertThat(bet.targetTxnId()).isEqualTo("77");
            assertThat(bet.targetType()).isEqualTo(TxnType.BET);
            assertThat(bet.rollbackTxnId()).isEqualTo("cancel:77");
            assertThat(win.targetTxnId()).isEqualTo("77");
            assertThat(win.targetType()).isEqualTo(TxnType.PAYOUT);
        });
    }

    @Test
    void sessionBetDebitsTheReserveAndSettlesItsUnusedPartWithTheWin() {
        WalletCommand bet = adapter.parse(post("sessionBet", """
                {"reqId":"r4","token":"tok-1","currency":"PHP","game":301,"round":11,"wagersTime":1759300000,
                 "betAmount":0,"winloseAmount":0,"sessionId":900,"type":1,"preserve":50}"""), client);
        assertThat(bet).isInstanceOfSatisfying(WalletCommand.Session.class, s ->
                assertThat(s.command()).isInstanceOfSatisfying(WalletCommand.Bet.class, b -> {
                    assertThat(b.txnId()).isEqualTo("900_11");
                    assertThat(b.roundId()).isEqualTo("900");
                    assertThat(b.amount()).isEqualByComparingTo("50");
                    assertThat(b.roundClosed()).isFalse();
                }));

        WalletCommand settle = adapter.parse(post("sessionBet", """
                {"reqId":"r5","token":"tok-1","currency":"PHP","game":301,"round":12,"wagersTime":1759300000,
                 "betAmount":30,"winloseAmount":45,"sessionId":900,"type":2,"preserve":50,"userId":"b7891001","turnover":30}"""), client);
        assertThat(settle).isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
            assertThat(p.playerId()).isEqualTo("b7891001");
            assertThat(p.txnId()).isEqualTo("900_12");
            assertThat(p.roundId()).isEqualTo("900");
            assertThat(p.amount()).isEqualByComparingTo("65"); // 50 - 30 + 45
            assertThat(p.roundClosed()).isTrue();
        });
    }

    @Test
    void negativeSessionSettlementIsAnAdjustThenAZeroPayout() {
        WalletCommand settle = adapter.parse(post("sessionBet", """
                {"token":"tok-1","currency":"PHP","game":301,"round":13,"betAmount":60,"winloseAmount":0,
                 "sessionId":901,"type":2,"preserve":50,"userId":"b7891001"}"""), client);

        assertThat(settle).isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> {
            assertThat(((WalletCommand.Adjust) batch.steps().get(0)).signedAmount()).isEqualByComparingTo("-10");
            assertThat(((WalletCommand.Payout) batch.steps().get(1)).amount()).isEqualByComparingTo("0");
        });
    }

    @Test
    void cancelSessionBetRollsBackThatBet() {
        WalletCommand command = adapter.parse(post("cancelSessionBet", """
                {"reqId":"r6","currency":"PHP","game":301,"round":11,"betAmount":0,"winloseAmount":0,
                 "userId":"b7891001","token":"tok-1","sessionId":900,"type":1,"preserve":50}"""), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Rollback.class, r -> {
            assertThat(r.targetTxnId()).isEqualTo("900_11");
            assertThat(r.targetType()).isEqualTo(TxnType.BET);
            assertThat(r.roundId()).isEqualTo("900");
        });
    }

    @Test
    void successEchoesTheTokenAndRoundsTheBalanceDown() {
        CallbackRequest request = post("bet", "{\"token\":\"tok-1\"}");
        JsonNode body = body(adapter.render(request, null,
                new CommandOutcome(Code.SUCCESS, "b7891001", "PHP", new BigDecimal("12.349"), 42L, false, null), client));

        assertThat(body.path("errorCode").asInt()).isZero();
        assertThat(body.path("token").asString()).isEqualTo("tok-1");
        assertThat(body.path("username").asString()).isEqualTo("b7891001");
        assertThat(body.path("balance").decimalValue()).isEqualByComparingTo("12.34");
        assertThat(body.path("txId").asLong()).isEqualTo(42L);
    }

    @Test
    void failuresAreRenderedWithJiliCodes() {
        CallbackRequest bet = post("bet", "{\"token\":\"tok-1\"}");
        CallbackRequest cancel = post("cancelBet", "{\"token\":\"tok-1\"}");
        assertThat(code(adapter.render(bet, null, new CommandOutcome(Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 1L, true, null), client))).isEqualTo(1);
        assertThat(code(adapter.render(bet, null, outcome(Code.INSUFFICIENT_FUNDS), client))).isEqualTo(2);
        assertThat(code(adapter.render(cancel, null, outcome(Code.INSUFFICIENT_FUNDS), client))).isEqualTo(6);
        assertThat(code(adapter.render(cancel, null, outcome(Code.TXN_NOT_FOUND), client))).isEqualTo(2);
        assertThat(code(adapter.render(bet, null, outcome(Code.INVALID_REQUEST), client))).isEqualTo(3);

        JsonNode invalidToken = body(adapter.render(bet, null, outcome(Code.INVALID_TOKEN), client));
        assertThat(invalidToken.path("errorCode").asInt()).isEqualTo(4);
        assertThat(invalidToken.has("token")).isFalse();

        assertThat(code(adapter.renderError(bet, CallbackError.SYSTEM_RETRYABLE))).isEqualTo(5);
        assertThat(code(adapter.renderError(bet, CallbackError.BAD_REQUEST))).isEqualTo(3);
    }

    @Test
    void betHistoryMapsSettledAndOpenWagers() {
        String json = """
                {"ErrorCode":0,"Message":"","Data":{"Result":[
                  {"Account":"b7891001","WagersId":%s,"WagersTime":"2026-10-01T02:20:31-04:00",
                   "SettlementTime":"2026-10-01T02:20:33-04:00","Status":1,"BetAmount":-10,"Turnover":10,
                   "PayoffAmount":25.5,"GameId":80,"GameCategoryId":1,"Type":1},
                  {"Account":"b7891002","WagersId":"2","WagersTime":"2026-10-01T02:21:00-04:00","SettlementTime":null,
                   "Status":null,"BetAmount":5,"PayoffAmount":null,"GameId":80},
                  {"Account":"x-unknown","WagersId":"3","WagersTime":"2026-10-01T02:22:00-04:00","Status":2,
                   "BetAmount":1,"PayoffAmount":0,"GameId":80}
                ]}}""".formatted(BIG_ROUND);

        JiliAdapter.HistoryPage page = JiliAdapter.parseHistory(json, false, "PHP", "JILI");

        assertThat(page.rows()).isEqualTo(3);
        assertThat(page.records()).extracting(ProviderBetRecordView::providerBetId).containsExactly(BIG_ROUND, "2");
        ProviderBetRecordView settled = page.records().getFirst();
        assertThat(settled.userId()).isEqualTo(1001L);
        assertThat(settled.roundId()).isEqualTo(BIG_ROUND);
        assertThat(settled.currency()).isEqualTo("PHP");
        assertThat(settled.gameCode()).isEqualTo("80");
        assertThat(settled.betAmount()).isEqualByComparingTo("10");
        assertThat(settled.payoutAmount()).isEqualByComparingTo("25.5");
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.betTime()).isEqualTo(Instant.parse("2026-10-01T06:20:31Z"));
        assertThat(settled.settleTime()).isEqualTo(Instant.parse("2026-10-01T06:20:33Z"));
        ProviderBetRecordView open = page.records().get(1);
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.settleTime()).isNull();
        assertThat(open.payoutAmount()).isEqualByComparingTo("0");
    }

    @Test
    void freeSpinHistoryKeepsFreeGamesOnlyAndTreatsCode101AsEmpty() {
        String json = """
                {"ErrorCode":0,"Data":[
                  {"Account":"b7891001","WagersId":"9","Type":19,"BetAmount":1,"PayoffAmount":3,"GameId":80,
                   "WagersTime":"2026-10-01T02:20:31-04:00","SettlementTime":"2026-10-01T02:20:31-04:00","Currency":"PHP"},
                  {"Account":"b7891001","WagersId":"10","Type":27,"BetAmount":1,"PayoffAmount":2,"GameId":80,
                   "WagersTime":"2026-10-01T02:20:31-04:00","SettlementTime":"2026-10-01T02:20:31-04:00"}
                ]}""";

        JiliAdapter.HistoryPage page = JiliAdapter.parseHistory(json, true, "PHP", "JILI");

        assertThat(page.rows()).isEqualTo(2);
        assertThat(page.records()).singleElement().satisfies(r -> {
            assertThat(r.providerBetId()).isEqualTo("9");
            assertThat(r.betAmount()).isEqualByComparingTo("0");
            assertThat(r.payoutAmount()).isEqualByComparingTo("3");
            assertThat(r.status()).isEqualTo("SETTLED");
        });
        assertThat(JiliAdapter.parseHistory("{\"ErrorCode\":101,\"Message\":\"no data\"}", true, "PHP", "JILI").records()).isEmpty();
        assertThatThrownBy(() -> JiliAdapter.parseHistory("{\"ErrorCode\":2,\"Message\":\"Invalid Key\"}", false, "PHP", "JILI"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static CallbackRequest post(String action, String json) {
        return AdapterTests.post("JILI", action, json);
    }

    private static CommandOutcome outcome(Code code) {
        return CommandOutcome.of(code, "b7891001", "PHP", BigDecimal.TEN);
    }

    private static JsonNode body(CallbackResponse response) {
        return JsonUtils.mapper().readTree(response.body());
    }

    private static int code(CallbackResponse response) {
        return body(response).path("errorCode").asInt();
    }
}

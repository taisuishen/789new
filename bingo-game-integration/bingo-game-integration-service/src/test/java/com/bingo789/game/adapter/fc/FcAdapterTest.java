package com.bingo789.game.adapter.fc;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FcAdapterTest {

    private static final String KEY = "0123456789abcdef";
    private static final String AGENT = "agent1";

    private final FcAdapter adapter = new FcAdapter();
    private final ProviderClient client = AdapterTests.client("FC", AGENT, KEY, Map.of(), Map.of());

    @Test
    void paramsDecryptAndMustMatchTheSign() {
        String json = "{\"MemberAccount\":\"b7891001\",\"Currency\":\"PHP\",\"GameID\":22016,\"Ts\":" + now() + "}";
        assertThat(FcAdapter.open(FcAdapter.seal(json, KEY), KEY)).isEqualTo(json);
        assertThat(adapter.parse(signed("GetBalance", json), client)).isEqualTo(new WalletCommand.GetBalance("b7891001", "PHP"));

        String other = json.replace("b7891001", "b7891002");
        CallbackRequest wrongSign = form("GetBalance", AGENT, FcAdapter.seal(other, KEY), Ciphers.md5Hex(json));
        CallbackRequest wrongKey = form("GetBalance", AGENT, FcAdapter.seal(json, "fedcba9876543210"), Ciphers.md5Hex(json));
        CallbackRequest wrongAgent = form("GetBalance", "agent2", FcAdapter.seal(json, KEY), Ciphers.md5Hex(json));
        CallbackRequest garbage = form("GetBalance", AGENT, "bm90IGNpcGhlcnRleHQ=", Ciphers.md5Hex(json));
        for (CallbackRequest tampered : List.of(wrongSign, wrongKey, wrongAgent, garbage)) {
            assertThatThrownBy(() -> adapter.parse(tampered, client))
                    .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        }
    }

    @Test
    void staleTsIsRefusedForDebitsButNotForSettlements() {
        long stale = now() - 3_600_000;
        String bet = "{\"MemberAccount\":\"b7891001\",\"Currency\":\"PHP\",\"GameID\":22016,\"Ts\":" + stale
                + ",\"RecordID\":\"rec-1\",\"BetID\":\"bet-1\",\"GameType\":2,\"Bet\":5}";
        assertThatThrownBy(() -> adapter.parse(signed("Bet", bet), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));

        String settle = "{\"MemberAccount\":\"b7891001\",\"Currency\":\"PHP\",\"GameID\":22016,\"Ts\":" + stale
                + ",\"RecordID\":\"rec-1\",\"BankID\":\"bank-1\",\"Win\":3,\"Refund\":0,\"SettleBetIDs\":[{\"betID\":\"bet-1\",\"win\":3}]}";
        assertThat(adapter.parse(signed("Settle", settle), client)).isInstanceOf(WalletCommand.Payout.class);

        // seconds work as well as milliseconds
        String secondsTs = bet.replace(String.valueOf(stale), String.valueOf(now() / 1000));
        assertThat(adapter.parse(signed("Bet", secondsTs), client)).isInstanceOf(WalletCommand.Bet.class);
    }

    @Test
    void betNInfoIsBetAndPayoutKeyedByBankId() {
        String json = "{\"MemberAccount\":\"b7891001\",\"Currency\":\"PHP\",\"GameID\":22016,\"Ts\":" + now()
                + ",\"RecordID\":\"rec-1\",\"BankID\":\"bank-1\",\"GameType\":2,\"Bet\":10,\"Win\":25.5,\"NetWin\":15.5}";

        assertThat(adapter.parse(signed("BetNInfo", json), client)).isInstanceOfSatisfying(WalletCommand.BetAndPayout.class, bp -> {
            assertThat(bp.betTxnId()).isEqualTo("bank-1");
            assertThat(bp.payoutTxnId()).isEqualTo("bank-1");
            assertThat(bp.roundId()).isEqualTo("rec-1");
            assertThat(bp.gameCode()).isEqualTo("22016");
            assertThat(bp.betAmount()).isEqualByComparingTo("10");
            assertThat(bp.payoutAmount()).isEqualByComparingTo("25.5");
            assertThat(bp.roundClosed()).isTrue();
        });
    }

    @Test
    void settlePaysEachBetAndTheRefund() {
        String json = "{\"MemberAccount\":\"b7891001\",\"Currency\":\"PHP\",\"GameID\":28001,\"Ts\":" + now()
                + ",\"RecordID\":\"rec-2\",\"BankID\":\"bank-2\",\"Bet\":20,\"Win\":30,\"Refund\":5,"
                + "\"SettleBetIDs\":[{\"betID\":\"b-1\",\"bet\":10,\"win\":30},{\"betID\":\"b-2\",\"bet\":10,\"win\":0}]}";

        assertThat(adapter.parse(signed("Settle", json), client)).isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> {
            assertThat(batch.steps()).hasSize(3);
            List<WalletCommand.Payout> payouts = batch.steps().stream().map(WalletCommand.Payout.class::cast).toList();
            assertThat(payouts).extracting(WalletCommand.Payout::txnId).containsExactly("b-1", "b-2", "refund:bank-2");
            assertThat(payouts.get(0).betTxnId()).isEqualTo("b-1");
            assertThat(payouts.get(0).amount()).isEqualByComparingTo("30");
            assertThat(payouts.get(2).amount()).isEqualByComparingTo("5");
            assertThat(payouts).extracting(WalletCommand.Payout::roundClosed).containsExactly(false, false, true);
        });
    }

    @Test
    void cancelsReverseTheBetAndItsPayout() {
        String json = "{\"MemberAccount\":\"b7891001\",\"Currency\":\"PHP\",\"GameID\":22016,\"Ts\":" + now()
                + ",\"BankID\":\"bank-1\",\"BetID\":\"bet-1\",\"RecordID\":\"rec-1\",\"Bet\":5}";

        for (String action : List.of("CancelBetNInfo", "CancelBet")) {
            assertThat(adapter.parse(signed(action, json), client)).isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> {
                // the payout first: the wallet refuses to refund a paid-out bet
                WalletCommand.Rollback win = (WalletCommand.Rollback) batch.steps().get(0);
                WalletCommand.Rollback bet = (WalletCommand.Rollback) batch.steps().get(1);
                String id = action.equals("CancelBet") ? "bet-1" : "bank-1";
                assertThat(bet.targetTxnId()).isEqualTo(id);
                assertThat(bet.targetType()).isEqualTo(TxnType.BET);
                assertThat(win.targetTxnId()).isEqualTo(id);
                assertThat(win.targetType()).isEqualTo(TxnType.PAYOUT);
            });
        }
    }

    @Test
    void promoAndFreeSpinWinsAreCredited() {
        String event = "{\"Currency\":\"PHP\",\"Ts\":" + now() + ",\"List\":["
                + "{\"eventID\":\"ev-1\",\"memberAccount\":\"b7891001\",\"gameID\":22016,\"bankID\":\"bk-1\",\"trsID\":\"trs-1\",\"points\":8},"
                + "{\"eventID\":\"ev-1\",\"memberAccount\":\"b7891001\",\"gameID\":22016,\"bankID\":\"bk-2\",\"trsID\":\"trs-2\",\"points\":2}]}";
        assertThat(adapter.parse(signed("EventSettle", event), client)).isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> {
            assertThat(batch.playerId()).isEqualTo("b7891001");
            WalletCommand.Payout first = (WalletCommand.Payout) batch.steps().getFirst();
            assertThat(first.txnId()).isEqualTo("trs-1");
            assertThat(first.roundId()).isEqualTo("promo:ev-1");
            assertThat(first.payoutType()).isEqualTo(TxnType.PROMO_PAYOUT);
        });
        String twoPlayers = event.replace("\"memberAccount\":\"b7891001\",\"gameID\":22016,\"bankID\":\"bk-2\"",
                "\"memberAccount\":\"b7891002\",\"gameID\":22016,\"bankID\":\"bk-2\"");
        assertThatThrownBy(() -> adapter.parse(signed("EventSettle", twoPlayers), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));

        String freeSpin = "{\"RecordID\":\"rec-9\",\"MemberAccount\":\"b7891001\",\"Currency\":\"PHP\",\"GameID\":22016,"
                + "\"Bet\":1,\"Win\":12,\"Ts\":" + now() + ",\"IsFreeSpin\":true,\"EventID\":\"ev-2\"}";
        assertThat(adapter.parse(signed("FreeSpinBetNInfo", freeSpin), client)).isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
            assertThat(p.txnId()).isEqualTo("rec-9");
            assertThat(p.payoutType()).isEqualTo(TxnType.FREE_PAYOUT);
            assertThat(p.amount()).isEqualByComparingTo("12");
        });
    }

    @Test
    void outcomesAreRenderedWithFcResults() {
        CallbackRequest bet = AdapterTests.post("FC", "Bet", "");
        JsonNode ok = json(adapter.render(bet, null, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", new BigDecimal("1234.567")), client));
        assertThat(ok.path("Result").asInt()).isZero();
        assertThat(ok.path("MainPoints").decimalValue()).isEqualByComparingTo("1234.56");

        CommandOutcome duplicate = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 1L, true, null);
        assertThat(result(adapter.render(bet, null, duplicate, client))).isZero();
        assertThat(result(adapter.render(bet, null, outcome(CommandOutcome.Code.INSUFFICIENT_FUNDS), client))).isEqualTo(203);
        assertThat(result(adapter.render(bet, null, outcome(CommandOutcome.Code.PLAYER_NOT_FOUND), client))).isEqualTo(500);
        assertThat(result(adapter.render(bet, null, outcome(CommandOutcome.Code.BET_NOT_FOUND), client))).isEqualTo(221);
        assertThat(result(adapter.render(AdapterTests.post("FC", "CancelBet", ""), null, outcome(CommandOutcome.Code.TXN_NOT_FOUND), client))).isZero();
        assertThat(result(adapter.render(AdapterTests.post("FC", "CancelBetNInfo", ""), null, outcome(CommandOutcome.Code.TXN_NOT_FOUND), client))).isEqualTo(221);
        assertThat(result(adapter.renderError(bet, CallbackError.SYSTEM_RETRYABLE))).isEqualTo(999);
        assertThat(result(adapter.renderError(bet, CallbackError.AUTH_FAILED))).isEqualTo(999);
    }

    @Test
    void recordsAreSettledRoundsInFcTime() {
        JsonNode response = JsonUtils.mapper().readTree("""
                {"Result":0,"Records":[
                  {"recordID":"R1","account":"b7891001","gameID":22016,"gametype":2,"bet":10,"winlose":-4,"validBet":10,"bdate":"2026-10-01 08:00:00"},
                  {"recordID":"R2","account":"x-unknown","gameID":22016,"gametype":2,"bet":1,"winlose":0,"validBet":1,"bdate":"2026-10-01 08:00:01"}]}""");

        List<ProviderBetRecordView> records = FcAdapter.parseRecords(response, "PHP", "FC");

        assertThat(records).hasSize(1);
        ProviderBetRecordView record = records.getFirst();
        assertThat(record.providerBetId()).isEqualTo("R1");
        assertThat(record.currency()).isEqualTo("PHP");
        assertThat(record.gameCode()).isEqualTo("22016");
        assertThat(record.betAmount()).isEqualByComparingTo("10");
        assertThat(record.payoutAmount()).isEqualByComparingTo("6");
        assertThat(record.betTime()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
    }

    @Test
    void windowsAreAtMostFifteenMinutesAndStopAtTheHistoryBoundary() {
        Instant now = Instant.parse("2026-10-01T12:00:30Z");

        FcAdapter.Window recent = FcAdapter.window(Instant.parse("2026-10-01T11:30:00.500Z"), Instant.parse("2026-10-01T11:58:00Z"), now);
        assertThat(recent.history()).isFalse();
        assertThat(recent.end()).isEqualTo(Instant.parse("2026-10-01T11:45:00.500Z"));
        assertThat(recent.first()).isEqualTo(Instant.parse("2026-10-01T11:30:00Z"));
        assertThat(recent.last()).isEqualTo(Instant.parse("2026-10-01T11:44:59Z"));

        FcAdapter.Window old = FcAdapter.window(Instant.parse("2026-10-01T09:50:00Z"), Instant.parse("2026-10-01T10:20:00Z"), now);
        assertThat(old.history()).isTrue();
        assertThat(old.end()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));

        FcAdapter.Window tiny = FcAdapter.window(Instant.parse("2026-10-01T11:57:59.200Z"), Instant.parse("2026-10-01T11:57:59.800Z"), now);
        assertThat(tiny.requested()).isFalse();
    }

    @Test
    void gameIconListIsParsed() {
        JsonNode response = JsonUtils.mapper().readTree("""
                {"Result":0,"GetGameIconList":{"fishing":{"21003":{"gameNameOfEnglish":"MONKEY KING FISHING","enPng":"https://x/21003.png"}},
                 "slot":{"22016":{"gameNameOfChinese":"x"}}}}""");

        assertThat(FcAdapter.parseGames(response, "FC"))
                .extracting(g -> g.gameCode() + "|" + g.name() + "|" + g.category())
                .containsExactly("21003|MONKEY KING FISHING|fishing", "22016|22016|slot");
        assertThat(FcAdapter.language("zh-CN")).isEqualTo("2");
        assertThat(FcAdapter.language(null)).isEqualTo("1");
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static CommandOutcome outcome(CommandOutcome.Code code) {
        return CommandOutcome.of(code, "b7891001", "PHP", BigDecimal.ONE);
    }

    private static CallbackRequest signed(String action, String json) {
        return form(action, AGENT, FcAdapter.seal(json, KEY), Ciphers.md5Hex(json));
    }

    private static CallbackRequest form(String action, String agent, String params, String sign) {
        return AdapterTests.post("FC", action, "AgentCode=" + agent + "&Currency=PHP&Params="
                + URLEncoder.encode(params, StandardCharsets.UTF_8) + "&Sign=" + sign);
    }

    private static JsonNode json(CallbackResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        return JsonUtils.mapper().readTree(response.body());
    }

    private static int result(CallbackResponse response) {
        return json(response).path("Result").asInt();
    }
}

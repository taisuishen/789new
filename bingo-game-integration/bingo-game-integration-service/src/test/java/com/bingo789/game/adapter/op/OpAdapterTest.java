package com.bingo789.game.adapter.op;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.CommandOutcome.Code;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.wallet.api.enums.TxnType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpAdapterTest {

    private static final String KEY = "0123456789abcdef";
    private static final String IV = "fedcba9876543210";

    private final OpAdapter adapter = new OpAdapter();
    private final ProviderClient client = AdapterTests.client("OP", "parent-1", null,
            Map.of("aesKey", KEY, "aesIv", IV), Map.of("dc", "DC1"));

    @Test
    void encryptedMessagesRoundTripAndTamperingFailsAuthentication() {
        String json = "{\"action\":3,\"ts\":1759300000000,\"uid\":\"b7891001\",\"currency\":\"PHP\"}";
        String x = OpAdapter.seal(json, KEY, IV);

        assertThat(x).doesNotContain("+", "/", "=");
        assertThat(OpAdapter.open(x, KEY, IV).path("uid").asString()).isEqualTo("b7891001");
        // the standard Base64 alphabet is accepted as well
        assertThat(OpAdapter.open(x.replace('-', '+').replace('_', '/'), KEY, IV).path("action").asInt()).isEqualTo(3);

        String tampered = (x.charAt(0) == 'A' ? "B" : "A") + x.substring(1);
        assertThatThrownBy(() -> OpAdapter.open(tampered, KEY, IV))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> OpAdapter.open(x, "fedcba9876543210", IV)).isInstanceOf(CallbackException.class);
        assertThatThrownBy(() -> OpAdapter.open("", KEY, IV)).isInstanceOf(CallbackException.class);
    }

    @Test
    void staleTimestampInsideTheCiphertextIsRejected() {
        long stale = Instant.now().minusSeconds(3600).toEpochMilli();
        CallbackRequest request = callback("{\"action\":3,\"ts\":" + stale + ",\"uid\":\"b7891001\"}");

        assertThatThrownBy(() -> adapter.parse(request, client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
    }

    @Test
    void betDebitsTheAbsoluteStakeInTheGameTypeRound() {
        WalletCommand command = adapter.parse(callback(message(1, """
                "transferId":123456789,"uid":"b7891001","gameSeqNo":218923321,"gType":1,"mType":100001,"gameMode":0,
                "gameDate":"2020-04-11T08:12:44.332Z","currency":"USD","bet":-10,"jackpotContribute":-0.4""")), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> {
            assertThat(bet.playerId()).isEqualTo("b7891001");
            assertThat(bet.currency()).isNull();
            assertThat(bet.txnId()).isEqualTo("123456789");
            assertThat(bet.roundId()).isEqualTo("1-218923321");
            assertThat(bet.gameCode()).isEqualTo("100001");
            assertThat(bet.amount()).isEqualByComparingTo("10");
            assertThat(bet.roundClosed()).isFalse();
        });
    }

    @Test
    void resultsCancelsAndPayoffsMapToTheirWalletCommands() {
        WalletCommand win = adapter.parse(callback(message(4, """
                "transferId":987,"uid":"b7891001","gameSeqNo":218923321,"gType":1,"mType":100001,"gameMode":0,
                "bet":-10,"win":25.5,"netWin":15.5""")), client);
        assertThat(win).isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
            assertThat(p.txnId()).isEqualTo("987");
            assertThat(p.roundId()).isEqualTo("1-218923321");
            assertThat(p.amount()).isEqualByComparingTo("25.5");
            assertThat(p.payoutType()).isEqualTo(TxnType.PAYOUT);
            assertThat(p.roundClosed()).isTrue();
        });

        WalletCommand freeWin = adapter.parse(callback(message(4, """
                "transferId":988,"uid":"b7891001","gameSeqNo":5,"gType":1,"win":3,"freeCardId":"card-1\"""")), client);
        assertThat(((WalletCommand.Payout) freeWin).payoutType()).isEqualTo(TxnType.FREE_PAYOUT);

        WalletCommand cancel = adapter.parse(callback(message(2, "\"transferId\":123456789,\"uid\":\"b7891001\",\"mType\":100001")), client);
        assertThat(cancel).isInstanceOfSatisfying(WalletCommand.Rollback.class, r -> {
            assertThat(r.rollbackTxnId()).isEqualTo("cancel:123456789");
            assertThat(r.targetTxnId()).isEqualTo("123456789");
            assertThat(r.targetType()).isEqualTo(TxnType.BET);
            assertThat(r.roundId()).isNull();
        });

        WalletCommand payoff = adapter.parse(callback(message(11, """
                "transferId":555,"uid":"b7891001","awardAmount":100,"campaignName":"October\"""")), client);
        assertThat(payoff).isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
            assertThat(p.payoutType()).isEqualTo(TxnType.PROMO_PAYOUT);
            assertThat(p.roundId()).isEqualTo("promo:555");
        });

        assertThatThrownBy(() -> adapter.parse(callback(message(21, "\"uid\":\"b7891001\"")), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.UNKNOWN_ACTION));
    }

    @Test
    void outcomesAreRenderedWithOpStatuses() {
        CallbackRequest request = callback(message(3, "\"uid\":\"b7891001\""));
        WalletCommand.Bet bet = new WalletCommand.Bet("b7891001", null, "1", "1-1", null, BigDecimal.ONE, false);
        WalletCommand.Payout win = new WalletCommand.Payout("b7891001", null, "2", "1-1", null, BigDecimal.ONE, TxnType.PAYOUT, null, true);
        WalletCommand.Rollback cancel = new WalletCommand.Rollback("b7891001", null, "cancel:1", "1", TxnType.BET, null, null);
        CommandOutcome replay = new CommandOutcome(Code.SUCCESS, "b7891001", "PHP", new BigDecimal("9980.129"), 7L, true, null);

        JsonNode ok = body(adapter.render(request, win, new CommandOutcome(Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("9980.129"), 7L, false, null), client));
        assertThat(ok.path("status").asString()).isEqualTo("0000");
        assertThat(ok.path("balance").decimalValue()).isEqualByComparingTo("9980.12");
        assertThat(ok.has("err_text")).isFalse();

        JsonNode duplicateBet = body(adapter.render(request, bet, replay, client));
        assertThat(duplicateBet.path("status").asString()).isEqualTo("9011");
        assertThat(duplicateBet.path("balance").decimalValue()).isEqualByComparingTo("9980.12");
        assertThat(status(adapter.render(request, win, replay, client))).isEqualTo("0000");

        assertThat(status(adapter.render(request, bet, outcome(Code.INSUFFICIENT_FUNDS), client))).isEqualTo("6006");
        assertThat(status(adapter.render(request, bet, outcome(Code.PLAYER_NOT_FOUND), client))).isEqualTo("9999");
        assertThat(status(adapter.render(request, cancel, outcome(Code.TXN_NOT_FOUND), client))).isEqualTo("0000");
        assertThat(status(adapter.render(request, win, outcome(Code.BET_NOT_FOUND), client))).isEqualTo("6001");

        assertThat(status(adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE))).isEqualTo("6001");
        assertThat(status(adapter.renderError(request, CallbackError.AUTH_FAILED))).isEqualTo("9006");
        assertThat(status(adapter.renderError(AdapterTests.post("OP", "", "dc=DC1&x="), CallbackError.AUTH_FAILED))).isEqualTo("9004");
        assertThat(status(adapter.renderError(request, CallbackError.UNKNOWN_ACTION))).isEqualTo("9007");
    }

    @Test
    void historyRowsAreKeyedByGameTypeAndSequence() {
        String json = """
                {"status":"0000","data":[
                  {"seqNo":5250145705663,"uid":"b7891001","gType":1,"mType":100001,"gameDate":"2026-10-01T08:00:00.000Z",
                   "reportDate":"2026-10-01T08:00:05.000Z","lastModifyTime":"2026-10-01T08:00:06.000Z","currency":"PHP",
                   "bet":-10,"win":25,"jackpot":0,"jackpotContribute":-0.4,"gameMode":0},
                  {"seqNo":1,"uid":"o789992043989","gType":1,"mType":100001,"gameDate":"2026-10-01T08:01:00.000Z","bet":-1,"win":0}
                ]}""";

        List<ProviderBetRecordView> records = OpAdapter.parseHistory(json, "PHP", "OP");

        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.providerBetId()).isEqualTo("1-5250145705663");
            assertThat(r.roundId()).isEqualTo("1-5250145705663");
            assertThat(r.userId()).isEqualTo(1001L);
            assertThat(r.gameCode()).isEqualTo("100001");
            assertThat(r.betAmount()).isEqualByComparingTo("10");
            assertThat(r.payoutAmount()).isEqualByComparingTo("25");
            assertThat(r.status()).isEqualTo("SETTLED");
            assertThat(r.betTime()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
            assertThat(r.settleTime()).isEqualTo(Instant.parse("2026-10-01T08:00:05Z"));
        });
        assertThat(OpAdapter.parseHistory("{\"status\":\"9015\",\"err_text\":\"Data does not exist.\"}", "PHP", "OP")).isEmpty();
        assertThatThrownBy(() -> OpAdapter.parseHistory("{\"status\":\"9019\",\"err_text\":\"rate limit\"}", "PHP", "OP"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A plaintext message with a fresh ts; {@code fields} are the JSON members after action and ts. */
    private static String message(int action, String fields) {
        return "{\"action\":" + action + ",\"ts\":" + System.currentTimeMillis() + "," + fields.strip() + "}";
    }

    private static CallbackRequest callback(String json) {
        return AdapterTests.post("OP", "", "dc=DC1&x=" + OpAdapter.seal(json, KEY, IV));
    }

    private static CommandOutcome outcome(Code code) {
        return CommandOutcome.of(code, "b7891001", "PHP", BigDecimal.TEN);
    }

    private static JsonNode body(CallbackResponse response) {
        return JsonUtils.mapper().readTree(response.body());
    }

    private static String status(CallbackResponse response) {
        return body(response).path("status").asString();
    }
}

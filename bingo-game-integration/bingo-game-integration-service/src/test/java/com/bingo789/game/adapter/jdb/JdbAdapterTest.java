package com.bingo789.game.adapter.jdb;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbAdapterTest {

    private static final String KEY = "0123456789abcdef";
    private static final String IV = "fedcba9876543210";

    private final JdbAdapter adapter = new JdbAdapter();
    private final ProviderClient client = AdapterTests.client("JDB", "agent", KEY, Map.of("iv", IV), Map.of("dc", "DC1"));

    @Test
    void xDecryptsWithKeyAndIvAndTamperingFailsAuthentication() {
        String json = "{\"action\":10,\"ts\":1759300000000,\"uid\":\"b7891001\",\"currency\":\"PP\",\"transferId\":900,"
                + "\"historyId\":\"h-1\",\"refTransferIds\":[800],\"amount\":7.5}";
        String x = JdbAdapter.seal(json, KEY, IV);
        assertThat(x).doesNotContain("+", "/", "=");
        assertThat(JdbAdapter.open(x, KEY, IV)).isEqualTo(json);
        assertThat(adapter.parse(call(json), client)).isInstanceOf(WalletCommand.Payout.class);

        String flipped = (x.charAt(0) == 'A' ? 'B' : 'A') + x.substring(1);
        List<CallbackRequest> tampered = List.of(
                AdapterTests.post("JDB", "", "x=" + flipped),
                AdapterTests.post("JDB", "", "x=" + JdbAdapter.seal(json, "abcdef0123456789", IV)),
                AdapterTests.post("JDB", "", "x=not-base64!"),
                AdapterTests.post("JDB", "", "y=1"));
        for (CallbackRequest request : tampered) {
            assertThatThrownBy(() -> adapter.parse(request, client))
                    .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        }
        assertThat(status(adapter.renderError(tampered.getFirst(), CallbackError.AUTH_FAILED))).isEqualTo("401");
    }

    @Test
    void balanceMapsTheJdbCurrencyCode() {
        assertThat(adapter.parse(call("{\"action\":6,\"ts\":" + now() + ",\"uid\":\"b7891001\",\"currency\":\"PP\"}"), client))
                .isEqualTo(new WalletCommand.GetBalance("b7891001", "PHP"));
        assertThat(JdbAdapter.currency("US")).isEqualTo("USD");
        assertThat(JdbAdapter.currency("VN")).isEqualTo("VN");
    }

    @Test
    void staleTsIsRefusedForDebitsOnly() {
        String bet = "{\"action\":9,\"ts\":1000,\"uid\":\"b7891001\",\"currency\":\"PP\",\"transferId\":1,\"historyId\":\"h\",\"amount\":1}";
        assertThatThrownBy(() -> adapter.parse(call(bet), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        String cancel = "{\"action\":11,\"ts\":1000,\"uid\":\"b7891001\",\"currency\":\"PP\",\"transferId\":2,\"refTransferIds\":[1]}";
        assertThat(adapter.parse(call(cancel), client)).isInstanceOf(WalletCommand.Rollback.class);
    }

    @Test
    void betAndSettleIsOneBetAndPayoutWithAPositiveStake() {
        String json = "{\"action\":8,\"ts\":" + now() + ",\"uid\":\"b7891001\",\"currency\":\"PP\",\"gType\":0,\"mType\":8001,"
                + "\"transferId\":123456789012,\"historyId\":\"h-9\",\"bet\":-2.5,\"win\":10,\"netWin\":7.5}";

        assertThat(adapter.parse(call(json), client)).isInstanceOfSatisfying(WalletCommand.BetAndPayout.class, bp -> {
            assertThat(bp.betTxnId()).isEqualTo("123456789012");
            assertThat(bp.payoutTxnId()).isEqualTo("123456789012");
            assertThat(bp.roundId()).isEqualTo("h-9");
            assertThat(bp.gameCode()).isEqualTo("0_8001");
            assertThat(bp.betAmount()).isEqualByComparingTo("2.5");
            assertThat(bp.payoutAmount()).isEqualByComparingTo("10");
        });
    }

    @Test
    void cancelsSettlesWithdrawsAndDeposits() {
        assertThat(parse("{\"action\":4,\"uid\":\"b7891001\",\"transferId\":5}"))
                .isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> assertThat(batch.steps())
                        .extracting(s -> ((WalletCommand.Rollback) s).targetType()).containsExactly(TxnType.PAYOUT, TxnType.BET));
        assertThat(parse("{\"action\":10,\"uid\":\"b7891001\",\"transferId\":7,\"refTransferIds\":[5,6],\"amount\":12}"))
                .isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
                    assertThat(p.txnId()).isEqualTo("7");
                    assertThat(p.betTxnId()).isEqualTo("5");
                    assertThat(p.roundId()).isEqualTo("5");
                    assertThat(p.amount()).isEqualByComparingTo("12");
                });
        assertThat(parse("{\"action\":11,\"uid\":\"b7891001\",\"transferId\":8,\"refTransferIds\":[5,6]}"))
                .isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> assertThat(batch.steps())
                        .extracting(s -> ((WalletCommand.Rollback) s).targetTxnId()).containsExactly("5", "6"));
        assertThat(parse("{\"action\":13,\"ts\":" + now() + ",\"uid\":\"b7891001\",\"transferId\":20,\"amount\":100}"))
                .isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> assertThat(bet.roundId()).isEqualTo("20"));
        assertThat(parse("{\"action\":14,\"uid\":\"b7891001\",\"transferId\":21,\"refTransferIds\":[20],\"amount\":80}"))
                .isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
                    assertThat(p.roundId()).isEqualTo("20");
                    assertThat(p.betTxnId()).isEqualTo("20");
                });
        assertThat(parse("{\"action\":15,\"uid\":\"b7891001\",\"transferId\":22,\"refTransferId\":20}"))
                .isInstanceOfSatisfying(WalletCommand.Rollback.class, r -> assertThat(r.targetTxnId()).isEqualTo("20"));

        assertThatThrownBy(() -> parse("{\"action\":14,\"uid\":\"b7891001\",\"transferId\":21,\"refTransferIds\":[],\"amount\":80}"))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));
        assertThatThrownBy(() -> parse("{\"action\":10,\"uid\":\"b7891001\",\"transferId\":7,\"refTransferIds\":[5],\"amount\":-1}"))
                .isInstanceOf(CallbackException.class);
        assertThatThrownBy(() -> parse("{\"action\":99,\"uid\":\"b7891001\"}"))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.UNKNOWN_ACTION));
    }

    @Test
    void outcomesAreRenderedWithJdbStatuses() {
        CallbackRequest request = AdapterTests.post("JDB", "", "");
        WalletCommand bet = new WalletCommand.Bet("b7891001", "PHP", "1", "h", null, BigDecimal.ONE, false);
        WalletCommand cancelBetAndSettle = parse("{\"action\":4,\"uid\":\"b7891001\",\"transferId\":5}");

        JsonNode ok = JsonUtils.mapper().readTree(adapter.render(request, bet,
                CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", new BigDecimal("50.129")), client).body());
        assertThat(ok.path("status").asString()).isEqualTo("0000");
        assertThat(ok.path("balance").decimalValue()).isEqualByComparingTo("50.12");

        CommandOutcome duplicate = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 1L, true, null);
        assertThat(status(adapter.render(request, bet, duplicate, client))).isEqualTo("0000");
        assertThat(status(adapter.render(request, bet, outcome(CommandOutcome.Code.INSUFFICIENT_FUNDS), client))).isEqualTo("6006");
        assertThat(status(adapter.render(request, bet, outcome(CommandOutcome.Code.PLAYER_NOT_FOUND), client))).isEqualTo("0001");
        assertThat(status(adapter.render(request, cancelBetAndSettle, outcome(CommandOutcome.Code.PLAYER_NOT_FOUND), client))).isEqualTo("6101");
        assertThat(status(adapter.render(request, bet, outcome(CommandOutcome.Code.BET_NOT_FOUND), client))).isEqualTo("6009");
        assertThat(status(adapter.render(request, cancelBetAndSettle, outcome(CommandOutcome.Code.TXN_NOT_FOUND), client))).isEqualTo("0000");
        assertThat(status(adapter.render(request, bet, outcome(CommandOutcome.Code.TXN_CANCELLED), client))).isEqualTo("6101");
        assertThat(status(adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE))).isEqualTo("9017");
        assertThat(status(adapter.renderError(request, CallbackError.UNKNOWN_ACTION))).isEqualTo("400");
        assertThat(status(adapter.renderError(request, CallbackError.BAD_REQUEST))).isEqualTo("6007");
    }

    @Test
    void betRecordsAreSettledRoundsInJdbTime() {
        JsonNode response = JsonUtils.mapper().readTree("""
                {"status":"0000","data":[
                  {"playerId":"b7891001","gType":0,"mType":8001,"historyId":"H1","currency":"PP","bet":-10,"total":15,
                   "hasGamble":0,"gameDate":"01-10-2026 08:00:00","lastModifyTime":"01-10-2026 08:00:03"},
                  {"playerId":"b7891002","gType":7,"mType":7001,"historyId":"H2","currency":"PP","bet":-10,"total":-10,
                   "hasGamble":1,"gambleBet":-20,"gameDate":"01-10-2026 08:01:00","lastModifyTime":"01-10-2026 08:01:00"},
                  {"playerId":"zz","gType":0,"mType":8001,"historyId":"H3","currency":"PP","bet":-1,"total":0,
                   "gameDate":"01-10-2026 08:02:00","lastModifyTime":"01-10-2026 08:02:00"}]}""");

        List<ProviderBetRecordView> records = JdbAdapter.parseRecords(response, "JDB");

        assertThat(records).extracting(ProviderBetRecordView::providerBetId).containsExactly("H1", "H2");
        ProviderBetRecordView first = records.getFirst();
        assertThat(first.currency()).isEqualTo("PHP");
        assertThat(first.gameCode()).isEqualTo("0_8001");
        assertThat(first.betAmount()).isEqualByComparingTo("10");
        assertThat(first.payoutAmount()).isEqualByComparingTo("25");
        assertThat(first.betTime()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
        assertThat(first.settleTime()).isEqualTo(Instant.parse("2026-10-01T12:00:03Z"));
        assertThat(records.get(1).betAmount()).isEqualByComparingTo("20");
        assertThat(records.get(1).payoutAmount()).isEqualByComparingTo("0");
    }

    @Test
    void windowsFollowJdbLimits() {
        Instant now = Instant.parse("2026-10-01T12:00:30Z");

        JdbAdapter.Window recent = JdbAdapter.window(Instant.parse("2026-10-01T11:30:20Z"), Instant.parse("2026-10-01T11:58:40Z"), now);
        assertThat(recent.history()).isFalse();
        assertThat(recent.start()).isEqualTo(Instant.parse("2026-10-01T11:30:00Z"));
        assertThat(recent.end()).isEqualTo(Instant.parse("2026-10-01T11:45:00Z"));
        assertThat(JdbAdapter.queryTime(recent.start())).isEqualTo("01-10-2026 07:30:00");

        JdbAdapter.Window old = JdbAdapter.window(Instant.parse("2026-10-01T09:57:10Z"), Instant.parse("2026-10-01T10:30:00Z"), now);
        assertThat(old.history()).isTrue();
        assertThat(old.end()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        JdbAdapter.Window older = JdbAdapter.window(Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T10:30:00Z"), now);
        assertThat(older.end()).isEqualTo(Instant.parse("2026-10-01T08:05:00Z"));

        assertThat(JdbAdapter.window(Instant.parse("2026-10-01T11:58:10Z"), Instant.parse("2026-10-01T11:58:50Z"), now)).isNull();
    }

    private WalletCommand parse(String json) {
        return adapter.parse(call(json), client);
    }

    private static CallbackRequest call(String json) {
        return AdapterTests.post("JDB", "", "x=" + JdbAdapter.seal(json, KEY, IV));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static CommandOutcome outcome(CommandOutcome.Code code) {
        return CommandOutcome.of(code, "b7891001", "PHP", BigDecimal.ONE);
    }

    private static String status(CallbackResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        return JsonUtils.mapper().readTree(response.body()).path("status").asString();
    }
}

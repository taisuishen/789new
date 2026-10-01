package com.bingo789.game.adapter.pp;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.api.dto.BetPullQuery;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.wallet.api.enums.TxnType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PpAdapterTest {

    private static final String SECRET = "testKey";

    private final PpAdapter adapter = new PpAdapter();
    private final ProviderClient client = AdapterTests.client("PP", "login", SECRET, Map.of(), Map.of("feedDomains", "api-a.example"));

    @Test
    void hashCoversEveryRawParameterSorted() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("userId", "b7891001");
        params.put("amount", "10.00");
        params.put("providerId", "PragmaticPlay");
        params.put("empty", "");
        // md5("amount=10.00&providerId=PragmaticPlay&userId=b7891001testKey")
        String expected = com.bingo789.game.adapter.support.Ciphers.md5Hex("amount=10.00&providerId=PragmaticPlay&userId=b7891001" + SECRET);

        assertThat(PpAdapter.hash(params, SECRET)).isEqualTo(expected);
        adapter.verifySignature(signed("bet.html", params), client);

        Map<String, String> extra = new LinkedHashMap<>(params);
        extra.put("newField", "x");
        CallbackRequest tampered = AdapterTests.post("PP", "bet.html",
                body(extra) + "&hash=" + PpAdapter.hash(params, SECRET));
        assertThatThrownBy(() -> adapter.verifySignature(tampered, client)).isInstanceOf(CallbackException.class);
    }

    @Test
    void betIsKeyedByReferenceAndRoundIsGameAndRoundId() {
        WalletCommand command = adapter.parse(signed("bet.html", Map.of("userId", "b7891001", "gameId", "vs20olympgate",
                "roundId", "555", "amount", "2.50", "reference", "ref-1", "providerId", "PragmaticPlay", "timestamp", "1")), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> {
            assertThat(bet.txnId()).isEqualTo("ref-1");
            assertThat(bet.roundId()).isEqualTo("vs20olympgate:555");
            assertThat(bet.amount()).isEqualByComparingTo("2.50");
            assertThat(bet.currency()).isNull();
        });
    }

    @Test
    void resultWithPromoWinIsTwoPayouts() {
        WalletCommand command = adapter.parse(signed("result.html", Map.of("userId", "b7891001", "gameId", "g", "roundId", "9",
                "amount", "5.00", "reference", "r-1", "promoWinAmount", "1.00", "promoWinReference", "p-1")), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Batch.class, batch -> {
            assertThat(batch.steps()).hasSize(2);
            assertThat(((WalletCommand.Payout) batch.steps().get(1)).payoutType()).isEqualTo(TxnType.PROMO_PAYOUT);
        });
    }

    @Test
    void refundOfAnUnknownBetSucceedsWithoutTransaction() {
        CallbackRequest request = signed("refund.html", Map.of("userId", "b7891001", "reference", "ref-9"));
        WalletCommand command = adapter.parse(request, client);
        assertThat(command).isInstanceOfSatisfying(WalletCommand.Rollback.class, r -> {
            assertThat(r.rollbackTxnId()).isEqualTo("refund:ref-9");
            assertThat(r.targetTxnId()).isEqualTo("ref-9");
        });

        CallbackResponse response = adapter.render(request, command, CommandOutcome.of(CommandOutcome.Code.TXN_NOT_FOUND,
                "b7891001", "PHP", BigDecimal.TEN), client);
        JsonNode body = JsonUtils.mapper().readTree(response.body());
        assertThat(body.path("error").asInt()).isZero();
        assertThat(body.path("transactionId").asString()).isEmpty();
    }

    @Test
    void failuresAreRenderedWithPpCodes() {
        CallbackRequest request = signed("bet.html", Map.of("userId", "b7891001"));
        assertThat(error(adapter.render(request, null, CommandOutcome.of(CommandOutcome.Code.INSUFFICIENT_FUNDS, "b7891001", "PHP", null), client))).isEqualTo(1);
        assertThat(error(adapter.renderError(request, com.bingo789.game.adapter.model.CallbackError.SYSTEM_RETRYABLE))).isEqualTo(100);
        assertThat(error(adapter.renderError(signed("endRound.html", Map.of()), com.bingo789.game.adapter.model.CallbackError.SYSTEM_RETRYABLE))).isEqualTo(130);
        assertThat(error(adapter.renderError(request, com.bingo789.game.adapter.model.CallbackError.AUTH_FAILED))).isEqualTo(5);
    }

    @Test
    void feedRowsEndingInTheWindowAreRecords() {
        String csv = """
                timepoint=1759300000000
                playerID,extPlayerID,gameID,playSessionID,parentSessionID,startDate,endDate,status,type,bet,win,currency,jackpot
                1,b7891001,vs20olympgate,111,,2026-10-01 08:00:00,2026-10-01 08:00:05,C,R,1.00,2.50,PHP,0
                2,b7891002,vs20olympgate,112,,2026-10-01 08:59:59,2026-10-01 09:00:01,C,R,1.00,0.00,PHP,0
                3,x-unknown,vs20olympgate,113,,2026-10-01 08:10:00,2026-10-01 08:10:01,C,R,1.00,0.00,PHP,0
                """;
        BetPullQuery query = new BetPullQuery("PP", Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T09:00:00Z"), null, 0);

        List<ProviderBetRecordView> records = PpAdapter.parseFeed(csv, query, "PP");

        assertThat(records).extracting(ProviderBetRecordView::providerBetId).containsExactly("111");
        assertThat(records.getFirst().userId()).isEqualTo(1001L);
        assertThat(records.getFirst().payoutAmount()).isEqualByComparingTo("2.50");
    }

    private static CallbackRequest signed(String action, Map<String, String> params) {
        return AdapterTests.post("PP", action, body(params) + "&hash=" + PpAdapter.hash(params, SECRET));
    }

    private static String body(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "&").append(k).append('=')
                .append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return sb.toString();
    }

    private static int error(CallbackResponse response) {
        return JsonUtils.mapper().readTree(response.body()).path("error").asInt();
    }
}

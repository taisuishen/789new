package com.bingo789.game.adapter.sa;

import com.bingo789.game.adapter.AdapterTests;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.adapter.support.Forms;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.wallet.api.enums.TxnType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaAdapterTest {

    private static final String KEY = "g9G16nTs";

    private final SaAdapter adapter = new SaAdapter();
    private final ProviderClient client = AdapterTests.client("SA", null, KEY,
            Map.of("md5Key", "md5", "secretKey", "sk"), Map.of("lobbyCode", "A123", "launchUrl", "https://sa.example/app.aspx"));

    @Test
    void bodyDecryptsWithTheDesKeyRawOrUrlEncoded() {
        String plain = "username=b7891001&currency=PHP&amount=12.50&txnid=T1&timestamp=2026-10-01 08:00:00.123"
                + "&gametype=bac&hostid=601&gameid=G77&ip=1.2.3.4&platform=0&betdetails=x";
        String sealed = SaAdapter.encrypt(plain, KEY);

        WalletCommand raw = adapter.parse(AdapterTests.post("SA", "PlaceBet", sealed), client);
        WalletCommand encoded = adapter.parse(AdapterTests.post("SA", "PlaceBet", Forms.encode(sealed)), client);

        assertThat(encoded).isEqualTo(raw);
        assertThat(raw).isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> {
            assertThat(bet.playerId()).isEqualTo("b7891001");
            assertThat(bet.currency()).isEqualTo("PHP");
            assertThat(bet.txnId()).isEqualTo("T1");
            assertThat(bet.roundId()).isEqualTo("G77");
            assertThat(bet.gameCode()).isEqualTo("601");
            assertThat(bet.amount()).isEqualByComparingTo("12.50");
        });
    }

    @Test
    void bodiesThatDoNotDecryptAreAuthFailures() {
        String sealed = SaAdapter.encrypt("username=b7891001&currency=PHP", KEY);
        // cut three bytes: no longer a whole number of DES blocks
        String truncated = sealed.substring(0, sealed.length() - 4);

        assertThatThrownBy(() -> adapter.parse(AdapterTests.post("SA", "GetUserBalance", truncated), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> adapter.parse(AdapterTests.post("SA", "GetUserBalance", "not base64 !"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThatThrownBy(() -> adapter.parse(AdapterTests.post("SA", "GetUserBalance",
                SaAdapter.encrypt("username=b7891001&currency=PHP", "otherKey")), client))
                .isInstanceOf(CallbackException.class);
    }

    @Test
    void winAndLostSettleTheRoundOncePerPlayer() {
        String details = "{\"betlist\":[{\"betid\":1,\"bettype\":1,\"betamount\":10,\"resultamount\":10,\"txnid\":\"T1\"}]}";
        WalletCommand win = parse("PlayerWin", "username=b7891001&currency=PHP&amount=20&txnid=P9&gameid=G77&hostid=601"
                + "&retry=0&payoutdetails=" + details);
        WalletCommand lost = parse("PlayerLost", "username=b7891001&currency=PHP&txnid=P10&gameid=G78&hostid=601");

        assertThat(win).isInstanceOfSatisfying(WalletCommand.Payout.class, p -> {
            assertThat(p.txnId()).isEqualTo("G77:b7891001");
            assertThat(p.roundId()).isEqualTo("G77");
            assertThat(p.betTxnId()).isNull();
            assertThat(p.amount()).isEqualByComparingTo("20");
            assertThat(p.roundClosed()).isTrue();
        });
        assertThat(((WalletCommand.Payout) lost).amount()).isEqualByComparingTo("0");
    }

    @Test
    void cancelReversesTheReferencedBet() {
        WalletCommand cancel = parse("PlaceBetCancel", "username=b7891001&currency=PHP&amount=12.5&txnid=C1&gameid=G77"
                + "&hostid=601&txn_reverse_id=T1&retry=0&gamecancel=0");

        assertThat(cancel).isEqualTo(new WalletCommand.Rollback("b7891001", "PHP", "C1", "T1", TxnType.BET, "G77", "601"));
    }

    @Test
    void adjustmentsAreRewardTipAndTipCancel() {
        WalletCommand reward = parse("BalanceAdjustment", "username=b7891001&currency=PHP&amount=5&txnid=A1"
                + "&adjustmenttype=1&adjustmentdetails={\"redemptionid\":1,\"eventid\":2}");
        WalletCommand tip = parse("BalanceAdjustment", "username=b7891001&currency=PHP&amount=3&txnid=A2"
                + "&adjustmenttype=2&adjustmentdetails={\"gifttype\":1}");
        WalletCommand tipCancel = parse("BalanceAdjustment", "username=b7891001&currency=PHP&amount=3&txnid=A3"
                + "&adjustmenttype=3&adjustmentdetails={\"canceltxnid\":\"A2\"}");

        assertThat(((WalletCommand.Payout) reward).payoutType()).isEqualTo(TxnType.PROMO_PAYOUT);
        assertThat(tip).isEqualTo(new WalletCommand.Bet("b7891001", "PHP", "A2", "tip:A2", SaAdapter.TIP_GAME, new BigDecimal("3"), true));
        assertThat(tipCancel).isEqualTo(new WalletCommand.Rollback("b7891001", "PHP", "A3", "A2", TxnType.BET, "tip:A2", SaAdapter.TIP_GAME));
        assertThatThrownBy(() -> parse("BalanceAdjustment", "username=b7891001&currency=PHP&amount=3&txnid=A4&adjustmenttype=7"))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.BAD_REQUEST));
    }

    @Test
    void outcomesAreRenderedAsSaXml() {
        CallbackRequest request = AdapterTests.post("SA", "PlaceBet", "");
        WalletCommand bet = new WalletCommand.Bet("b7891001", "PHP", "T1", "G77", "601", BigDecimal.ONE, false);

        String ok = xml(adapter.render(request, bet, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("100.129")), client));
        assertThat(ok).contains("<username>b7891001</username><currency>PHP</currency><amount>100.12</amount><error>0</error>");

        CommandOutcome replay = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 1L, true, null);
        assertThat(xml(adapter.render(request, bet, replay, client))).contains("<error>1005</error>").contains("<amount>10.00</amount>");
        assertThat(xml(adapter.render(request, bet, CommandOutcome.of(CommandOutcome.Code.INSUFFICIENT_FUNDS, "b7891001", "PHP",
                BigDecimal.ONE), client))).contains("<error>1004</error>");
        assertThat(xml(adapter.render(request, bet, CommandOutcome.of(CommandOutcome.Code.BET_NOT_FOUND, "b7891001", "PHP",
                BigDecimal.ONE), client))).contains("<error>1005</error>");
        assertThat(xml(adapter.render(request, bet, CommandOutcome.of(CommandOutcome.Code.PLAYER_NOT_FOUND, "x", "PHP", null), client)))
                .contains("<error>1000</error>");

        WalletCommand usd = new WalletCommand.Bet("b7891001", "USD", "T1", "G77", "601", BigDecimal.ONE, false);
        assertThat(xml(adapter.render(request, usd, CommandOutcome.of(CommandOutcome.Code.INVALID_REQUEST, "b7891001", "USD", null), client)))
                .contains("<error>1001</error>");
        assertThat(xml(adapter.renderError(request, CallbackError.AUTH_FAILED))).contains("<error>1006</error>");
        assertThat(xml(adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE))).contains("<error>9999</error>");
    }

    @Test
    void betDetailsAreOneRecordPerBetOfTheRound() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <GetAllBetDetailsForTimeIntervalResponse>
                  <ErrorMsgId>0</ErrorMsgId>
                  <ErrorMsg>Success</ErrorMsg>
                  <BetDetailList>
                    <BetDetail>
                      <BetTime>2026-10-01T08:00:00.191</BetTime>
                      <PayoutTime>2026-10-01T08:00:40.5</PayoutTime>
                      <Username>b7891001</Username>
                      <HostID>601</HostID>
                      <GameID>G77</GameID>
                      <BetID>9001</BetID>
                      <BetAmount>10.00</BetAmount>
                      <Rolling>10.00</Rolling>
                      <ResultAmount>9.50</ResultAmount>
                      <GameType>bac</GameType>
                    </BetDetail>
                    <BetDetail>
                      <BetTime>2026-10-01T08:00:00</BetTime>
                      <Username>someone-else</Username>
                      <GameID>G77</GameID>
                      <BetID>9002</BetID>
                      <BetAmount>10</BetAmount>
                      <ResultAmount>-10</ResultAmount>
                    </BetDetail>
                  </BetDetailList>
                </GetAllBetDetailsForTimeIntervalResponse>
                """;

        List<ProviderBetRecordView> records = SaAdapter.parseBetDetails(xml, "SA", "PHP");

        assertThat(records).hasSize(1);
        ProviderBetRecordView record = records.getFirst();
        assertThat(record.providerBetId()).isEqualTo("9001");
        assertThat(record.roundId()).isEqualTo("G77");
        assertThat(record.userId()).isEqualTo(1001L);
        assertThat(record.gameCode()).isEqualTo("601");
        assertThat(record.payoutAmount()).isEqualByComparingTo("19.50");
        assertThat(record.betTime()).isEqualTo(Instant.parse("2026-10-01T00:00:00.191Z"));

        assertThatThrownBy(() -> SaAdapter.parseBetDetails("<R><ErrorMsgId>108</ErrorMsgId></R>", "SA", "PHP"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SaAdapter.parseBetDetails("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>", "SA", "PHP"))
                .isInstanceOf(IllegalStateException.class);
    }

    private WalletCommand parse(String action, String plain) {
        return adapter.parse(AdapterTests.post("SA", action, SaAdapter.encrypt(plain, KEY)), client);
    }

    private static String xml(CallbackResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo("application/xml");
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}

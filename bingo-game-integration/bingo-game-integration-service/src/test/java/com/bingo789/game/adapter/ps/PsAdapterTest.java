package com.bingo789.game.adapter.ps;

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

class PsAdapterTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

    private final PsAdapter adapter = new PsAdapter();
    private final ProviderClient client = AdapterTests.client("PS", "host-php", null, Map.of(), Map.of());

    @Test
    void theTokenIsTheOnlyCredential() {
        adapter.verifySignature(get("bet", "txn_id=1&total_bet=100&game_id=g&subgame_id=0&ts=1"), client);

        assertThatThrownBy(() -> adapter.parse(get("bet", "txn_id=1&total_bet=100&game_id=g"), client))
                .isInstanceOfSatisfying(CallbackException.class, e -> assertThat(e.error()).isEqualTo(CallbackError.AUTH_FAILED));
        assertThat(adapter.parse(get("auth", "access_token=" + TOKEN), client))
                .isEqualTo(new WalletCommand.Authenticate(TOKEN, null));
    }

    @Test
    void betIsASessionKeyedByTxnIdInCents() {
        WalletCommand command = adapter.parse(get("bet", "access_token=" + TOKEN + "&txn_id=0042&total_bet=250"
                + "&game_id=PSS-ON-00001&subgame_id=0&ts=1759300000"), client);

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Session.class, s -> {
            assertThat(s.token()).isEqualTo(TOKEN);
            assertThat(s.command()).isInstanceOfSatisfying(WalletCommand.Bet.class, bet -> {
                assertThat(bet.playerId()).isNull();
                assertThat(bet.txnId()).isEqualTo("42");
                assertThat(bet.roundId()).isEqualTo("42");
                assertThat(bet.gameCode()).isEqualTo("PSS-ON-00001");
                assertThat(bet.amount()).isEqualByComparingTo("2.50");
            });
        });
        assertThatThrownBy(() -> adapter.parse(get("bet", "access_token=" + TOKEN + "&txn_id=0&total_bet=1&game_id=g"), client))
                .isInstanceOf(CallbackException.class);
    }

    @Test
    void resultCreditsTheWinButNotTheJackpotContribution() {
        WalletCommand command = adapter.parse(get("result", "access_token=" + TOKEN + "&txn_id=42&total_win=1234"
                + "&bonus_win=0&game_id=g&subgame_id=0&ts=1&jp_contrib=5.5"), client);

        WalletCommand.Payout payout = (WalletCommand.Payout) ((WalletCommand.Session) command).command();
        assertThat(payout.amount()).isEqualByComparingTo("12.34");
        assertThat(payout.txnId()).isEqualTo("42");
        assertThat(payout.betTxnId()).isEqualTo("42");
        assertThat(payout.payoutType()).isEqualTo(TxnType.PAYOUT);
        assertThat(payout.roundClosed()).isTrue();
    }

    @Test
    void refundReversesTheBetAndBonusIsAStakelessJackpotInItsRound() {
        WalletCommand.Rollback refund = (WalletCommand.Rollback) ((WalletCommand.Session) adapter.parse(
                get("refundbet", "access_token=" + TOKEN + "&txn_id=42&game_id=g"), client)).command();
        assertThat(refund.rollbackTxnId()).isEqualTo("refund:42");
        assertThat(refund.targetTxnId()).isEqualTo("42");
        assertThat(refund.targetType()).isEqualTo(TxnType.BET);

        WalletCommand.Payout bonus = (WalletCommand.Payout) ((WalletCommand.Session) adapter.parse(get("bonusaward",
                "access_token=" + TOKEN + "&bonus_id=9&bonus_reward=5000&bonus_type=GRAND&game_id=g&subgame_id=0&txn_id=42"),
                client)).command();
        assertThat(bonus.txnId()).isEqualTo("9");
        assertThat(bonus.roundId()).isEqualTo("42");
        assertThat(bonus.payoutType()).isEqualTo(TxnType.JACKPOT_PAYOUT);
        assertThat(bonus.betTxnId()).isNull();
        assertThat(bonus.amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void outcomesAreRenderedWithPsStatusCodesAndCentBalances() {
        CallbackRequest bet = get("bet", "access_token=" + TOKEN);
        JsonNode ok = body(adapter.render(bet, null, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("12.349")), client));
        assertThat(ok.path("status_code").asInt()).isZero();
        assertThat(ok.path("balance").asLong()).isEqualTo(1234);

        CommandOutcome replay = new CommandOutcome(CommandOutcome.Code.SUCCESS, "b7891001", "PHP", BigDecimal.TEN, 1L, true, null);
        assertThat(status(adapter.render(bet, null, replay, client))).isZero();
        JsonNode insufficient = body(adapter.render(bet, null, CommandOutcome.of(CommandOutcome.Code.INSUFFICIENT_FUNDS,
                "b7891001", "PHP", BigDecimal.ONE), client));
        assertThat(insufficient.path("status_code").asInt()).isEqualTo(PsAdapter.INSUFFICIENT_BALANCE);
        assertThat(insufficient.path("balance").asLong()).isEqualTo(100);
        assertThat(status(adapter.render(bet, null, CommandOutcome.of(CommandOutcome.Code.BET_NOT_FOUND, null, "PHP", null), client)))
                .isEqualTo(PsAdapter.TXN_INVALID);
        assertThat(status(adapter.render(bet, null, CommandOutcome.of(CommandOutcome.Code.INVALID_TOKEN, null, null, null), client)))
                .isEqualTo(PsAdapter.TOKEN_INVALID);
        assertThat(status(adapter.renderError(bet, CallbackError.SYSTEM_RETRYABLE))).isEqualTo(PsAdapter.SYSTEM_ERROR);
    }

    @Test
    void authAnswersTheMemberId() {
        CallbackRequest auth = get("auth", "access_token=" + TOKEN);
        JsonNode ok = body(adapter.render(auth, null, CommandOutcome.of(CommandOutcome.Code.SUCCESS, "b7891001", "PHP",
                new BigDecimal("5")), client));
        assertThat(ok.path("member_id").asString()).isEqualTo("b7891001");
        assertThat(ok.path("member_name").asString()).isEqualTo("b7891001");
        assertThat(ok.path("balance").asLong()).isEqualTo(500);

        JsonNode failed = body(adapter.render(auth, null, CommandOutcome.of(CommandOutcome.Code.INVALID_TOKEN, null, null, null), client));
        assertThat(failed.path("status_code").asInt()).isEqualTo(PsAdapter.TOKEN_INVALID);
        assertThat(failed.path("member_id").isNull()).isTrue();
    }

    @Test
    void feedRecordsIncludeTheJackpotWinInThePayout() {
        String json = """
                {"2026-10-01": {
                   "b7891001": [
                     {"sn": 42, "gid": "PSS-ON-00001", "sid": 0, "s_tm": "2026-10-01 08:00:00", "tm": "08:00:05",
                      "bet": 100, "dm": 1, "win": 250, "bn": 0, "gb": 0, "jp": 1000, "jc": 1, "gt": "SLOT",
                      "jd": [{"sn": 1, "type": 1, "gid": 1, "pid": 1, "win": 1000, "dtm": "2026-10-01 08:00:05"}]}
                   ],
                   "x-unknown": [
                     {"sn": 43, "gid": "g", "s_tm": "2026-10-01 08:00:00", "tm": "08:00:05", "bet": 100, "win": 0, "jp": 0}
                   ]}}
                """;

        List<ProviderBetRecordView> records = PsAdapter.parseFeed(json, "PS", "PHP");

        assertThat(records).hasSize(1);
        ProviderBetRecordView record = records.getFirst();
        assertThat(record.providerBetId()).isEqualTo("42");
        assertThat(record.roundId()).isEqualTo("42");
        assertThat(record.userId()).isEqualTo(1001L);
        assertThat(record.betAmount()).isEqualByComparingTo("1.00");
        assertThat(record.payoutAmount()).isEqualByComparingTo("12.50");
        assertThat(record.betTime()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(record.settleTime()).isEqualTo(Instant.parse("2026-10-01T00:00:05Z"));
        assertThat(record.status()).isEqualTo("SETTLED");
    }

    private static CallbackRequest get(String action, String query) {
        return AdapterTests.get("PS", action, query);
    }

    private static JsonNode body(CallbackResponse response) {
        assertThat(response.httpStatus()).isEqualTo(200);
        return JsonUtils.mapper().readTree(response.body());
    }

    private static int status(CallbackResponse response) {
        return body(response).path("status_code").asInt();
    }
}

package com.bingo789.game.adapter.demo;

import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.wallet.api.enums.TxnType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoProviderAdapterTest {

    private final DemoProviderAdapter adapter = new DemoProviderAdapter();

    @Test
    void parsesWinWithFreeSpinType() {
        String body = """
                {"playerId":"b7891001","currency":"PHP","transactionId":"w-1","betTransactionId":"b-1",
                 "roundId":"r-1","gameCode":"slot-1","amount":12.3400,"winType":"FREESPIN","roundEnded":true}
                """;

        WalletCommand command = adapter.parse(request("win", body));

        assertThat(command).isInstanceOfSatisfying(WalletCommand.Payout.class, payout -> {
            assertThat(payout.payoutType()).isEqualTo(TxnType.FREE_PAYOUT);
            assertThat(payout.amount()).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(payout.roundClosed()).isTrue();
        });
    }

    @Test
    void rejectsMissingFieldsAndUnknownActions() {
        assertThatThrownBy(() -> adapter.parse(request("bet", "{\"playerId\":\"b7891001\"}")))
                .isInstanceOf(CallbackException.class);
        assertThatThrownBy(() -> adapter.parse(request("jackpot-contribution", "{}")))
                .isInstanceOf(CallbackException.class);
    }

    @Test
    void signatureCoversTimestampActionAndBody() {
        String a = DemoProviderAdapter.sign("secret", "1", "bet", "{}");
        assertThat(DemoProviderAdapter.sign("secret", "1", "bet", "{}")).isEqualTo(a);
        assertThat(DemoProviderAdapter.sign("secret", "2", "bet", "{}")).isNotEqualTo(a);
        assertThat(DemoProviderAdapter.sign("secret", "1", "win", "{}")).isNotEqualTo(a);
        assertThat(DemoProviderAdapter.sign("secret", "1", "bet", "{ }")).isNotEqualTo(a);
    }

    private static CallbackRequest request(String action, String body) {
        return new CallbackRequest("DEMO", action, Map.of(), null, body.getBytes(StandardCharsets.UTF_8), "127.0.0.1", Instant.now());
    }
}

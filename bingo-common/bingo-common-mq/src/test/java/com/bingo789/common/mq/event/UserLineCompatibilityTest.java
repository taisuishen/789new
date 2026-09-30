package com.bingo789.common.mq.event;

import com.bingo789.common.core.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Events produced before user lines existed (still in Kafka retention) must decode, on line 1. */
class UserLineCompatibilityTest {

    @Test
    void eventWithoutLineBelongsToTheDefaultLine() {
        String legacy = """
                {"id":1,"userId":42,"currency":"PHP","txnType":"BET","direction":-1,"amount":10.0000,
                 "balanceAfter":90.0000,"providerCode":"DEMO","providerTxnId":"t1","roundClosed":false,"status":1,
                 "createdAt":"2026-09-30T16:00:00.123+08:00"}
                """;
        WalletTxnEvent event = JsonUtils.fromJson(legacy, WalletTxnEvent.class);
        assertThat(event.userLine()).isEqualTo(1);
        assertThat(event.userId()).isEqualTo(42);
    }

    @Test
    void lineRoundTrips() {
        String json = """
                {"orderNo":"D1","userId":7,"userLine":2,"currency":"PHP","amount":100,"channelCode":"GCASH",
                 "firstDeposit":true,"succeededAt":"2026-09-30T08:00:00Z"}
                """;
        DepositSucceededEvent event = JsonUtils.fromJson(json, DepositSucceededEvent.class);
        assertThat(event.userLine()).isEqualTo(2);
        assertThat(JsonUtils.fromJson(JsonUtils.toJson(event), DepositSucceededEvent.class)).isEqualTo(event);
    }
}

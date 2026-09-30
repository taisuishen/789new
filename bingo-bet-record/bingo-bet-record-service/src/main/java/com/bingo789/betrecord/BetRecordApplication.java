package com.bingo789.betrecord;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.lobby.api.LobbyClient;
import com.bingo789.user.api.UserClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Bet records. {@code game_round} is a projection of the wallet ledger (Kafka {@code bingo.wallet.txn}), never
 * written by provider callbacks directly; {@code provider_bet_record} holds the provider's own view, pulled
 * from bet-history APIs and forwarded to reconciliation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(clients = {ProviderQueryClient.class, UserClient.class, LobbyClient.class})
public class BetRecordApplication {

    public static void main(String[] args) {
        BingoTime.applyJvmDefault();
        SpringApplication.run(BetRecordApplication.class, args);
    }
}

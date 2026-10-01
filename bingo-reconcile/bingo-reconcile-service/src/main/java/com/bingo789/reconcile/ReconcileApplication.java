package com.bingo789.reconcile;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.lobby.api.LobbyClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Reconciliation and reporting. Every figure comes from StarRocks (ReconcileDw), which loads the Kafka streams into
 * Primary Key tables (exact sums under at-least-once delivery); this service consumes no Kafka itself.
 * <ul>
 *   <li>Layer 1 (hourly, approximate): ledger (wallet_txn) vs provider bet history (provider_bet) per provider and
 *   currency.</li>
 *   <li>Layer 2 (daily, authoritative): per-record matching of rounds, auto-compensation of known patterns,
 *   tickets (recon_diff) for everything else.</li>
 *   <li>Reporting: GGR per day (ggr_daily), provider revenue-share settlement, RTP monitoring.</li>
 * </ul>
 * Platform totals are never updated per wallet transaction: a shared total row touched by every bet would be a
 * global hot row serializing the whole wallet.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(clients = {ProviderQueryClient.class, LobbyClient.class})
public class ReconcileApplication {

    public static void main(String[] args) {
        BingoTime.applyJvmDefault();
        SpringApplication.run(ReconcileApplication.class, args);
    }
}

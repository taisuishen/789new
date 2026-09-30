package com.bingo789.reconcile;

import com.bingo789.common.core.time.BingoTime;
import com.bingo789.game.api.ProviderQueryClient;
import com.bingo789.lobby.api.LobbyClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Reconciliation and reporting.
 * <ul>
 *   <li>Layer 1 (hourly, approximate): ledger aggregates (recon_platform_hourly, fed by bingo.wallet.txn) vs
 *   provider aggregates (recon_provider_hourly, fed by bingo.provider.bet) per provider and currency.</li>
 *   <li>Layer 2 (daily, authoritative): per-record matching in StarRocks, auto-compensation of known
 *   patterns, tickets (recon_diff) for everything else.</li>
 *   <li>Reporting: GGR per day, provider revenue-share settlement, RTP monitoring.</li>
 * </ul>
 * Platform aggregate accounts (platform totals, agent totals, GGR) are computed here, asynchronously and in
 * batches. They are never updated per wallet transaction: a shared total row touched by every bet would be a
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

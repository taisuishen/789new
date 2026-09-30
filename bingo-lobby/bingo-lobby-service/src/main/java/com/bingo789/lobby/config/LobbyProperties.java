package com.bingo789.lobby.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * @param cacheTtl          catalogue snapshots are refreshed in the background after this age
 * @param cacheMaxStale     a snapshot older than this is dropped even if refreshing keeps failing (DB outage)
 * @param maxPageSize       upper bound for player-facing page sizes
 * @param defaultLobbyUrl   return URL handed to providers when the client sends none or a non-allowed one
 * @param allowedLobbyHosts hosts a client-supplied lobby URL may point to (prevents open redirects via the provider)
 */
@ConfigurationProperties("bingo.lobby")
public record LobbyProperties(
        @DefaultValue("30s") Duration cacheTtl,
        @DefaultValue("10m") Duration cacheMaxStale,
        @DefaultValue("200") int maxPageSize,
        String defaultLobbyUrl,
        List<String> allowedLobbyHosts) {

    public LobbyProperties {
        allowedLobbyHosts = allowedLobbyHosts == null ? List.of()
                : allowedLobbyHosts.stream().map(host -> host.trim().toLowerCase(Locale.ROOT)).toList();
    }
}

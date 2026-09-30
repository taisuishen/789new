package com.bingo789.gateway.config;

import com.bingo789.common.core.HeaderNames;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Path lists use "PATTERN" (any method) or "METHOD PATTERN" entries with Spring {@code PathPattern} syntax.
 * Defaults fail closed: nothing is public and geo-fencing refuses to start without allowed countries.
 * <p>
 * Bound once at startup. {@code bingo.gateway.degrade.*} is deliberately not here: it changes at runtime and is
 * re-read by DegradeGlobalFilter.
 */
@ConfigurationProperties("bingo.gateway")
public record BingoGatewayProperties(
        @DefaultValue Geo geo,
        @DefaultValue ClientIp clientIp,
        @DefaultValue Auth auth,
        @DefaultValue Admission admission,
        @DefaultValue({"/internal/**", "/callback/**"}) List<String> blockedPaths) {

    /**
     * @param countryHeader    header carrying the ISO 3166-1 alpha-2 country resolved by WAF/CDN
     * @param allowedCountries licensed jurisdictions
     * @param bypassPaths      paths exempt from geo-fencing (health checks)
     */
    public record Geo(
            @DefaultValue("true") boolean enabled,
            @DefaultValue(HeaderNames.COUNTRY_CODE) String countryHeader,
            List<String> allowedCountries,
            List<String> bypassPaths) {

        public Geo {
            allowedCountries = allowedCountries == null ? List.of() : List.copyOf(allowedCountries);
            bypassPaths = bypassPaths == null ? List.of() : List.copyOf(bypassPaths);
        }
    }

    /**
     * @param trustedHops 0 = first X-Forwarded-For entry (the edge overwrites the header);
     *                    N = N-th entry from the right, for proxies that append instead
     */
    public record ClientIp(@DefaultValue("0") int trustedHops) {
    }

    /** @param publicPaths paths reachable without a session; a valid token is still resolved when present */
    public record Auth(List<String> publicPaths) {

        public Auth {
            publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        }
    }

    /**
     * Waiting room: once the online estimate reaches {@code maxOnline}, new entries to the protected paths queue.
     *
     * @param enabled            gate protected paths at capacity (online tracking always runs, for the gauge)
     * @param maxOnline          capacity in distinct players active within {@code onlineWindow}
     * @param onlineWindow       a player counts as online when authenticated traffic was seen within this window
     * @param protectedPaths     entry points only (login, game launch); in-game traffic and wallet are never gated
     * @param admitRatePerSecond max players let in from the queue per second, cluster-wide
     * @param passTtl            lifetime of an admission pass (refreshed on every admitted protected request)
     * @param ticketTtl          how long a queue ticket stays redeemable
     * @param passSecret         HMAC key for tickets and passes, shared by all gateway pods, at least 32 chars
     */
    public record Admission(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1000000") long maxOnline,
            @DefaultValue("5m") Duration onlineWindow,
            @DefaultValue({"/api/user/login", "/api/lobby/games/*/launch"}) List<String> protectedPaths,
            @DefaultValue("2000") int admitRatePerSecond,
            @DefaultValue("30m") Duration passTtl,
            @DefaultValue("1h") Duration ticketTtl,
            String passSecret) {

        public Admission {
            protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
        }

        /** Keeps the secret out of logs. */
        @Override
        public String toString() {
            return "Admission[enabled=" + enabled + ", maxOnline=" + maxOnline + ", onlineWindow=" + onlineWindow
                    + ", protectedPaths=" + protectedPaths + ", admitRatePerSecond=" + admitRatePerSecond
                    + ", passTtl=" + passTtl + ", ticketTtl=" + ticketTtl + ", passSecret=***]";
        }
    }
}

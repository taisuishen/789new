package com.bingo789.gateway.admission;

import com.bingo789.gateway.admission.AdmissionTokens.Binding;
import com.bingo789.gateway.admission.AdmissionTokens.Claims;
import com.bingo789.gateway.admission.AdmissionTokens.Kind;
import com.bingo789.gateway.admission.AdmissionTokens.Subject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-09-30T04:00:00Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofMinutes(30));
    private static final String USER = "1234567890";
    private static final String IP = "203.0.113.7";

    private final AdmissionTokens tokens = new AdmissionTokens(SECRET);

    @Test
    void userBoundPassRoundTrips() {
        String pass = tokens.issue(Kind.PASS, 42, EXPIRES, Subject.of(USER, IP));

        Claims claims = tokens.verify(pass, Kind.PASS, NOW, USER, "198.51.100.1");

        assertThat(claims).isEqualTo(new Claims(Kind.PASS, 42, EXPIRES, Binding.USER));
        assertThat(pass).doesNotContain(USER).matches("[A-Za-z0-9._-]+");
    }

    @Test
    void passIsBoundToTheUser() {
        String pass = tokens.issue(Kind.PASS, 42, EXPIRES, Subject.of(USER, IP));

        assertThat(tokens.verify(pass, Kind.PASS, NOW, "999", IP)).isNull();
        assertThat(tokens.verify(pass, Kind.PASS, NOW, null, IP)).isNull();
    }

    @Test
    void anonymousPassIsBoundToTheClientIp() {
        String pass = tokens.issue(Kind.PASS, 7, EXPIRES, Subject.of(null, IP));

        assertThat(tokens.verify(pass, Kind.PASS, NOW, null, IP)).isNotNull();
        // still valid once the player is logged in, from the same IP
        assertThat(tokens.verify(pass, Kind.PASS, NOW, USER, IP)).isNotNull();
        assertThat(tokens.verify(pass, Kind.PASS, NOW, null, "198.51.100.1")).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        String pass = tokens.issue(Kind.PASS, 42, EXPIRES, Subject.of(USER, IP));

        assertThat(tokens.verify(pass, Kind.PASS, EXPIRES.minusSeconds(1), USER, IP)).isNotNull();
        assertThat(tokens.verify(pass, Kind.PASS, EXPIRES, USER, IP)).isNull();
    }

    @Test
    void ticketIsNotAPass() {
        String ticket = tokens.issue(Kind.TICKET, 42, EXPIRES, Subject.of(USER, IP));

        assertThat(tokens.verify(ticket, Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify(ticket, Kind.TICKET, NOW, USER, IP)).isNotNull();
    }

    @Test
    void tamperedTokensAreRejected() {
        String pass = tokens.issue(Kind.PASS, 42, EXPIRES, Subject.of(USER, IP));
        String[] parts = pass.split("\\.");

        String lowerNumber = String.join(".", parts[0], "1", parts[2], parts[3], parts[4]);
        String longerExpiry = String.join(".", parts[0], parts[1], Long.toString(EXPIRES.getEpochSecond() + 3600), parts[3], parts[4]);
        String rebound = String.join(".", parts[0], parts[1], parts[2], "i", parts[4]);

        assertThat(tokens.verify(lowerNumber, Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify(longerExpiry, Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify(rebound, Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(new AdmissionTokens(SECRET.toUpperCase()).verify(pass, Kind.PASS, NOW, USER, IP)).isNull();
    }

    @Test
    void malformedTokensAreRejected() {
        assertThat(tokens.verify(null, Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify("", Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify("P.1.2.u", Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify("P.1.2.x.sig", Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify("P.a.b.u.sig.extra", Kind.PASS, NOW, USER, IP)).isNull();
        assertThat(tokens.verify("P." + "9".repeat(200), Kind.PASS, NOW, USER, IP)).isNull();
    }

    @Test
    void shortOrBlankSecretIsRefused() {
        assertThatThrownBy(() -> new AdmissionTokens(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionTokens(" ".repeat(40))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionTokens("x".repeat(31))).isInstanceOf(IllegalArgumentException.class);
    }
}

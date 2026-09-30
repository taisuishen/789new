package com.bingo789.game.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CidrMatcherTest {

    @Test
    void matchesIpv4Ranges() {
        CidrMatcher matcher = CidrMatcher.of(List.of("10.0.0.0/8", "203.0.113.10/32", "192.168.1.0/25"));

        assertThat(matcher.matches("10.20.30.40")).isTrue();
        assertThat(matcher.matches("203.0.113.10")).isTrue();
        assertThat(matcher.matches("203.0.113.11")).isFalse();
        assertThat(matcher.matches("192.168.1.127")).isTrue();
        assertThat(matcher.matches("192.168.1.128")).isFalse();
    }

    @Test
    void matchesIpv6AndRejectsGarbage() {
        CidrMatcher matcher = CidrMatcher.of(List.of("2001:db8::/32"));

        assertThat(matcher.matches("2001:db8:1::1")).isTrue();
        assertThat(matcher.matches("2001:db9::1")).isFalse();
        assertThat(matcher.matches("not-an-ip")).isFalse();
        assertThat(matcher.matches(null)).isFalse();
    }
}

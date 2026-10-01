package com.bingo789.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** @param ttl how long a launch token stays valid after its last use (verification extends it) */
@ConfigurationProperties("bingo.game-token")
public record GameTokenProperties(@DefaultValue("4h") Duration ttl) {
}

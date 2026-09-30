package com.bingo789.common.mybatis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param forceMaster route every statement to the primary node (wallet sets this to true)
 * @param masterHint  TaurusDB proxy hint that pins a statement to the primary node
 * @param workerId    snowflake worker id, unique per instance; -1 derives one from the hostname (dev only)
 */
@ConfigurationProperties("bingo.mybatis")
public record BingoMybatisProperties(
        @DefaultValue("false") boolean forceMaster,
        @DefaultValue("/*FORCE_MASTER*/") String masterHint,
        @DefaultValue("-1") long workerId) {
}

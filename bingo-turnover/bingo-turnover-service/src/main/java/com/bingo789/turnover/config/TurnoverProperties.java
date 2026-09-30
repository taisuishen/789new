package com.bingo789.turnover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * @param defaultDepositMultiplier deposit play-through (AML) for lines / currencies without a turnover_setting row:
 *                                 every deposit creates an ALL-games bucket of amount x multiplier (0 = none)
 * @param recordKeepMonths         full months of turnover_record kept before the current one (turnoverPartitionJob)
 */
@ConfigurationProperties("bingo.turnover")
public record TurnoverProperties(@DefaultValue("1.0") BigDecimal defaultDepositMultiplier,
                                 @DefaultValue("6") int recordKeepMonths) {
}

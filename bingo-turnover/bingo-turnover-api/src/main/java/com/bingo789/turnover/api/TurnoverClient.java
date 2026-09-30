package com.bingo789.turnover.api;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "bingo-turnover", contextId = "turnoverClient")
public interface TurnoverClient extends TurnoverApi {
}

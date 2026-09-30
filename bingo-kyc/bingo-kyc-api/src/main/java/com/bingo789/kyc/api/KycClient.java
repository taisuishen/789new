package com.bingo789.kyc.api;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "bingo-kyc", contextId = "kycClient")
public interface KycClient extends KycApi {
}

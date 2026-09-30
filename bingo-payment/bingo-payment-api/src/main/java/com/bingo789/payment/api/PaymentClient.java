package com.bingo789.payment.api;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "bingo-payment", contextId = "paymentClient")
public interface PaymentClient extends PaymentApi {
}

package com.bingo789.user.api;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "bingo-user", contextId = "userClient")
public interface UserClient extends UserApi {
}

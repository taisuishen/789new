package com.bingo789.user.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.user.service.AccountService;
import com.bingo789.user.support.Tokens;
import com.bingo789.user.web.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ProfileController {

    private final AccountService accountService;

    /** Also extends the session TTL (the gateway only reads sessions, it never refreshes them). */
    @GetMapping("/me")
    public Result<MeResponse> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return Result.ok(accountService.me(CurrentUser.requireUserId(), Tokens.fromBearerHeader(authorization)));
    }
}

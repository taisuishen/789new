package com.bingo789.user.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.user.service.AccountService;
import com.bingo789.user.service.ClientInfo;
import com.bingo789.user.support.Tokens;
import com.bingo789.user.web.dto.LoginRequest;
import com.bingo789.user.web.dto.LoginResponse;
import com.bingo789.user.web.dto.RegisterRequest;
import com.bingo789.user.web.dto.RegisterResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** register and login are public paths at the gateway; logout requires a session. */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accountService;

    @PostMapping("/register")
    public Result<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(accountService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        CurrentUser client = CurrentUser.get();
        ClientInfo info = client == null
                ? new ClientInfo(null, null, userAgent, null)
                : new ClientInfo(client.clientIp(), client.deviceId(), userAgent, client.countryCode());
        return Result.ok(accountService.login(request, info));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser.requireUserId();
        String token = Tokens.fromBearerHeader(authorization);
        if (token != null) {
            accountService.logout(token);
        }
        return Result.ok();
    }
}

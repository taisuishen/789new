package com.bingo789.payment.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.payment.common.PageResult;
import com.bingo789.payment.service.DepositService;
import com.bingo789.payment.web.dto.CreateDepositRequest;
import com.bingo789.payment.web.dto.DepositCreatedView;
import com.bingo789.payment.web.dto.DepositOrderView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment/deposits")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService depositService;

    // TODO: accept a client request id so a double-submitted form does not create two orders.
    @PostMapping
    public Result<DepositCreatedView> create(@Valid @RequestBody CreateDepositRequest request) {
        long userId = CurrentUser.requireUserId();
        CurrentUser user = CurrentUser.get();
        return Result.ok(depositService.create(userId, request, user.clientIp(), user.deviceId()));
    }

    @GetMapping
    public Result<PageResult<DepositOrderView>> list(@RequestParam(value = "page", defaultValue = "1") long page,
                                                     @RequestParam(value = "size", defaultValue = "20") long size) {
        return Result.ok(depositService.listOwn(CurrentUser.requireUserId(), page, size));
    }
}

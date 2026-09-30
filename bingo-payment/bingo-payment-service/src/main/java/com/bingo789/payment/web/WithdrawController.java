package com.bingo789.payment.web;

import com.bingo789.common.core.Result;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.payment.common.PageResult;
import com.bingo789.payment.service.WithdrawService;
import com.bingo789.payment.web.dto.CreateWithdrawRequest;
import com.bingo789.payment.web.dto.WithdrawOrderView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment/withdrawals")
@RequiredArgsConstructor
public class WithdrawController {

    private final WithdrawService withdrawService;

    /** Returns PENDING_AUDIT normally, or CREATED when the wallet freeze is still being confirmed. */
    @PostMapping
    public Result<WithdrawOrderView> create(@Valid @RequestBody CreateWithdrawRequest request) {
        long userId = CurrentUser.requireUserId();
        CurrentUser user = CurrentUser.get();
        return Result.ok(withdrawService.create(userId, request, user.clientIp(), user.deviceId()));
    }

    @GetMapping
    public Result<PageResult<WithdrawOrderView>> list(@RequestParam(value = "page", defaultValue = "1") long page,
                                                      @RequestParam(value = "size", defaultValue = "20") long size) {
        return Result.ok(withdrawService.listOwn(CurrentUser.requireUserId(), page, size));
    }
}

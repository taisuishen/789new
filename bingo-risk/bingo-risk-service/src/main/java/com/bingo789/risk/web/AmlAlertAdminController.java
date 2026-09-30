package com.bingo789.risk.web;

import com.bingo789.common.core.Result;
import com.bingo789.risk.common.Operators;
import com.bingo789.risk.common.PageResult;
import com.bingo789.risk.domain.AmlAlertStatus;
import com.bingo789.risk.service.AmlAlertService;
import com.bingo789.risk.web.dto.AmlAlertView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

// TODO: RBAC + audit log (AML data: compliance officers only)
@RestController
@RequestMapping("/admin/risk/aml-alerts")
@RequiredArgsConstructor
public class AmlAlertAdminController {

    private final AmlAlertService amlAlertService;

    @GetMapping
    public Result<PageResult<AmlAlertView>> list(@RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "page", defaultValue = "1") long page,
                                                 @RequestParam(value = "size", defaultValue = "20") long size,
                                                 @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        Operators.require(operator);
        AmlAlertStatus filter = status == null || status.isBlank() ? null : AmlAlertStatus.valueOf(status.toUpperCase(Locale.ROOT));
        return Result.ok(amlAlertService.list(filter, page, size));
    }
}

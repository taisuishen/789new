package com.bingo789.promotion.web;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.Result;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.promotion.common.Operators;
import com.bingo789.promotion.common.PageResult;
import com.bingo789.promotion.service.PromotionAdminService;
import com.bingo789.promotion.web.dto.PromotionAdminView;
import com.bingo789.promotion.web.dto.PromotionCreateRequest;
import com.bingo789.promotion.web.dto.PromotionStatusRequest;
import com.bingo789.promotion.web.dto.PromotionUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Promotion management for the back office (internal: blocked by the player gateway). The operator comes from
 * {@link Operators#HEADER}, set by the back-office gateway.
 */
// TODO: RBAC + audit log
@RestController
@RequestMapping("/internal/promotion/activities")
@RequiredArgsConstructor
public class PromotionAdminController {

    private final PromotionAdminService adminService;

    /** @param lines the viewer's lines ("1,2", or "*" for every line); a promotion is listed when it targets any of them */
    @GetMapping
    public Result<PageResult<PromotionAdminView>> list(@RequestParam("lines") String lines,
                                                       @RequestParam(value = "status", required = false) String status,
                                                       @RequestParam(value = "type", required = false) String type,
                                                       @RequestParam(value = "page", defaultValue = "1") long page,
                                                       @RequestParam(value = "size", defaultValue = "20") long size,
                                                       @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        Operators.require(operator);
        return Result.ok(adminService.list(parseScope(lines), status, type, page, size));
    }

    @PostMapping
    public Result<PromotionAdminView> create(@RequestBody PromotionCreateRequest request,
                                             @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return Result.ok(adminService.create(request, Operators.require(operator)));
    }

    @PutMapping("/{id}")
    public Result<PromotionAdminView> update(@PathVariable("id") long id, @RequestBody PromotionUpdateRequest request,
                                             @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return Result.ok(adminService.update(id, request, Operators.require(operator)));
    }

    @PutMapping("/{id}/status")
    public Result<PromotionAdminView> changeStatus(@PathVariable("id") long id, @RequestBody PromotionStatusRequest request,
                                                   @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return Result.ok(adminService.changeStatus(id, request, Operators.require(operator)));
    }

    private static LineScope parseScope(String lines) {
        try {
            return LineScope.parse(lines);
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "lines must be \"*\" or a comma-separated list of lines 1..99");
        }
    }
}

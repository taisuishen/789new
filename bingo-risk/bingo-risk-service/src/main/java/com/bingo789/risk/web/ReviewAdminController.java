package com.bingo789.risk.web;

import com.bingo789.common.core.Result;
import com.bingo789.risk.common.Operators;
import com.bingo789.risk.common.PageResult;
import com.bingo789.risk.domain.ReviewStatus;
import com.bingo789.risk.service.ReviewService;
import com.bingo789.risk.web.dto.ReviewDecisionRequest;
import com.bingo789.risk.web.dto.ReviewDecisionView;
import com.bingo789.risk.web.dto.ReviewTaskView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

// TODO: RBAC + audit log
@RestController
@RequestMapping("/admin/risk/reviews")
@RequiredArgsConstructor
public class ReviewAdminController {

    private final ReviewService reviewService;

    @GetMapping
    public Result<PageResult<ReviewTaskView>> list(@RequestParam(value = "status", required = false) String status,
                                                   @RequestParam(value = "page", defaultValue = "1") long page,
                                                   @RequestParam(value = "size", defaultValue = "20") long size,
                                                   @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        Operators.require(operator);
        ReviewStatus filter = status == null || status.isBlank() ? null : ReviewStatus.valueOf(status.toUpperCase(Locale.ROOT));
        return Result.ok(reviewService.list(filter, page, size));
    }

    @PostMapping("/{id}/decision")
    public Result<ReviewDecisionView> decide(@PathVariable("id") long id,
                                             @Valid @RequestBody ReviewDecisionRequest request,
                                             @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return Result.ok(reviewService.decide(id, request.decision(), request.reason(), Operators.require(operator)));
    }
}

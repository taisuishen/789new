package com.bingo789.reconcile.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.Result;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.reconcile.ReconcileErrorCode;
import com.bingo789.reconcile.entity.DiffStatus;
import com.bingo789.reconcile.entity.ReconDiff;
import com.bingo789.reconcile.entity.ReconLevel;
import com.bingo789.reconcile.mapper.GgrDailyMapper;
import com.bingo789.reconcile.mapper.ReconDiffMapper;
import com.bingo789.reconcile.web.dto.DiffView;
import com.bingo789.reconcile.web.dto.GgrView;
import com.bingo789.reconcile.web.dto.PageView;
import com.bingo789.reconcile.web.dto.ResolveDiffRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/** Back-office API, exposed only on the back-office ingress. Reads are served by TaurusDB read replicas. */
// TODO: RBAC + audit log (who resolved/ignored which diff)
@Slf4j
@RestController
@RequestMapping("/admin/reconcile")
@RequiredArgsConstructor
public class ReconcileAdminController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_GGR_RANGE_DAYS = 366;

    private final ReconDiffMapper diffMapper;
    private final GgrDailyMapper ggrMapper;

    @GetMapping("/diffs")
    public Result<PageView<DiffView>> diffs(@RequestParam(name = "status", required = false) String status,
                                            @RequestParam(name = "level", required = false) String level,
                                            @RequestParam(name = "provider", required = false) String provider,
                                            @RequestParam(name = "page", defaultValue = "1") int page,
                                            @RequestParam(name = "size", defaultValue = "50") int size) {
        DiffStatus statusFilter = parseEnum(DiffStatus.class, status, "status");
        ReconLevel levelFilter = parseEnum(ReconLevel.class, level, "level");
        IPage<ReconDiff> result = diffMapper.selectPage(new Page<>(Math.max(page, 1), Math.clamp(size, 1, MAX_PAGE_SIZE)),
                Wrappers.<ReconDiff>lambdaQuery()
                        .eq(statusFilter != null, ReconDiff::getStatus, statusFilter)
                        .eq(levelFilter != null, ReconDiff::getLevel, levelFilter)
                        .eq(provider != null && !provider.isBlank(), ReconDiff::getProviderCode, provider)
                        .orderByDesc(ReconDiff::getId));
        return Result.ok(new PageView<>(result.getRecords().stream().map(DiffView::of).toList(),
                result.getTotal(), result.getCurrent(), result.getSize()));
    }

    @PostMapping("/diffs/{id}/resolve")
    public Result<Void> resolve(@PathVariable("id") long id, @Valid @RequestBody ResolveDiffRequest request) {
        int updated = diffMapper.resolve(id, request.status(), request.note(), LocalDateTime.now(BingoTime.ZONE));
        if (updated == 0) {
            BizException.check(diffMapper.selectById(id) != null, ReconcileErrorCode.DIFF_NOT_FOUND);
            throw new BizException(ReconcileErrorCode.DIFF_ALREADY_CLOSED);
        }
        log.info("recon diff {} marked {}: {}", id, request.status(), request.note());
        return Result.ok();
    }

    /** GGR per reporting day, provider and currency; both dates inclusive. */
    @GetMapping("/ggr")
    public Result<List<GgrView>> ggr(@RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(name = "provider", required = false) String provider) {
        BizException.check(!to.isBefore(from), CommonErrorCode.BAD_REQUEST, "to must not be before from");
        BizException.check(ChronoUnit.DAYS.between(from, to) < MAX_GGR_RANGE_DAYS, ReconcileErrorCode.DATE_RANGE_TOO_LARGE);
        return Result.ok(ggrMapper.sumByDayProviderCurrency(from, to, provider).stream().map(GgrView::of).toList());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "invalid " + name + ": " + value);
        }
    }
}

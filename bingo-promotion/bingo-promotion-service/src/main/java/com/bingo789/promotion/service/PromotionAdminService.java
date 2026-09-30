package com.bingo789.promotion.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.line.LineScope;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.promotion.common.PageResult;
import com.bingo789.promotion.common.PromotionErrorCode;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionEntry;
import com.bingo789.promotion.domain.PromotionStatus;
import com.bingo789.promotion.domain.PromotionType;
import com.bingo789.promotion.mapper.PromotionMapper;
import com.bingo789.promotion.terms.PromotionTerms;
import com.bingo789.promotion.web.dto.PromotionAdminView;
import com.bingo789.promotion.web.dto.PromotionCreateRequest;
import com.bingo789.promotion.web.dto.PromotionStatusRequest;
import com.bingo789.promotion.web.dto.PromotionUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Back-office management of promotions. Every change bumps the version; update and status change carry the version
 * the operator saw, so a stale edit (or approving a version other than the one reviewed) is refused.
 * Other pods pick changes up with their next catalog refresh.
 * TODO: RBAC by line (an operator may only manage promotions within their own lines), four-eyes approval, audit log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionAdminService {

    static final int MAX_NAME_LENGTH = 128;
    /** Bounds the catalog every pod keeps in memory and the player-facing payload. */
    static final int MAX_CONFIG_LENGTH = 65_535;

    private final PromotionMapper promotionMapper;

    /** Promotions whose user_lines intersect the viewer's lines, newest first. */
    public PageResult<PromotionAdminView> list(LineScope scope, String status, String type, long page, long size) {
        PromotionStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            statusFilter = PromotionStatus.parse(status);
            BizException.check(statusFilter != null, CommonErrorCode.BAD_REQUEST, "unknown status " + status);
        }
        String typeFilter = type == null || type.isBlank() ? null : type.trim().toUpperCase(Locale.ROOT);
        String scopeJson = scope.isAll() ? null : JsonUtils.toJson(scope.lines().stream().sorted().toList());
        Page<Promotion> request = PageResult.request(page, size);
        Page<Promotion> result = promotionMapper.selectPage(request, Wrappers.<Promotion>lambdaQuery()
                .eq(statusFilter != null, Promotion::getStatus, statusFilter)
                .eq(typeFilter != null, Promotion::getPromoType, typeFilter)
                .apply(scopeJson != null, "JSON_OVERLAPS(user_lines, CAST({0} AS JSON))", scopeJson)
                .orderByDesc(Promotion::getId));
        return PageResult.of(result, PromotionAdminView::of);
    }

    /** Creates a DRAFT; it takes a status change to put it ONLINE. */
    public PromotionAdminView create(PromotionCreateRequest request, String operator) {
        PromotionType type = PromotionType.parse(request.type());
        require(type != null, "type must be one of " + Arrays.toString(PromotionType.values()));
        Promotion promotion = new Promotion();
        promotion.setPromoType(type.name());
        applyFields(promotion, type, request.name(), request.userLines(), request.startTime(), request.endTime(),
                request.sort(), request.config());
        promotion.setStatus(PromotionStatus.DRAFT);
        promotion.setVersion(1);
        promotion.setCreatedBy(operator);
        promotion.setUpdatedBy(operator);
        LocalDateTime now = BingoTime.now();
        promotion.setCreatedAt(now);
        promotion.setUpdatedAt(now);
        promotionMapper.insert(promotion);
        log.info("promotion {} ({}, lines {}) created by {}", promotion.getId(), type, promotion.getUserLines(), operator);
        return PromotionAdminView.of(promotion);
    }

    /** Replaces the editable fields of the version the operator edited. */
    public PromotionAdminView update(long id, PromotionUpdateRequest request, String operator) {
        require(request.version() != null, "version is required");
        Promotion current = MasterRoute.run(() -> promotionMapper.selectById(id));
        BizException.check(current != null, PromotionErrorCode.PROMOTION_NOT_FOUND);
        PromotionType type = supportedType(current);
        Promotion changed = new Promotion();
        changed.setId(id);
        applyFields(changed, type, request.name(), request.userLines(), request.startTime(), request.endTime(),
                request.sort(), request.config());
        changed.setUpdatedBy(operator);
        int updated = promotionMapper.updateVersioned(changed, request.version());
        BizException.check(updated == 1, PromotionErrorCode.VERSION_CONFLICT);
        log.info("promotion {} updated from version {} by {}", id, request.version(), operator);
        return PromotionAdminView.of(MasterRoute.run(() -> promotionMapper.selectById(id)));
    }

    /** DRAFT -> ONLINE / OFFLINE, ONLINE <-> OFFLINE; the same status again is a no-op. */
    public PromotionAdminView changeStatus(long id, PromotionStatusRequest request, String operator) {
        PromotionStatus target = PromotionStatus.parse(request.status());
        require(target != null, "status must be one of " + Arrays.toString(PromotionStatus.values()));
        require(request.version() != null, "version is required");
        Promotion current = MasterRoute.run(() -> promotionMapper.selectById(id));
        BizException.check(current != null, PromotionErrorCode.PROMOTION_NOT_FOUND);
        BizException.check(request.version().equals(current.getVersion()), PromotionErrorCode.VERSION_CONFLICT);
        if (current.getStatus() == target) {
            return PromotionAdminView.of(current);
        }
        BizException.check(current.getStatus().canMoveTo(target), PromotionErrorCode.INVALID_STATUS_CHANGE,
                current.getStatus() + " -> " + target + " is not allowed");
        if (target == PromotionStatus.ONLINE) {
            // from now on this code applies the terms: re-check the stored row against the current rules
            PromotionType type = supportedType(current);
            try {
                PromotionEntry.parseLines(current.getUserLines());
                PromotionTerms.validate(type, JsonUtils.mapper().readTree(current.getConfigJson()));
            } catch (IllegalArgumentException e) {
                throw new BizException(PromotionErrorCode.INVALID_PROMOTION, e.getMessage());
            }
        }
        int updated = promotionMapper.updateStatus(id, target, operator, request.version());
        BizException.check(updated == 1, PromotionErrorCode.VERSION_CONFLICT);
        log.warn("promotion {} version {}: {} -> {} by {}", id, request.version(), current.getStatus(), target, operator);
        return PromotionAdminView.of(MasterRoute.run(() -> promotionMapper.selectById(id)));
    }

    private static void applyFields(Promotion target, PromotionType type, String name, List<Integer> userLines,
                                    Instant startTime, Instant endTime, Integer sort, JsonNode config) {
        String trimmed = name == null ? "" : name.strip();
        require(!trimmed.isEmpty() && trimmed.length() <= MAX_NAME_LENGTH,
                "name is required and allows at most " + MAX_NAME_LENGTH + " characters");
        require(userLines != null && !userLines.isEmpty(), "userLines must list at least one line");
        for (Integer line : userLines) {
            require(line != null && UserLine.isValid(line), "userLines must hold lines " + UserLine.MIN + ".." + UserLine.MAX);
        }
        require(startTime != null && endTime != null, "startTime and endTime are required");
        // DATETIME(3): truncate here, MySQL would round
        LocalDateTime start = BingoTime.toLocal(startTime).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime end = BingoTime.toLocal(endTime).truncatedTo(ChronoUnit.MILLIS);
        require(start.isBefore(end), "startTime must be before endTime");
        target.setName(trimmed);
        target.setUserLines(JsonUtils.toJson(new TreeSet<>(userLines)));
        target.setStartTime(start);
        target.setEndTime(end);
        target.setSort(sort == null ? 0 : sort);
        target.setConfigJson(validConfig(type, config));
    }

    private static String validConfig(PromotionType type, JsonNode config) {
        try {
            PromotionTerms.validate(type, config);
        } catch (IllegalArgumentException e) {
            throw new BizException(PromotionErrorCode.INVALID_PROMOTION, e.getMessage());
        }
        String json = JsonUtils.toJson(config);
        require(json.length() <= MAX_CONFIG_LENGTH, "config allows at most " + MAX_CONFIG_LENGTH + " characters");
        return json;
    }

    /** A row of a type this version does not know (written by a newer version) is not edited here. */
    private static PromotionType supportedType(Promotion promotion) {
        PromotionType type = PromotionType.parse(promotion.getPromoType());
        require(type != null, "promotion type " + promotion.getPromoType() + " is not supported by this version");
        return type;
    }

    private static void require(boolean condition, String message) {
        BizException.check(condition, PromotionErrorCode.INVALID_PROMOTION, message);
    }
}

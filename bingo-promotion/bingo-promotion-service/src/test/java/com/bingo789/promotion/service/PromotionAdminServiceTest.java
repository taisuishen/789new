package com.bingo789.promotion.service;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.ErrorCode;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.PromotionFixtures;
import com.bingo789.promotion.common.PromotionErrorCode;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionStatus;
import com.bingo789.promotion.mapper.PromotionMapper;
import com.bingo789.promotion.web.dto.PromotionAdminView;
import com.bingo789.promotion.web.dto.PromotionCreateRequest;
import com.bingo789.promotion.web.dto.PromotionStatusRequest;
import com.bingo789.promotion.web.dto.PromotionUpdateRequest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static com.bingo789.promotion.PromotionFixtures.FIRST_DEPOSIT_CONFIG;
import static com.bingo789.promotion.PromotionFixtures.REBATE_CONFIG;
import static com.bingo789.promotion.PromotionFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionAdminServiceTest {

    private static final long ID = 7L;
    private static final Instant START = LocalDateTime.of(2026, 10, 1, 0, 0).toInstant(BingoTime.ZONE);
    private static final Instant END = LocalDateTime.of(2026, 11, 1, 0, 0).toInstant(BingoTime.ZONE);

    private PromotionMapper mapper;
    private PromotionAdminService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PromotionMapper.class);
        when(mapper.insert(any(Promotion.class))).thenAnswer(inv -> {
            inv.<Promotion>getArgument(0).setId(ID);
            return 1;
        });
        service = new PromotionAdminService(mapper);
    }

    @Test
    void createStoresADraftWithSortedDistinctLinesAndTheOperator() {
        PromotionAdminView view = service.create(new PromotionCreateRequest("  Welcome bonus ", "first_deposit",
                List.of(2, 1, 2), START, END, null, json(FIRST_DEPOSIT_CONFIG)), "op-1");

        ArgumentCaptor<Promotion> inserted = ArgumentCaptor.forClass(Promotion.class);
        verify(mapper).insert(inserted.capture());
        Promotion row = inserted.getValue();
        assertThat(row.getName()).isEqualTo("Welcome bonus");
        assertThat(row.getPromoType()).isEqualTo("FIRST_DEPOSIT");
        assertThat(row.getUserLines()).isEqualTo("[1,2]");
        assertThat(row.getStatus()).isEqualTo(PromotionStatus.DRAFT);
        assertThat(row.getStartTime()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(row.getEndTime()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
        assertThat(row.getSort()).isZero();
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getCreatedBy()).isEqualTo("op-1");
        assertThat(row.getUpdatedBy()).isEqualTo("op-1");
        assertThat(json(row.getConfigJson())).isEqualTo(json(FIRST_DEPOSIT_CONFIG));
        assertThat(view.id()).isEqualTo(String.valueOf(ID));
        assertThat(view.userLines()).containsExactly(1, 2);
        assertThat(view.status()).isEqualTo(PromotionStatus.DRAFT);
    }

    @Test
    void createRejectsInvalidInputWithoutWriting() {
        assertInvalid(create(" ", "REBATE", List.of(1), START, END, REBATE_CONFIG), "name");
        assertInvalid(create("x".repeat(129), "REBATE", List.of(1), START, END, REBATE_CONFIG), "name");
        assertInvalid(create("n", "CHECKIN", List.of(1), START, END, REBATE_CONFIG), "type");
        assertInvalid(create("n", null, List.of(1), START, END, REBATE_CONFIG), "type");
        assertInvalid(create("n", "REBATE", List.of(), START, END, REBATE_CONFIG), "userLines");
        assertInvalid(create("n", "REBATE", null, START, END, REBATE_CONFIG), "userLines");
        assertInvalid(create("n", "REBATE", List.of(1, 0), START, END, REBATE_CONFIG), "userLines");
        assertInvalid(create("n", "REBATE", List.of(100), START, END, REBATE_CONFIG), "userLines");
        assertInvalid(create("n", "REBATE", List.of(1), START, START, REBATE_CONFIG), "startTime must be before endTime");
        assertInvalid(create("n", "REBATE", List.of(1), END, START, REBATE_CONFIG), "startTime must be before endTime");
        assertInvalid(create("n", "REBATE", List.of(1), null, END, REBATE_CONFIG), "startTime");
        assertInvalid(create("n", "REBATE", List.of(1), START, END, null), "config must be a JSON object");
        assertInvalid(create("n", "REBATE", List.of(1), START, END, "\"text\""), "config must be a JSON object");
        // type-specific terms: the FIRST_DEPOSIT schema is not a valid REBATE config
        assertInvalid(create("n", "REBATE", List.of(1), START, END, FIRST_DEPOSIT_CONFIG), "config.percent is not a known setting");
        assertInvalid(create("n", "FIRST_DEPOSIT", List.of(1), START, END, """
                {"percent":100,"maxAmount":1000,"turnover":{"multiplier":10},"display":"banner"}"""), "config.display");
        assertInvalid(create("n", "FIRST_DEPOSIT", List.of(1), START, END, """
                {"percent":100,"maxAmount":1000,"turnover":{"multiplier":10,"scope":"GAME_TYPE","scopeValue":"CARDS"}}"""),
                "unknown game type CARDS");

        verify(mapper, never()).insert(any(Promotion.class));
    }

    @Test
    void createRejectsAnOversizedConfig() {
        String config = "{\"defaultRate\":0.005,\"turnover\":{\"multiplier\":1},\"display\":{\"text\":\""
                + "x".repeat(PromotionAdminService.MAX_CONFIG_LENGTH) + "\"}}";

        assertInvalid(create("n", "REBATE", List.of(1), START, END, config), "config allows at most");
    }

    @Test
    void updateWithTheEditedVersionReplacesTheEditableFields() {
        when(mapper.selectById(ID)).thenReturn(stored(PromotionStatus.ONLINE, 3), stored(PromotionStatus.ONLINE, 4));
        when(mapper.updateVersioned(any(Promotion.class), eq(3))).thenReturn(1);

        PromotionAdminView view = service.update(ID, new PromotionUpdateRequest("Rebate v2", List.of(3, 1), START, END, 5,
                json(REBATE_CONFIG), 3), "op-2");

        ArgumentCaptor<Promotion> changed = ArgumentCaptor.forClass(Promotion.class);
        verify(mapper).updateVersioned(changed.capture(), eq(3));
        assertThat(changed.getValue().getId()).isEqualTo(ID);
        assertThat(changed.getValue().getName()).isEqualTo("Rebate v2");
        assertThat(changed.getValue().getUserLines()).isEqualTo("[1,3]");
        assertThat(changed.getValue().getSort()).isEqualTo(5);
        assertThat(changed.getValue().getUpdatedBy()).isEqualTo("op-2");
        assertThat(view.version()).isEqualTo(4);
    }

    @Test
    void updateWithAStaleVersionIsAConflict() {
        when(mapper.selectById(ID)).thenReturn(stored(PromotionStatus.ONLINE, 3));
        when(mapper.updateVersioned(any(Promotion.class), eq(2))).thenReturn(0);

        assertError(() -> service.update(ID, new PromotionUpdateRequest("Rebate", List.of(1), START, END, 0,
                json(REBATE_CONFIG), 2), "op-2"), PromotionErrorCode.VERSION_CONFLICT);
    }

    @Test
    void updateIsValidatedAgainstTheStoredType() {
        when(mapper.selectById(ID)).thenReturn(stored(PromotionStatus.DRAFT, 1));

        assertError(() -> service.update(ID, new PromotionUpdateRequest("Rebate", List.of(1), START, END, 0,
                json(FIRST_DEPOSIT_CONFIG), 1), "op-2"), PromotionErrorCode.INVALID_PROMOTION);
        assertError(() -> service.update(ID, new PromotionUpdateRequest("Rebate", List.of(1), START, END, 0,
                json(REBATE_CONFIG), null), "op-2"), PromotionErrorCode.INVALID_PROMOTION);
        verify(mapper, never()).updateVersioned(any(Promotion.class), anyInt());
    }

    @Test
    void updateOfAnUnknownPromotionIsNotFound() {
        assertError(() -> service.update(ID, new PromotionUpdateRequest("Rebate", List.of(1), START, END, 0,
                json(REBATE_CONFIG), 1), "op-2"), PromotionErrorCode.PROMOTION_NOT_FOUND);
    }

    @Test
    void putOnlineApprovesTheReviewedVersion() {
        when(mapper.selectById(ID)).thenReturn(stored(PromotionStatus.DRAFT, 2), stored(PromotionStatus.ONLINE, 3));
        when(mapper.updateStatus(ID, PromotionStatus.ONLINE, "op-3", 2)).thenReturn(1);

        PromotionAdminView view = service.changeStatus(ID, new PromotionStatusRequest("ONLINE", 2), "op-3");

        assertThat(view.status()).isEqualTo(PromotionStatus.ONLINE);
        assertThat(view.version()).isEqualTo(3);
    }

    @Test
    void statusChangeChecksVersionAndLifecycle() {
        when(mapper.selectById(ID)).thenReturn(stored(PromotionStatus.ONLINE, 2));

        // the operator reviewed version 1, someone changed it since
        assertError(() -> service.changeStatus(ID, new PromotionStatusRequest("OFFLINE", 1), "op-3"),
                PromotionErrorCode.VERSION_CONFLICT);
        assertError(() -> service.changeStatus(ID, new PromotionStatusRequest("DRAFT", 2), "op-3"),
                PromotionErrorCode.INVALID_STATUS_CHANGE);
        assertError(() -> service.changeStatus(ID, new PromotionStatusRequest("LIVE", 2), "op-3"),
                PromotionErrorCode.INVALID_PROMOTION);
        // same status again: nothing to do
        assertThat(service.changeStatus(ID, new PromotionStatusRequest("online", 2), "op-3").version()).isEqualTo(2);
        verify(mapper, never()).updateStatus(anyLong(), any(), anyString(), anyInt());
    }

    @Test
    void concurrentStatusChangeLosesOnTheVersion() {
        when(mapper.selectById(ID)).thenReturn(stored(PromotionStatus.ONLINE, 2));
        when(mapper.updateStatus(ID, PromotionStatus.OFFLINE, "op-3", 2)).thenReturn(0);

        assertError(() -> service.changeStatus(ID, new PromotionStatusRequest("OFFLINE", 2), "op-3"),
                PromotionErrorCode.VERSION_CONFLICT);
    }

    private static PromotionCreateRequest create(String name, String type, List<Integer> lines, Instant start, Instant end,
                                                 String config) {
        return new PromotionCreateRequest(name, type, lines, start, end, 0, config == null ? null : json(config));
    }

    private static Promotion stored(PromotionStatus status, int version) {
        Promotion row = PromotionFixtures.online(ID, "REBATE", "[1]", BingoTime.toLocal(START), BingoTime.toLocal(END), 0, REBATE_CONFIG);
        row.setStatus(status);
        row.setVersion(version);
        return row;
    }

    private void assertInvalid(PromotionCreateRequest request, String messagePart) {
        assertThatThrownBy(() -> service.create(request, "op-1"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PromotionErrorCode.INVALID_PROMOTION);
                    assertThat(e.getMessage()).contains(messagePart);
                });
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}

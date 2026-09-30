package com.bingo789.promotion;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionStatus;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;

/** Test fixtures shared by the unit tests and the IT. */
public final class PromotionFixtures {

    public static final String FIRST_DEPOSIT_CONFIG = """
            {"percent":100,"maxAmount":1000,"turnover":{"multiplier":10}}""";
    public static final String REBATE_CONFIG = """
            {"defaultRate":0.005,"turnover":{"multiplier":1}}""";

    private PromotionFixtures() {
    }

    public static PromotionProperties properties(boolean firstDepositEnabled) {
        return new PromotionProperties("+08:00", Duration.ofSeconds(30), Duration.ofSeconds(30),
                new PromotionProperties.FirstDeposit(firstDepositEnabled));
    }

    /** An ONLINE row as the mapper returns it. */
    public static Promotion online(long id, String type, String userLines, LocalDateTime start, LocalDateTime end,
                                   int sort, String config) {
        Promotion row = new Promotion();
        row.setId(id);
        row.setName("promotion " + id);
        row.setPromoType(type);
        row.setUserLines(userLines);
        row.setStartTime(start);
        row.setEndTime(end);
        row.setStatus(PromotionStatus.ONLINE);
        row.setSort(sort);
        row.setConfigJson(config);
        row.setVersion(1);
        row.setCreatedBy("test");
        row.setUpdatedBy("test");
        row.setCreatedAt(start);
        row.setUpdatedAt(start);
        return row;
    }

    public static PlayerStatusView player(long userId, int line) {
        return new PlayerStatusView(userId, line, "PHP", AccountStatus.ACTIVE, KycStatus.VERIFIED,
                true, true, true, null, null);
    }

    public static JsonNode json(String json) {
        return JsonUtils.mapper().readTree(json);
    }
}

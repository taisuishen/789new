package com.bingo789.promotion.service;

import com.bingo789.common.core.RetryableException;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.promotion.PromotionFixtures;
import com.bingo789.promotion.mapper.PromotionMapper;
import com.bingo789.promotion.web.dto.ActivityView;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.bingo789.promotion.PromotionFixtures.online;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Status filtering (only ONLINE rows are loaded) is SQL and covered by PromotionServiceIT. */
class ActivityServiceTest {

    private static final long PLAYER = 42L;
    private static final String FD = "FIRST_DEPOSIT";
    private static final String DISPLAYED_CONFIG = """
            {"percent":100,"maxAmount":1000,"turnover":{"multiplier":10,"scope":"GAME_TYPE","scopeValue":"SLOT"},
             "display":{"title":"Welcome bonus","banner":"https://cdn.example/welcome.png"}}""";

    private PromotionMapper mapper;
    private UserClient userClient;
    private ActivityService service;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        mapper = mock(PromotionMapper.class);
        userClient = mock(UserClient.class);
        service = new ActivityService(new PromotionCatalog(mapper, PromotionFixtures.properties(true)),
                new PlayerLineCache(userClient, PromotionFixtures.properties(true)));
        now = BingoTime.now();
    }

    @Test
    void playerSeesLiveActivitiesOfTheirLineHighestSortThenNewestFirst() {
        when(userClient.playerStatus(PLAYER)).thenReturn(PromotionFixtures.player(PLAYER, 2));
        when(mapper.selectOnlineNotEnded(any())).thenReturn(List.of(
                online(1, FD, "[1]", now.minusDays(1), now.plusDays(1), 9, DISPLAYED_CONFIG),
                online(2, FD, "[1,2]", now.minusDays(1), now.plusDays(1), 0, DISPLAYED_CONFIG),
                online(3, FD, "[2]", now.minusDays(1), now.plusDays(1), 5, DISPLAYED_CONFIG),
                online(4, FD, "[2]", now.minusDays(1), now.plusDays(1), 0, DISPLAYED_CONFIG),
                // not started yet
                online(5, FD, "[2]", now.plusHours(1), now.plusDays(1), 9, DISPLAYED_CONFIG),
                // ended after the snapshot was loaded
                online(6, FD, "[2]", now.minusDays(1), now.minusSeconds(1), 9, DISPLAYED_CONFIG)));

        assertThat(service.listFor(PLAYER)).extracting(ActivityView::id).containsExactly("3", "4", "2");
    }

    @Test
    void restrictedPlayersSeeNoPromotions() {
        when(userClient.playerStatus(PLAYER)).thenReturn(new PlayerStatusView(PLAYER, 1, "PHP", AccountStatus.ACTIVE,
                KycStatus.VERIFIED, false, false, true, null, "SELF_EXCLUDED"));
        when(mapper.selectOnlineNotEnded(any())).thenReturn(List.of(
                online(1, FD, "[1]", now.minusDays(1), now.plusDays(1), 0, DISPLAYED_CONFIG)));

        assertThat(service.listFor(PLAYER)).isEmpty();
    }

    @Test
    void onlyTheDisplayObjectOfTheConfigLeavesTheServer() {
        when(userClient.playerStatus(PLAYER)).thenReturn(PromotionFixtures.player(PLAYER, 1));
        when(mapper.selectOnlineNotEnded(any())).thenReturn(List.of(
                online(1, FD, "[1]", now.minusDays(1), now.plusDays(1), 1, DISPLAYED_CONFIG),
                online(2, "REBATE", "[1]", now.minusDays(1), now.plusDays(1), 0, PromotionFixtures.REBATE_CONFIG)));

        List<ActivityView> views = service.listFor(PLAYER);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).type()).isEqualTo(FD);
        assertThat(views.get(0).display().get("title").stringValue()).isEqualTo("Welcome bonus");
        assertThat(views.get(0).startTime()).isEqualTo(BingoTime.toInstant(now.minusDays(1)));
        assertThat(views.get(1).display()).isNull();
        String json = JsonUtils.toJson(views);
        assertThat(json).contains("Welcome bonus", "https://cdn.example/welcome.png")
                .doesNotContain("maxAmount", "percent", "turnover", "SLOT", "defaultRate");
    }

    @Test
    void aMalformedRowIsLeftOutInsteadOfBreakingTheList() {
        when(userClient.playerStatus(PLAYER)).thenReturn(PromotionFixtures.player(PLAYER, 1));
        when(mapper.selectOnlineNotEnded(any())).thenReturn(List.of(
                online(1, FD, "[0]", now.minusDays(1), now.plusDays(1), 0, DISPLAYED_CONFIG),
                online(2, FD, "[1]", now.minusDays(1), now.plusDays(1), 0, DISPLAYED_CONFIG)));

        assertThat(service.listFor(PLAYER)).extracting(ActivityView::id).containsExactly("2");
    }

    @Test
    void thePlayersLineIsCachedAndTheCatalogIsNotReloadedPerRequest() {
        when(userClient.playerStatus(PLAYER)).thenReturn(PromotionFixtures.player(PLAYER, 1));

        service.listFor(PLAYER);
        service.listFor(PLAYER);

        verify(userClient, times(1)).playerStatus(PLAYER);
        verify(mapper, times(1)).selectOnlineNotEnded(any());
    }

    @Test
    void aLineFromAnOlderUserServiceIsTheDefaultLine() {
        when(userClient.playerStatus(PLAYER)).thenReturn(PromotionFixtures.player(PLAYER, 0));
        when(mapper.selectOnlineNotEnded(any())).thenReturn(List.of(
                online(1, FD, "[1]", now.minusDays(1), now.plusDays(1), 0, DISPLAYED_CONFIG)));

        assertThat(service.listFor(PLAYER)).extracting(ActivityView::id).containsExactly("1");
    }

    @Test
    void userServiceOutageIsRetryable() {
        when(userClient.playerStatus(PLAYER)).thenThrow(new IllegalStateException("connect timed out"));

        assertThatThrownBy(() -> service.listFor(PLAYER)).isInstanceOf(RetryableException.class);
    }
}

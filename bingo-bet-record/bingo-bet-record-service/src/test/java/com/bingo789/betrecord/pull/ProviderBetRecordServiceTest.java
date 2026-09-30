package com.bingo789.betrecord.pull;

import com.bingo789.betrecord.catalog.GameInfo;
import com.bingo789.betrecord.entity.ProviderBetRecord;
import com.bingo789.betrecord.mapper.ProviderBetRecordMapper;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.mq.event.ProviderBetEvent;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderBetRecordServiceTest {

    private static final String PROVIDER = "DEMO";
    private static final Instant BET_TIME = Instant.parse("2026-09-30T05:00:00Z");

    private final ProviderBetRecordMapper mapper = mock(ProviderBetRecordMapper.class);
    private final ProviderBetRecordService service = new ProviderBetRecordService(mapper, new SnowflakeIdGenerator(1));

    @Test
    void newRecordsTakeThePlayersCurrentLineAndResentOnesKeepTheStoredLine() {
        // b-unacked was stored on line 1 by a run that crashed before Kafka acknowledged it; b-acked is done
        when(mapper.findStored(eq(PROVIDER), any(), any(), any()))
                .thenReturn(List.of(stored("b-unacked", 1, false), stored("b-acked", 1, true)));
        PageLookups lookups = new PageLookups(Map.of(10L, 2, 11L, 3, 12L, 4),
                Map.of("fortune-tiger", new GameInfo("SLOT", "Fortune Tiger")));

        List<ProviderBetEvent> toPublish = service.upsertPage(PROVIDER, List.of(
                view("b-new", 10L, "fortune-tiger"), view("b-unacked", 11L, "fortune-tiger"), view("b-acked", 12L, null)),
                lookups);

        assertThat(toPublish.stream().map(ProviderBetEvent::providerBetId).toList()).containsExactly("b-new", "b-unacked");
        assertThat(toPublish.get(0).userLine()).isEqualTo(2);
        assertThat(toPublish.get(1).userLine()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProviderBetRecord>> upserted = ArgumentCaptor.forClass(List.class);
        verify(mapper).upsertBatch(upserted.capture());
        List<ProviderBetRecord> rows = upserted.getValue();
        assertThat(rows).hasSize(3);
        // written for new records only: on a duplicate key the stored line and game attributes are kept
        assertThat(rows.get(0).getUserLine()).isEqualTo(2);
        assertThat(rows.get(0).getGameType()).isEqualTo("SLOT");
        assertThat(rows.get(0).getGameName()).isEqualTo("Fortune Tiger");
        assertThat(rows.get(2).getGameCode()).isEmpty();
        assertThat(rows.get(2).getGameType()).isEqualTo("OTHER");
        assertThat(rows.get(2).getGameName()).isEmpty();
    }

    @Test
    void recordOfAPlayerMissingFromTheLookupGoesToTheDefaultLine() {
        PageLookups lookups = new PageLookups(Map.of(), Map.of());

        List<ProviderBetEvent> toPublish = service.upsertPage(PROVIDER, List.of(view("b-1", 99L, "unknown-game")), lookups);

        assertThat(toPublish).hasSize(1);
        assertThat(toPublish.getFirst().userLine()).isEqualTo(1);
    }

    private static ProviderBetRecordView view(String betId, long userId, String gameCode) {
        return new ProviderBetRecordView(PROVIDER, betId, "r-" + betId, userId, "PHP", gameCode, new BigDecimal("10"),
                new BigDecimal("5"), "SETTLED", BET_TIME, BET_TIME.plusSeconds(5));
    }

    private static ProviderBetRecord stored(String betId, int line, boolean published) {
        ProviderBetRecord row = new ProviderBetRecord();
        row.setProviderBetId(betId);
        row.setUserLine(line);
        row.setPublished(published);
        return row;
    }
}

package com.bingo789.game.api.dto;

import java.util.List;

public record BetPullPage(List<ProviderBetRecordView> records, String nextCursor, boolean hasMore) {
}

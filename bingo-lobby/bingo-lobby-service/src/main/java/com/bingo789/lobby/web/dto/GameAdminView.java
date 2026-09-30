package com.bingo789.lobby.web.dto;

import com.bingo789.lobby.entity.Game;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GameAdminView(
        String id,
        String providerCode,
        String gameCode,
        String name,
        String category,
        BigDecimal theoreticalRtp,
        String status,
        Boolean mobileSupported,
        Boolean desktopSupported,
        Integer sort,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static GameAdminView of(Game g) {
        return new GameAdminView(String.valueOf(g.getId()), g.getProviderCode(), g.getGameCode(), g.getName(),
                g.getCategory(), g.getTheoreticalRtp(), g.getStatus() == null ? null : g.getStatus().name(),
                g.getMobileSupported(), g.getDesktopSupported(), g.getSort(), g.getCreatedAt(), g.getUpdatedAt());
    }
}

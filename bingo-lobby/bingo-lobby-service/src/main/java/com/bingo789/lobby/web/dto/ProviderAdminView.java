package com.bingo789.lobby.web.dto;

import com.bingo789.lobby.entity.GameProvider;

import java.time.LocalDateTime;

public record ProviderAdminView(
        String code,
        String name,
        String walletMode,
        String status,
        String statusReason,
        Integer sort,
        LocalDateTime updatedAt) {

    public static ProviderAdminView of(GameProvider p) {
        return new ProviderAdminView(p.getCode(), p.getName(),
                p.getWalletMode() == null ? null : p.getWalletMode().name(),
                p.getStatus() == null ? null : p.getStatus().name(),
                p.getStatusReason(), p.getSort(), p.getUpdatedAt());
    }
}

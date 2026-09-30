package com.bingo789.user.api.dto;

/**
 * @param lineVersion         bumped by every migration; downstream copies (wallet) apply only newer versions
 * @param shadowUserId        shadow account left in {@code fromLine}
 * @param retiredShadowUserId shadow removed from {@code toLine} because the player came back to it, else null
 */
public record UserLineMigrationView(
        long userId,
        int fromLine,
        int toLine,
        long lineVersion,
        long shadowUserId,
        Long retiredShadowUserId) {
}

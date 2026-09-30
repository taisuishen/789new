package com.bingo789.common.web;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;

/**
 * Identity of the player behind the current request, as asserted by the gateway.
 * Downstream services trust these headers only because they are not reachable from outside the cluster.
 */
public record CurrentUser(Long userId, String sessionId, String clientIp, String deviceId, String countryCode) {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** User id of the logged-in player, or 401 when the request is anonymous. */
    public static long requireUserId() {
        CurrentUser user = HOLDER.get();
        if (user == null || user.userId() == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return user.userId();
    }

    static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    static void clear() {
        HOLDER.remove();
    }
}

package com.bingo789.common.core.session;

/**
 * Redis key layout for player sessions. Written by bingo-user, read by bingo-gateway.
 * Sessions are opaque server-side tokens (not JWT) so that self-exclusion, account suspension
 * and logout take effect immediately.
 */
public final class SessionKeys {

    /** bingo:session:{token} -> userId */
    public static final String SESSION_PREFIX = "bingo:session:";
    /** bingo:user-sessions:{userId} -> set of tokens, used to revoke all sessions of a user */
    public static final String USER_SESSIONS_PREFIX = "bingo:user-sessions:";
    /** bingo:game-token:{token} -> json(GameTokenView), issued at game launch for provider authentication */
    public static final String GAME_TOKEN_PREFIX = "bingo:game-token:";

    private SessionKeys() {
    }

    public static String session(String token) {
        return SESSION_PREFIX + token;
    }

    public static String userSessions(long userId) {
        return USER_SESSIONS_PREFIX + userId;
    }

    public static String gameToken(String token) {
        return GAME_TOKEN_PREFIX + token;
    }

    /**
     * Non-secret, stable id of a session: hex of the first 16 bytes of SHA-256(token). Used as X-Session-Id, so the
     * bearer token itself is never copied anywhere else.
     */
    public static String sessionId(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

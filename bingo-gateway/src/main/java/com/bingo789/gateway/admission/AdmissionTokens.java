package com.bingo789.gateway.admission;

import com.bingo789.common.core.crypto.Hmacs;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * HMAC-signed waiting-room tokens: {@code <kind>.<number>.<expiresAtEpochSecond>.<binding>.<signature>}.
 * <ul>
 *   <li>kind {@code T}: queue ticket, redeemable at GET /api/queue/status once {@code number <= admitted};</li>
 *   <li>kind {@code P}: admission pass, accepted on protected paths even at capacity.</li>
 * </ul>
 * Binding {@code u}: the pass is bound to the authenticated userId; {@code i}: to the client IP (used before login,
 * when no userId exists). The bound value is not in the token: the verifier takes it from the current request
 * (X-User-Id / X-Client-Ip, both written by AuthGlobalFilter) and it is part of the signed payload, so a token only
 * works for the player, or the IP, it was issued to.
 * <p>
 * Tickets are signed too: plain ticket numbers are guessable, and anyone could otherwise redeem an already
 * admitted low number.
 */
public final class AdmissionTokens {

    public static final int MIN_SECRET_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 160;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    public enum Kind {
        TICKET('T'), PASS('P');

        final char code;

        Kind(char code) {
            this.code = code;
        }
    }

    public enum Binding {
        USER('u'), CLIENT_IP('i');

        final char code;

        Binding(char code) {
            this.code = code;
        }

        static Binding of(String code) {
            if (code.length() != 1) {
                return null;
            }
            for (Binding binding : values()) {
                if (binding.code == code.charAt(0)) {
                    return binding;
                }
            }
            return null;
        }
    }

    /** Who a token is issued to. */
    public record Subject(Binding binding, String value) {

        /** The userId when authenticated, otherwise the client IP. */
        public static Subject of(String userId, String clientIp) {
            return userId != null ? new Subject(Binding.USER, userId) : new Subject(Binding.CLIENT_IP, clientIp);
        }

        /** The request's value for an existing token's binding; null when the request lacks it. */
        public static Subject forBinding(Binding binding, String userId, String clientIp) {
            String value = binding == Binding.USER ? userId : clientIp;
            return value == null ? null : new Subject(binding, value);
        }
    }

    public record Claims(Kind kind, long number, Instant expiresAt, Binding binding) {
    }

    private final byte[] key;

    /** @throws IllegalArgumentException when the secret is blank or shorter than {@value #MIN_SECRET_LENGTH} chars */
    public AdmissionTokens(String secret) {
        if (secret == null || secret.isBlank() || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("admission pass secret must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(Kind kind, long number, Instant expiresAt, Subject subject) {
        String unsigned = kind.code + "." + number + "." + expiresAt.getEpochSecond() + "." + subject.binding().code;
        return unsigned + "." + sign(unsigned, subject.value());
    }

    /**
     * @param userId   verified userId of the current request, null when anonymous
     * @param clientIp client IP of the current request
     * @return the claims, or null when the token is absent, malformed, tampered with, expired, of another kind, or
     *         bound to another player / IP
     */
    public Claims verify(String token, Kind expected, Instant now, String userId, String clientIp) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5 || parts[0].length() != 1 || parts[0].charAt(0) != expected.code) {
            return null;
        }
        Binding binding = Binding.of(parts[3]);
        Subject subject = binding == null ? null : Subject.forBinding(binding, userId, clientIp);
        if (subject == null) {
            return null;
        }
        String unsigned = token.substring(0, token.lastIndexOf('.'));
        if (!Hmacs.safeEquals(sign(unsigned, subject.value()), parts[4])) {
            return null;
        }
        // Signed by us, so the numbers are well-formed; still parsed defensively.
        long number;
        Instant expiresAt;
        try {
            number = Long.parseLong(parts[1]);
            expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
        } catch (RuntimeException e) {
            return null;
        }
        if (number < 0 || !now.isBefore(expiresAt)) {
            return null;
        }
        return new Claims(expected, number, expiresAt, binding);
    }

    private String sign(String unsigned, String subjectValue) {
        byte[] data = (unsigned + "." + subjectValue).getBytes(StandardCharsets.UTF_8);
        return BASE64URL.encodeToString(Hmacs.hmacSha256(key, data));
    }
}

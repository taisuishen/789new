package com.bingo789.user.service;

import com.bingo789.common.core.BizException;
import com.bingo789.user.UserErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PasswordService {

    private static final int MIN_LENGTH = 8;
    /** BCrypt only uses the first 72 bytes; longer input is rejected rather than silently truncated. */
    private static final int MAX_BYTES = 72;

    private final PasswordEncoder encoder;
    private final String dummyHash;

    public PasswordService(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode("timing-equalizer-not-a-password");
    }

    public String hash(String rawPassword) {
        BizException.check(meetsPolicy(rawPassword), UserErrorCode.WEAK_PASSWORD,
                "password must be 8-72 bytes and contain at least one letter and one digit");
        return encoder.encode(rawPassword);
    }

    /**
     * Does the same BCrypt work whether or not the account exists, so response times do not reveal
     * which usernames are registered.
     */
    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        if (passwordHash == null) {
            encoder.matches(rawPassword, dummyHash);
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }

    private static boolean meetsPolicy(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        return raw.chars().anyMatch(Character::isLetter) && raw.chars().anyMatch(Character::isDigit);
    }
}

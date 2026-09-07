package com.bugtracking.model;

import java.security.SecureRandom;
import java.util.Base64;

public final class PublicTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicTokens() {
    }

    // 24 random bytes is exactly 32 chars of unpadded base64url
    public static String fresh() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

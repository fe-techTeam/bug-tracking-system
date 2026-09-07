package com.bugtracking.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicTokensTest {

    @Test
    void isThirtyTwoUrlSafeCharacters() {
        String token = PublicTokens.fresh();
        assertEquals(32, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]{32}"));
    }

    @Test
    void differsEveryTime() {
        assertNotEquals(PublicTokens.fresh(), PublicTokens.fresh());
    }
}

package com.achul.compliance.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-1: JWT 발급·검증 단위 테스트 (ADR-008).
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long!!";
    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 15, 14);

    @Test
    void accessToken_roundTrips_withClaims() {
        String token = provider.createAccessToken(42L, "user@example.com", "USER");
        Claims claims = provider.parse(token);

        assertEquals("42", claims.getSubject());
        assertEquals("user@example.com", claims.get("email", String.class));
        assertEquals("USER", claims.get("role", String.class));
        assertEquals("access", claims.get("type", String.class));
    }

    @Test
    void refreshToken_hasTypeAndUniqueId() {
        String t1 = provider.createRefreshToken(1L);
        String t2 = provider.createRefreshToken(1L);

        Claims c1 = provider.parse(t1);
        assertEquals("refresh", c1.get("type", String.class));
        assertEquals("1", c1.getSubject());
        assertNotNull(c1.getId(), "jti 존재");
        // 회전 시 매번 달라야 함
        assertNotEquals(t1, t2);
    }

    @Test
    void parse_rejectsTamperedToken() {
        String token = provider.createAccessToken(1L, "a@b.com", "USER");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(JwtException.class, () -> provider.parse(tampered));
    }

    @Test
    void parse_rejectsForeignSecret() {
        String token = provider.createAccessToken(1L, "a@b.com", "USER");
        JwtTokenProvider other = new JwtTokenProvider("another-completely-different-secret-32b!", 15, 14);
        assertThrows(JwtException.class, () -> other.parse(token));
    }

    @Test
    void constructor_rejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("too-short", 15, 14));
    }

    @Test
    void sha256_isDeterministicAnd64Hex() {
        String h1 = JwtTokenProvider.sha256("hello");
        String h2 = JwtTokenProvider.sha256("hello");
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
        assertTrue(h1.matches("[0-9a-f]{64}"));
        assertNotEquals(h1, JwtTokenProvider.sha256("world"));
    }
}

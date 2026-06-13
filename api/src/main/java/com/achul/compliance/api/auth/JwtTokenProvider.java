package com.achul.compliance.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * P3-1: JWT 발급·검증 (ADR-008).
 * Access(15분)·Refresh(14일) 이중 토큰. HS256 서명.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final Duration accessValidity;
    private final Duration refreshValidity;

    public JwtTokenProvider(
        @Value("${auth.jwt.secret}") String secret,
        @Value("${auth.jwt.access-token-validity-minutes:15}") long accessMinutes,
        @Value("${auth.jwt.refresh-token-validity-days:14}") long refreshDays
    ) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                "auth.jwt.secret 은 최소 32바이트(256bit) 이상이어야 합니다. 현재: " + secretBytes.length + "바이트");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessValidity = Duration.ofMinutes(accessMinutes);
        this.refreshValidity = Duration.ofDays(refreshDays);
    }

    public String createAccessToken(Long userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("email", email)
            .claim("role", role)
            .claim("type", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(accessValidity)))
            .signWith(key)
            .compact();
    }

    /** Refresh token. jti(고유 id)로 회전 시 토큰 문자열이 매번 달라지게 한다. */
    public String createRefreshToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .id(UUID.randomUUID().toString())
            .claim("type", "refresh")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(refreshValidity)))
            .signWith(key)
            .compact();
    }

    /**
     * 서명·만료 검증 후 claims 반환. 유효하지 않으면 {@link JwtException}.
     */
    public Claims parse(String token) {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public Instant refreshExpiry() {
        return Instant.now().plus(refreshValidity);
    }

    public long accessMaxAgeSeconds() {
        return accessValidity.toSeconds();
    }

    public long refreshMaxAgeSeconds() {
        return refreshValidity.toSeconds();
    }

    /** Refresh token 원문 → DB 저장용 SHA-256 해시(hex). 원문은 쿠키에만 보관. */
    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

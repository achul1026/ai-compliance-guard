package com.achul.compliance.api.auth;

import com.achul.compliance.infra.persistence.entity.RefreshTokenEntity;
import com.achul.compliance.infra.persistence.entity.UserEntity;
import com.achul.compliance.infra.persistence.repository.RefreshTokenRepository;
import com.achul.compliance.infra.persistence.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * P3-1: 인증 도메인 서비스 (ADR-008).
 * 회원가입·로그인·토큰 회전·로그아웃. BCrypt + JWT 이중 토큰.
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        PasswordEncoder passwordEncoder,
        JwtTokenProvider tokenProvider
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public UserEntity signup(String email, String rawPassword) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmail(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
        UserEntity user = new UserEntity(normalized, passwordEncoder.encode(rawPassword), "USER");
        return userRepository.save(user);
    }

    @Transactional
    public TokenPair login(String email, String rawPassword) {
        UserEntity user = userRepository.findByEmail(normalizeEmail(email))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return issueTokens(user);
    }

    /** Refresh 회전: 기존 refresh 무효화 + 새 Access/Refresh 발급. */
    @Transactional
    public TokenPair refresh(String refreshTokenRaw) {
        Claims claims;
        try {
            claims = tokenProvider.parse(refreshTokenRaw);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh 토큰입니다.");
        }
        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh 토큰이 아닙니다.");
        }

        String hash = JwtTokenProvider.sha256(refreshTokenRaw);
        RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료되었거나 무효화된 세션입니다."));

        // 회전: 사용된 refresh는 즉시 폐기
        refreshTokenRepository.delete(stored);

        Long userId = Long.valueOf(claims.getSubject());
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));

        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshTokenRaw) {
        if (refreshTokenRaw == null || refreshTokenRaw.isBlank()) {
            return;
        }
        refreshTokenRepository.deleteByTokenHash(JwtTokenProvider.sha256(refreshTokenRaw));
    }

    @Transactional(readOnly = true)
    public UserEntity getById(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    private TokenPair issueTokens(UserEntity user) {
        String access = tokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refresh = tokenProvider.createRefreshToken(user.getId());

        OffsetDateTime expiresAt = tokenProvider.refreshExpiry().atOffset(ZoneOffset.UTC);
        refreshTokenRepository.save(new RefreshTokenEntity(
            user.getId(), JwtTokenProvider.sha256(refresh), expiresAt));

        return new TokenPair(access, refresh, user);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** 발급된 토큰 쌍 + 사용자. */
    public record TokenPair(String accessToken, String refreshToken, UserEntity user) {
    }
}

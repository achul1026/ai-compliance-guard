package com.achul.compliance.api.auth;

import com.achul.compliance.infra.persistence.entity.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * P3-1: 인증 API (ADR-008).
 * context-path /api/v1 + 매핑 /auth → /api/v1/auth/*.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    static final String ACCESS_COOKIE = JwtAuthenticationFilter.ACCESS_COOKIE; // "access_token"
    static final String REFRESH_COOKIE = "refresh_token";
    private static final String ACCESS_PATH = "/api/v1";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final JwtTokenProvider tokenProvider;
    private final boolean cookieSecure;

    public AuthController(
        AuthService authService,
        JwtTokenProvider tokenProvider,
        @Value("${auth.jwt.cookie-secure:false}") boolean cookieSecure
    ) {
        this.authService = authService;
        this.tokenProvider = tokenProvider;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest req) {
        UserEntity user = authService.signup(req.email(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest req) {
        AuthService.TokenPair pair = authService.login(req.email(), req.password());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, accessCookie(pair.accessToken()).toString())
            .header(HttpHeaders.SET_COOKIE, refreshCookie(pair.refreshToken()).toString())
            .body(UserResponse.from(pair.user()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<UserResponse> refresh(
        @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken
    ) {
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        AuthService.TokenPair pair = authService.refresh(refreshToken);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, accessCookie(pair.accessToken()).toString())
            .header(HttpHeaders.SET_COOKIE, refreshCookie(pair.refreshToken()).toString())
            .body(UserResponse.from(pair.user()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken
    ) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, expire(ACCESS_COOKIE, ACCESS_PATH).toString())
            .header(HttpHeaders.SET_COOKIE, expire(REFRESH_COOKIE, REFRESH_PATH).toString())
            .build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        JwtAuthenticationFilter.AuthPrincipal principal =
            (JwtAuthenticationFilter.AuthPrincipal) auth.getPrincipal();
        UserEntity user = authService.getById(principal.userId());
        return ResponseEntity.ok(UserResponse.from(user));
    }

    // ── 쿠키 빌더 ──

    private ResponseCookie accessCookie(String token) {
        return baseCookie(ACCESS_COOKIE, token, ACCESS_PATH, tokenProvider.accessMaxAgeSeconds());
    }

    private ResponseCookie refreshCookie(String token) {
        return baseCookie(REFRESH_COOKIE, token, REFRESH_PATH, tokenProvider.refreshMaxAgeSeconds());
    }

    private ResponseCookie baseCookie(String name, String value, String path, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(path)
            .maxAge(Duration.ofSeconds(maxAgeSeconds))
            .build();
    }

    private ResponseCookie expire(String name, String path) {
        return ResponseCookie.from(name, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(path)
            .maxAge(0)
            .build();
    }

    // ── DTO ──

    public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다") String password
    ) {}

    public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
    ) {}

    public record UserResponse(Long id, String email, String role) {
        static UserResponse from(UserEntity u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getRole());
        }
    }
}

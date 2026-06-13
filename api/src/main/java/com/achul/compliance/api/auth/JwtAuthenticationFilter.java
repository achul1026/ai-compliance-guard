package com.achul.compliance.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * P3-1: Access token 쿠키를 읽어 SecurityContext에 인증을 채우는 필터 (ADR-008).
 *
 * <p>토큰이 없거나 유효하지 않으면 인증을 채우지 않고 통과한다(익명).
 * 접근 통제는 {@link SecurityConfig}의 authorizeHttpRequests가 담당한다.</p>
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACCESS_COOKIE = "access_token";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractCookie(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = tokenProvider.parse(token);
                if (!"access".equals(claims.get("type", String.class))) {
                    throw new JwtException("access 토큰이 아님");
                }
                Long userId = Long.valueOf(claims.getSubject());
                String role = claims.get("role", String.class);
                var authority = new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role));

                AuthPrincipal principal = new AuthPrincipal(userId, claims.get("email", String.class), role);
                var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // 만료·위조 등 → 익명 통과. 보호 경로면 시큐리티가 401 처리.
                log.debug("Access token 검증 실패: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (ACCESS_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** SecurityContext에 담기는 인증 주체. */
    public record AuthPrincipal(Long userId, String email, String role) {
    }
}

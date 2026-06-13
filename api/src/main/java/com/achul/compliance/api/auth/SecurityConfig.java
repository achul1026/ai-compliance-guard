package com.achul.compliance.api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * P3-1: Spring Security 설정 (ADR-008).
 *
 * <p>핵심: 기존 데모 엔드포인트(/health, /search, /audit, 정적 UI)는 공개 유지.
 * 인증은 신규 보호 경로에만. JWT 기반이라 세션은 STATELESS, CSRF는 비활성(쿠키 SameSite + 토큰 검증으로 대체).</p>
 */
@Configuration
public class SecurityConfig {

    private final JwtTokenProvider tokenProvider;

    public SecurityConfig(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // JWT 쿠키 기반 — 표준 CSRF 토큰 대신 SameSite=Lax + access 토큰 서명 검증으로 방어(ADR-008 §4)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // 공개: 데모·헬스·인증 진입점·정적 리소스
                // /error — 컨트롤러 예외(ResponseStatusException) 시 내부 포워딩 경로. 막으면 모든 에러가 401로 덮인다.
                .requestMatchers("/health", "/actuator/**", "/error").permitAll()
                .requestMatchers("/auth/signup", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                // /search는 공개 데모 유지. /audit은 P3-3에서 로그인 필수 + 월 5회 제한으로 전환.
                .requestMatchers("/search").permitAll()
                .requestMatchers("/", "/index.html", "/static/**", "/favicon.ico", "/*.html", "/*.css", "/*.js").permitAll()
                // 관리자 전용
                .requestMatchers("/admin/**").hasRole("ADMIN")
                // 그 외(예: /auth/me, /auth/logout)는 인증 필요
                .anyRequest().authenticated()
            )
            // 보안 헤더 (P3-5): MIME 스니핑 차단, HSTS(HTTPS 운영), 클릭재킹 차단.
            // 앱인토스 WebView는 우리 도메인을 직접 로드(프레임 아님)하므로 SAMEORIGIN으로 충분.
            // 토스 도메인 내 iframe 임베드가 필요해지면 입점 시 프레임 정책 재검토.
            .headers(h -> h
                .contentTypeOptions(c -> {})
                .frameOptions(f -> f.sameOrigin())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
            )
            // 인증 실패 시 리다이렉트 대신 401 (API)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(new JwtAuthenticationFilter(tokenProvider),
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

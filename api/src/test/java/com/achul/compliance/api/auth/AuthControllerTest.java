package com.achul.compliance.api.auth;

import com.achul.compliance.infra.persistence.entity.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P3-1: 인증 API 시큐리티 슬라이스 통합 테스트 (ADR-008).
 *
 * <p>Spring Security 필터 체인 + JwtAuthenticationFilter + 쿠키 + 권한이 실제로 동작한다.
 * DB 관심사({@link AuthService})는 모킹하고, JWT는 실제 {@link JwtTokenProvider}로 검증한다.
 * 실 DB 풀 플로우는 수동 curl E2E로 별도 확인.</p>
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class})
@TestPropertySource(properties = {
    "auth.jwt.secret=test-secret-key-at-least-32-bytes-long!!",
    "auth.jwt.cookie-secure=false"
})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private AuthService authService;

    private static UserEntity user(long id, String email, String role) {
        UserEntity u = new UserEntity(email, "$2a$hash", role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    void signup_returns201() throws Exception {
        given(authService.signup(eq("new@example.com"), any())).willReturn(user(1L, "new@example.com", "USER"));

        mockMvc.perform(post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "new@example.com", "password", "password123"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("new@example.com"))
            .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void signup_rejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "not-an-email", "password", "password123"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void signup_rejectsShortPassword() throws Exception {
        mockMvc.perform(post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "a@b.com", "password", "short"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void login_setsHttpOnlyCookies() throws Exception {
        UserEntity u = user(7L, "user@example.com", "USER");
        String access = tokenProvider.createAccessToken(7L, "user@example.com", "USER");
        String refresh = tokenProvider.createRefreshToken(7L);
        given(authService.login(eq("user@example.com"), any()))
            .willReturn(new AuthService.TokenPair(access, refresh, u));

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "user@example.com", "password", "password123"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("user@example.com"))
            .andExpect(cookie().exists("access_token"))
            .andExpect(cookie().httpOnly("access_token", true))
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(cookie().path("refresh_token", "/api/v1/auth"));
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidAccessCookie_returns200() throws Exception {
        given(authService.getById(7L)).willReturn(user(7L, "user@example.com", "USER"));
        String access = tokenProvider.createAccessToken(7L, "user@example.com", "USER");

        mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", access)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.email").value("user@example.com"));
    }

    @Test
    void me_withRefreshTokenInAccessCookie_returns401() throws Exception {
        // refresh 토큰을 access 자리에 넣으면 필터가 type 검사로 거부해야 함
        String refresh = tokenProvider.createRefreshToken(7L);

        mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", refresh)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_clearsCookies() throws Exception {
        mockMvc.perform(post("/auth/logout").cookie(new Cookie("refresh_token", "whatever")))
            .andExpect(status().isNoContent())
            .andExpect(cookie().maxAge("access_token", 0))
            .andExpect(cookie().maxAge("refresh_token", 0));
    }
}

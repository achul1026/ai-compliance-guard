package com.achul.compliance.api.audit;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.workflow.ComplianceAuditService;
import com.achul.compliance.agent.workflow.ComplianceReport;
import com.achul.compliance.api.auth.JwtTokenProvider;
import com.achul.compliance.api.auth.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P3-3: /audit 로그인 필수 + Free Tier 월 한도 슬라이스 테스트.
 */
@WebMvcTest(AuditController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class})
@TestPropertySource(properties = {
    "auth.jwt.secret=test-secret-key-at-least-32-bytes-long!!",
    "auth.jwt.cookie-secure=false"
})
class AuditControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private ComplianceAuditService auditService;
    @MockBean private UsageService usageService;

    private static ComplianceReport sampleReport() {
        return new ComplianceReport("카피",
            new AuditReport(List.of(), "none", "위반 없음"), null, List.of(), 1, true, "mock");
    }

    private Cookie accessCookie(long userId) {
        return new Cookie("access_token", tokenProvider.createAccessToken(userId, "u@test.com", "USER"));
    }

    @Test
    void audit_withoutLogin_returns401() throws Exception {
        mockMvc.perform(post("/audit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("copy", "테스트 카피"))))
            .andExpect(status().isUnauthorized());

        verify(auditService, never()).audit(any());
    }

    @Test
    void audit_withLogin_withinLimit_returns200_andRecords() throws Exception {
        given(usageService.remainingThisMonth(7L)).willReturn(5);
        given(usageService.freeMonthlyLimit()).willReturn(5);
        given(auditService.audit(eq("당뇨병 예방에 좋아요"))).willReturn(sampleReport());

        mockMvc.perform(post("/audit")
                .cookie(accessCookie(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("copy", "당뇨병 예방에 좋아요"))))
            .andExpect(status().isOk())
            .andExpect(header().string("X-RateLimit-Remaining", "4"))
            .andExpect(jsonPath("$.audit.overall_risk").value("none"));

        verify(usageService).record(eq(7L), eq("당뇨병 예방에 좋아요"), any());
    }

    @Test
    void audit_overLimit_returns429_andDoesNotAnalyze() throws Exception {
        given(usageService.remainingThisMonth(7L)).willReturn(0);
        given(usageService.freeMonthlyLimit()).willReturn(5);

        mockMvc.perform(post("/audit")
                .cookie(accessCookie(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("copy", "카피"))))
            .andExpect(status().isTooManyRequests());

        verify(auditService, never()).audit(any());
        verify(usageService, never()).record(any(), any(), any());
    }

    @Test
    void audit_withLogin_blankCopy_returns400() throws Exception {
        mockMvc.perform(post("/audit")
                .cookie(accessCookie(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("copy", "  "))))
            .andExpect(status().isBadRequest());
    }
}

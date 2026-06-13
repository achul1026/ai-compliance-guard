package com.achul.compliance.agent;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.RemediationResult;
import com.achul.compliance.agent.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-4: 교정관 — 위반 구절마다 대안이 생성되는지 검증한다.
 */
class RemediatorAgentTest {

    @Test
    void remediate_returnsAlternatives_perViolation() {
        StubLlm llm = new StubLlm("""
            {
              "remediations": [
                {
                  "violation_index": 0,
                  "alternatives": ["혈당 관리에 도움을 줄 수 있습니다", "건강한 생활 습관과 함께하세요"],
                  "rationale": "식약처 인정 기능성 표현 범위 내로 수정"
                }
              ]
            }
            """);
        RemediatorAgent agent = new RemediatorAgent(llm);

        AuditReport report = new AuditReport(List.of(
            new AuditReport.Violation("당뇨병 예방에 효과적입니다", "질병 예방·치료 표방",
                "식품 등의 표시·광고에 관한 법률", "제8조 제1항 1호", "high", "근거")
        ), "high", "요약");

        RemediationResult result = agent.remediate("당뇨병 예방에 효과적입니다", report);

        assertEquals(1, result.remediations().size());
        RemediationResult.Remediation r = result.remediations().get(0);
        assertEquals(0, r.violationIndex());
        assertEquals(2, r.alternatives().size());
        assertNotNull(r.rationale());
        assertTrue(llm.lastUserMessage.contains("당뇨병 예방에 효과적입니다"));
    }

    @Test
    void remediate_skipsLlm_forCleanReport() {
        StubLlm llm = new StubLlm("ignored");
        RemediatorAgent agent = new RemediatorAgent(llm);

        RemediationResult result = agent.remediate("물 많이 드세요",
            new AuditReport(List.of(), "none", "위반 없음"));

        assertTrue(result.remediations().isEmpty());
        assertEquals(0, llm.calls);
    }

    static class StubLlm implements LlmPort {
        final String response;
        String lastUserMessage;
        int calls = 0;

        StubLlm(String response) {
            this.response = response;
        }

        @Override
        public String complete(String systemPrompt, String userMessage) {
            calls++;
            lastUserMessage = userMessage;
            return response;
        }

        @Override
        public String modelName() {
            return "stub";
        }
    }
}

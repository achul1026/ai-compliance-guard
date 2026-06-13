package com.achul.compliance.agent;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.CritiqueResult;
import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.agent.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-3: 비판관 — 결정적 환각 차단(LLM 무관)과 의미 검증 위임을 검증한다.
 * ROADMAP 게이트: "의도적으로 잘못된 근거를 심으면 Critique가 탐지".
 */
class CritiqueAgentTest {

    private static final String AD_COPY = "당뇨병 예방에 효과적입니다. 매일 한 알이면 충분합니다.";

    private static final List<RegulationSnippet> REGULATIONS = List.of(
        new RegulationSnippet("식품 등의 표시·광고에 관한 법률", "제8조 제1항 1호",
            "질병의 예방·치료에 효능이 있는 것으로 인식할 우려가 있는 표시 또는 광고를 금지한다.")
    );

    private static AuditReport report(AuditReport.Violation... violations) {
        return new AuditReport(List.of(violations), "high", "요약");
    }

    // === 결정적 검증 (환각 차단 핵심) ===

    @Test
    void critique_detectsFabricatedQuote_withoutLlm() {
        CountingLlm llm = new CountingLlm("{\"passed\": true, \"issues\": [], \"feedback\": null}");
        CritiqueAgent agent = new CritiqueAgent(llm);

        // 광고 카피에 존재하지 않는 구절을 인용 (환각)
        AuditReport fabricated = report(new AuditReport.Violation(
            "암을 완치시킵니다", "질병 예방·치료 표방",
            "식품 등의 표시·광고에 관한 법률", "제8조 제1항 1호", "high", "근거"));

        CritiqueResult result = agent.critique(AD_COPY, REGULATIONS, fabricated);

        assertFalse(result.passed());
        assertEquals("quote_not_found", result.issues().get(0).issueType());
        assertEquals(0, llm.calls, "결정적 실패 시 LLM 호출을 생략해야 함");
        assertNotNull(result.feedback());
    }

    @Test
    void critique_detectsFabricatedCitation_withoutLlm() {
        CountingLlm llm = new CountingLlm("{\"passed\": true, \"issues\": [], \"feedback\": null}");
        CritiqueAgent agent = new CritiqueAgent(llm);

        // 제공된 규정 목록에 없는 법령을 인용 (환각)
        AuditReport fabricated = report(new AuditReport.Violation(
            "당뇨병 예방에 효과적입니다", "질병 예방·치료 표방",
            "존재하지않는법률", "제99조", "high", "근거"));

        CritiqueResult result = agent.critique(AD_COPY, REGULATIONS, fabricated);

        assertFalse(result.passed());
        assertEquals("citation_not_found", result.issues().get(0).issueType());
        assertEquals(0, llm.calls);
    }

    @Test
    void critique_quoteMatching_isLenientToWhitespace() {
        CountingLlm llm = new CountingLlm("{\"passed\": true, \"issues\": [], \"feedback\": null}");
        CritiqueAgent agent = new CritiqueAgent(llm);

        // 공백 차이만 있는 인용은 환각이 아님
        AuditReport okReport = report(new AuditReport.Violation(
            "당뇨병  예방에 효과적입니다", "질병 예방·치료 표방",
            "식품 등의 표시·광고에 관한 법률", "제8조 제1항 1호", "high", "근거"));

        CritiqueResult result = agent.critique(AD_COPY, REGULATIONS, okReport);

        assertTrue(result.passed());
        assertEquals(1, llm.calls, "결정적 검증 통과 시 의미 검증으로 진행");
    }

    // === 의미 검증 (LLM 위임) ===

    @Test
    void critique_delegatesSemanticCheck_andReturnsFailure() {
        CountingLlm llm = new CountingLlm("""
            {
              "passed": false,
              "issues": [{"violation_index": 0, "issue_type": "unsupported_reasoning", "detail": "조항이 주장을 뒷받침하지 않음"}],
              "feedback": "제8조의 실제 내용에 맞춰 위반 유형을 수정하라"
            }
            """);
        CritiqueAgent agent = new CritiqueAgent(llm);

        AuditReport plausible = report(new AuditReport.Violation(
            "당뇨병 예방에 효과적입니다", "소비자 기만",
            "식품 등의 표시·광고에 관한 법률", "제8조 제1항 1호", "low", "근거"));

        CritiqueResult result = agent.critique(AD_COPY, REGULATIONS, plausible);

        assertFalse(result.passed());
        assertEquals("unsupported_reasoning", result.issues().get(0).issueType());
        assertTrue(llm.lastUserMessage.contains("위반 보고서"));
    }

    @Test
    void critique_passesCleanReport_withoutLlm() {
        CountingLlm llm = new CountingLlm("ignored");
        CritiqueAgent agent = new CritiqueAgent(llm);

        AuditReport clean = new AuditReport(List.of(), "none", "위반 없음");

        CritiqueResult result = agent.critique(AD_COPY, REGULATIONS, clean);

        assertTrue(result.passed());
        assertEquals(0, llm.calls);
    }

    static class CountingLlm implements LlmPort {
        final String response;
        String lastUserMessage;
        int calls = 0;

        CountingLlm(String response) {
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

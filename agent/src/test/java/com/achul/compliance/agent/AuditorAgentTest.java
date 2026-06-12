package com.achul.compliance.agent;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.agent.port.LlmPort;
import com.achul.compliance.agent.support.LlmJsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-2: 검사관 에이전트 — stub LLM으로 프롬프트 조립·JSON 파싱·재시도를 검증한다.
 */
class AuditorAgentTest {

    private static final String VALID_JSON = """
        ```json
        {
          "violations": [
            {
              "quote": "당뇨병 예방에 효과적입니다",
              "violation_type": "질병 예방·치료 표방",
              "law_name": "식품 등의 표시·광고에 관한 법률",
              "article": "제8조 제1항 1호",
              "risk_level": "high",
              "reasoning": "질병 예방 효능을 직접 표방."
            }
          ],
          "overall_risk": "high",
          "summary": "질병 예방 표방으로 명백한 위반."
        }
        ```
        """;

    private static final List<RegulationSnippet> REGULATIONS = List.of(
        new RegulationSnippet("식품 등의 표시·광고에 관한 법률", "제8조 제1항 1호",
            "질병의 예방·치료에 효능이 있는 것으로 인식할 우려가 있는 표시 또는 광고를 금지한다.")
    );

    @Test
    void audit_parsesViolationReport_fromFencedJson() {
        StubLlm llm = new StubLlm(VALID_JSON);
        AuditorAgent agent = new AuditorAgent(llm);

        AuditReport report = agent.audit("당뇨병 예방에 효과적입니다", REGULATIONS, null);

        assertFalse(report.clean());
        assertEquals(1, report.violations().size());
        assertEquals("high", report.overallRisk());
        AuditReport.Violation v = report.violations().get(0);
        assertEquals("당뇨병 예방에 효과적입니다", v.quote());
        assertEquals("제8조 제1항 1호", v.article());
        // 프롬프트에 카피와 규정이 포함됐는지
        assertTrue(llm.lastUserMessage.contains("당뇨병 예방에 효과적입니다"));
        assertTrue(llm.lastUserMessage.contains("식품 등의 표시·광고에 관한 법률"));
    }

    @Test
    void audit_includesFeedback_whenProvided() {
        StubLlm llm = new StubLlm(VALID_JSON);
        AuditorAgent agent = new AuditorAgent(llm);

        agent.audit("카피", REGULATIONS, "근거 조항 인용이 부정확함");

        assertTrue(llm.lastUserMessage.contains("비판관 피드백"));
        assertTrue(llm.lastUserMessage.contains("근거 조항 인용이 부정확함"));
    }

    @Test
    void audit_retriesOnce_onMalformedJson() {
        StubLlm llm = new StubLlm("죄송합니다, JSON으로 답하기 어렵습니다.", VALID_JSON);
        AuditorAgent agent = new AuditorAgent(llm);

        AuditReport report = agent.audit("카피", REGULATIONS, null);

        assertEquals(2, llm.calls);
        assertFalse(report.clean());
    }

    @Test
    void audit_throwsAfterRetryFails() {
        StubLlm llm = new StubLlm("not json", "still not json");
        AuditorAgent agent = new AuditorAgent(llm);

        assertThrows(LlmJsonParser.LlmJsonParseException.class,
            () -> agent.audit("카피", REGULATIONS, null));
        assertEquals(2, llm.calls);
    }

    @Test
    void audit_cleanReport_whenNoViolations() {
        StubLlm llm = new StubLlm("""
            {"violations": [], "overall_risk": "none", "summary": "위반 없음"}
            """);
        AuditorAgent agent = new AuditorAgent(llm);

        AuditReport report = agent.audit("물 많이 드세요", REGULATIONS, null);

        assertTrue(report.clean());
        assertEquals("none", report.overallRisk());
    }

    /** 호출 순서대로 준비된 응답을 반환하는 stub. */
    static class StubLlm implements LlmPort {
        final List<String> responses = new ArrayList<>();
        String lastUserMessage;
        int calls = 0;

        StubLlm(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String complete(String systemPrompt, String userMessage) {
            this.lastUserMessage = userMessage;
            return responses.get(Math.min(calls++, responses.size() - 1));
        }

        @Override
        public String modelName() {
            return "stub";
        }
    }
}

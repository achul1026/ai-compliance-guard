package com.achul.compliance.agent.workflow;

import com.achul.compliance.agent.AuditorAgent;
import com.achul.compliance.agent.CritiqueAgent;
import com.achul.compliance.agent.RemediatorAgent;
import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.agent.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-5: 순환 루프 — 피드백 루프 동작과 반복 상한 안전 종료를 검증한다.
 * ROADMAP 게이트: "피드백 루프가 동작하고 최대 반복 횟수에서 안전하게 종료".
 *
 * <p>시나리오는 스크립트된 LLM 응답 시퀀스로 구성한다. 결정적 검증(quote/citation 실재)을
 * 통과하는 보고서를 쓰되, 의미 검증(LLM)의 통과/반려를 시퀀스로 제어한다.</p>
 */
class AuditWorkflowTest {

    private static final String AD_COPY = "당뇨병 예방에 효과적입니다";

    private static final List<RegulationSnippet> REGULATIONS = List.of(
        new RegulationSnippet("식품 등의 표시·광고에 관한 법률", "제8조 제1항 1호",
            "질병의 예방·치료에 효능이 있는 것으로 인식할 우려가 있는 표시 또는 광고를 금지한다.")
    );

    private static final String AUDIT_JSON = """
        {
          "violations": [{
            "quote": "당뇨병 예방에 효과적입니다",
            "violation_type": "질병 예방·치료 표방",
            "law_name": "식품 등의 표시·광고에 관한 법률",
            "article": "제8조 제1항 1호",
            "risk_level": "high",
            "reasoning": "질병 예방 효능 직접 표방"
          }],
          "overall_risk": "high",
          "summary": "명백한 위반"
        }
        """;

    private static final String CRITIQUE_PASS = """
        {"passed": true, "issues": [], "feedback": null}
        """;

    private static final String CRITIQUE_FAIL = """
        {"passed": false,
         "issues": [{"violation_index": 0, "issue_type": "unsupported_reasoning", "detail": "위험도 재평가 필요"}],
         "feedback": "risk_level 판단 근거를 보강하라"}
        """;

    private static final String REMEDIATION_JSON = """
        {"remediations": [{
          "violation_index": 0,
          "alternatives": ["혈당 관리에 도움을 줄 수 있습니다"],
          "rationale": "기능성 표현 범위 내"
        }]}
        """;

    private static AuditWorkflow workflow(ScriptedLlm llm, int maxIterations) {
        return new AuditWorkflow(
            new AuditorAgent(llm), new CritiqueAgent(llm), new RemediatorAgent(llm),
            llm, maxIterations);
    }

    @Test
    void run_singlePass_whenCritiqueApproves() {
        // 호출 순서: auditor → critique(pass) → remediator
        ScriptedLlm llm = new ScriptedLlm(AUDIT_JSON, CRITIQUE_PASS, REMEDIATION_JSON);
        ComplianceReport report = workflow(llm, 2).run(AD_COPY, REGULATIONS);

        assertEquals(1, report.iterations());
        assertTrue(report.critiquePassed());
        assertEquals(1, report.audit().violations().size());
        assertEquals(1, report.remediation().remediations().size());
        assertEquals(3, llm.calls);
    }

    @Test
    void run_feedbackLoop_reAuditsWithCritiqueFeedback() {
        // auditor → critique(fail) → auditor(피드백 포함) → critique(pass) → remediator
        ScriptedLlm llm = new ScriptedLlm(AUDIT_JSON, CRITIQUE_FAIL, AUDIT_JSON, CRITIQUE_PASS, REMEDIATION_JSON);
        ComplianceReport report = workflow(llm, 3).run(AD_COPY, REGULATIONS);

        assertEquals(2, report.iterations());
        assertTrue(report.critiquePassed());
        // 2회차 검사관 호출(3번째 LLM 호출)에 비판관 피드백이 포함됐는지
        assertTrue(llm.userMessages.get(2).contains("risk_level 판단 근거를 보강하라"));
        assertEquals(5, llm.calls);
    }

    @Test
    void run_terminatesSafely_atMaxIterations() {
        // 비판관이 계속 반려 → maxIterations=2에서 안전 종료
        ScriptedLlm llm = new ScriptedLlm(AUDIT_JSON, CRITIQUE_FAIL, AUDIT_JSON, CRITIQUE_FAIL, REMEDIATION_JSON);
        ComplianceReport report = workflow(llm, 2).run(AD_COPY, REGULATIONS);

        assertEquals(2, report.iterations());
        assertFalse(report.critiquePassed(), "상한 도달 시 critiquePassed=false로 정직하게 표기");
        assertNotNull(report.remediation(), "상한 도달이어도 교정안은 제공");
        assertEquals(5, llm.calls, "무한루프 없이 정확히 5회 호출 후 종료");
    }

    /** 호출 순서대로 응답을 소비하는 스크립트 LLM. */
    static class ScriptedLlm implements LlmPort {
        final List<String> script;
        final List<String> userMessages = new ArrayList<>();
        int calls = 0;

        ScriptedLlm(String... responses) {
            this.script = List.of(responses);
        }

        @Override
        public String complete(String systemPrompt, String userMessage) {
            userMessages.add(userMessage);
            if (calls >= script.size()) {
                throw new AssertionError("예상보다 많은 LLM 호출: " + (calls + 1));
            }
            return script.get(calls++);
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }
}

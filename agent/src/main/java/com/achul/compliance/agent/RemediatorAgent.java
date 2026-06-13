package com.achul.compliance.agent;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.RemediationResult;
import com.achul.compliance.agent.port.LlmPort;
import com.achul.compliance.agent.support.LlmJsonParser;
import com.achul.compliance.agent.support.PromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P2-4: 교정관(Remediator) 에이전트 — 위반 구절별 안전한 대체 문구 제안.
 */
@Slf4j
@Component
public class RemediatorAgent {

    private static final String SYSTEM_PROMPT = PromptLoader.load("prompts/remediator-system.txt");

    private final LlmPort llm;

    public RemediatorAgent(LlmPort llm) {
        this.llm = llm;
    }

    /**
     * @param adCopy 광고 카피 원문
     * @param report 비판관 검증을 통과한 최종 위반 보고서
     * @return 위반 항목별 대체 문구 (위반 없으면 빈 결과)
     */
    public RemediationResult remediate(String adCopy, AuditReport report) {
        if (report.clean()) {
            return new RemediationResult(List.of());
        }

        String userMessage = buildUserMessage(adCopy, report);
        String response = llm.complete(SYSTEM_PROMPT, userMessage);
        try {
            return LlmJsonParser.parse(response, RemediationResult.class);
        } catch (LlmJsonParser.LlmJsonParseException first) {
            log.warn("Remediator 응답 파싱 실패, 1회 재시도. cause={}", first.getMessage());
            String retryResponse = llm.complete(SYSTEM_PROMPT,
                userMessage + "\n\n[형식 오류] 직전 응답이 유효한 JSON이 아니었다. 지정된 JSON 형식으로만 다시 응답하라.");
            return LlmJsonParser.parse(retryResponse, RemediationResult.class);
        }
    }

    private String buildUserMessage(String adCopy, AuditReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 광고 카피 원문\n").append(adCopy).append("\n\n");
        sb.append("## 위반 항목\n");
        List<AuditReport.Violation> violations = report.violations();
        for (int i = 0; i < violations.size(); i++) {
            AuditReport.Violation v = violations.get(i);
            sb.append(i).append(". 구절=\"").append(v.quote())
                .append("\", 위반유형=").append(v.violationType())
                .append(", 근거=[").append(v.lawName()).append(' ').append(v.article()).append(']')
                .append('\n');
        }
        return sb.toString();
    }
}

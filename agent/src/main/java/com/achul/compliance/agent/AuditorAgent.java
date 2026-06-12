package com.achul.compliance.agent;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.agent.port.LlmPort;
import com.achul.compliance.agent.support.LlmJsonParser;
import com.achul.compliance.agent.support.PromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P2-2: 검사관(Auditor) 에이전트.
 *
 * <p>검색 컨텍스트(근거 규정)와 광고 카피를 받아 위반 소지 리포트를 생성한다.
 * 비판관(P2-3)의 재검사 피드백을 받으면 그 내용을 반영해 다시 작성한다.</p>
 */
@Slf4j
@Component
public class AuditorAgent {

    private static final String SYSTEM_PROMPT = PromptLoader.load("prompts/auditor-system.txt");

    private final LlmPort llm;

    public AuditorAgent(LlmPort llm) {
        this.llm = llm;
    }

    /**
     * @param adCopy      검사할 광고 카피
     * @param regulations 검색된 근거 규정 (Hybrid RAG 결과)
     * @param feedback    비판관 피드백 (첫 검사면 null)
     */
    public AuditReport audit(String adCopy, List<RegulationSnippet> regulations, String feedback) {
        String userMessage = buildUserMessage(adCopy, regulations, feedback);

        String response = llm.complete(SYSTEM_PROMPT, userMessage);
        try {
            return LlmJsonParser.parse(response, AuditReport.class);
        } catch (LlmJsonParser.LlmJsonParseException first) {
            // 1회 재시도: 형식 오류를 알려주고 재생성 요청
            log.warn("Auditor 응답 파싱 실패, 1회 재시도. cause={}", first.getMessage());
            String retryResponse = llm.complete(SYSTEM_PROMPT,
                userMessage + "\n\n[형식 오류] 직전 응답이 유효한 JSON이 아니었다. 규칙 6의 JSON 형식으로만 다시 응답하라.");
            return LlmJsonParser.parse(retryResponse, AuditReport.class);
        }
    }

    private String buildUserMessage(String adCopy, List<RegulationSnippet> regulations, String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 광고 카피\n").append(adCopy).append("\n\n");
        sb.append("## 관련 규정 조항\n");
        for (int i = 0; i < regulations.size(); i++) {
            RegulationSnippet r = regulations.get(i);
            sb.append(i + 1).append(". [").append(r.lawName());
            if (r.article() != null && !r.article().isBlank()) {
                sb.append(' ').append(r.article());
            }
            sb.append("] ").append(r.text()).append('\n');
        }
        if (feedback != null && !feedback.isBlank()) {
            sb.append("\n## 비판관 피드백 (이전 보고서의 문제점 — 반드시 반영해 다시 작성)\n").append(feedback).append('\n');
        }
        return sb.toString();
    }
}

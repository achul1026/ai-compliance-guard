package com.achul.compliance.agent;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.CritiqueResult;
import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.agent.port.LlmPort;
import com.achul.compliance.agent.support.LlmJsonParser;
import com.achul.compliance.agent.support.PromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P2-3: 비판관(Critique) 에이전트 — 환각 차단.
 *
 * <p>2단계 검증:
 * <ol>
 *   <li><b>결정적 검증 (코드, 비용 0)</b> — 인용 구절이 광고 카피에 실재하는가(quote_not_found),
 *       인용 법령·조항이 검사관에게 제공된 규정 목록에 실재하는가(citation_not_found).
 *       LLM이 지어낸 인용은 여기서 확실하게 걸린다.</li>
 *   <li><b>의미 검증 (LLM)</b> — 조항 내용이 위반 주장을 실제로 뒷받침하는가, 과잉 판정은 없는가.</li>
 * </ol>
 * 결정적 검증 실패가 하나라도 있으면 LLM 호출 없이 즉시 실패를 반환한다 (무료 한도 절약).</p>
 */
@Slf4j
@Component
public class CritiqueAgent {

    private static final String SYSTEM_PROMPT = PromptLoader.load("prompts/critique-system.txt");

    private final LlmPort llm;

    public CritiqueAgent(LlmPort llm) {
        this.llm = llm;
    }

    public CritiqueResult critique(String adCopy, List<RegulationSnippet> regulations, AuditReport report) {
        // 위반 없음 보고서는 의미 검증만 의미가 있는데, 과잉 비용을 피해 통과시킨다.
        // (미탐지 검증은 골든셋 평가 트랙에서 다룬다 — 루프에서 잡으려면 호출이 배로 든다.)
        if (report.clean()) {
            return CritiqueResult.pass();
        }

        List<CritiqueResult.Issue> deterministicIssues = verifyDeterministic(adCopy, regulations, report);
        if (!deterministicIssues.isEmpty()) {
            String feedback = buildDeterministicFeedback(deterministicIssues);
            log.info("Critique 결정적 검증 실패 {}건 — LLM 호출 생략", deterministicIssues.size());
            return new CritiqueResult(false, deterministicIssues, feedback);
        }

        return verifySemantic(adCopy, regulations, report);
    }

    /** 1단계: 인용 실재성 검증 (substring 매칭, LLM 불필요). */
    private List<CritiqueResult.Issue> verifyDeterministic(
        String adCopy, List<RegulationSnippet> regulations, AuditReport report) {

        List<CritiqueResult.Issue> issues = new ArrayList<>();
        String normalizedCopy = normalize(adCopy);

        for (int i = 0; i < report.violations().size(); i++) {
            AuditReport.Violation v = report.violations().get(i);

            // (1) quote가 광고 카피에 실재하는가
            if (v.quote() == null || !normalizedCopy.contains(normalize(v.quote()))) {
                issues.add(new CritiqueResult.Issue(i, "quote_not_found",
                    "인용 구절이 광고 카피에 없음: \"" + v.quote() + "\""));
            }

            // (2) 인용 법령이 제공된 규정 목록에 실재하는가
            boolean lawFound = regulations.stream()
                .anyMatch(r -> r.lawName() != null && v.lawName() != null
                    && normalize(r.lawName()).contains(normalize(v.lawName())));
            if (!lawFound) {
                issues.add(new CritiqueResult.Issue(i, "citation_not_found",
                    "인용 법령이 제공된 규정 목록에 없음: \"" + v.lawName() + "\""));
            }
        }
        return issues;
    }

    /** 2단계: LLM 의미 검증. */
    private CritiqueResult verifySemantic(String adCopy, List<RegulationSnippet> regulations, AuditReport report) {
        String userMessage = buildUserMessage(adCopy, regulations, report);
        String response = llm.complete(SYSTEM_PROMPT, userMessage);
        try {
            return LlmJsonParser.parse(response, CritiqueResult.class);
        } catch (LlmJsonParser.LlmJsonParseException first) {
            log.warn("Critique 응답 파싱 실패, 1회 재시도. cause={}", first.getMessage());
            String retryResponse = llm.complete(SYSTEM_PROMPT,
                userMessage + "\n\n[형식 오류] 직전 응답이 유효한 JSON이 아니었다. 지정된 JSON 형식으로만 다시 응답하라.");
            return LlmJsonParser.parse(retryResponse, CritiqueResult.class);
        }
    }

    private String buildUserMessage(String adCopy, List<RegulationSnippet> regulations, AuditReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 광고 카피\n").append(adCopy).append("\n\n");
        sb.append("## 검사관에게 제공된 규정 조항\n");
        for (int i = 0; i < regulations.size(); i++) {
            RegulationSnippet r = regulations.get(i);
            sb.append(i + 1).append(". [").append(r.lawName());
            if (r.article() != null && !r.article().isBlank()) {
                sb.append(' ').append(r.article());
            }
            sb.append("] ").append(r.text()).append('\n');
        }
        sb.append("\n## 검사관의 위반 보고서\n");
        List<AuditReport.Violation> violations = report.violations();
        for (int i = 0; i < violations.size(); i++) {
            AuditReport.Violation v = violations.get(i);
            sb.append(i).append(". quote=\"").append(v.quote())
                .append("\", violation_type=").append(v.violationType())
                .append(", 근거=[").append(v.lawName()).append(' ').append(v.article())
                .append("], risk=").append(v.riskLevel())
                .append(", reasoning=").append(v.reasoning()).append('\n');
        }
        sb.append("전체 판정: ").append(report.overallRisk())
            .append(" — ").append(report.summary());
        return sb.toString();
    }

    private static String buildDeterministicFeedback(List<CritiqueResult.Issue> issues) {
        StringBuilder sb = new StringBuilder("다음 인용 오류를 수정하라. 제공된 규정 목록과 광고 카피 원문만 사용할 것:\n");
        for (CritiqueResult.Issue issue : issues) {
            sb.append("- ").append(issue.detail()).append('\n');
        }
        return sb.toString();
    }

    /** 공백·중점 변형에 관대한 비교용 정규화. */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s·ㆍ]+", "");
    }
}

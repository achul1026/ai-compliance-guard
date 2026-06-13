package com.achul.compliance.agent.workflow;

import com.achul.compliance.agent.AuditorAgent;
import com.achul.compliance.agent.CritiqueAgent;
import com.achul.compliance.agent.RemediatorAgent;
import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.CritiqueResult;
import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.agent.domain.RemediationResult;
import com.achul.compliance.agent.port.LlmPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P2-5: Stateful 순환 루프 오케스트레이터 (ADR-007 자체 상태머신).
 *
 * <pre>
 * 검사관 ──► 비판관 ──passed──► 교정관 ──► 최종 리포트
 *   ▲           │
 *   └──feedback─┘ (최대 maxIterations회)
 * </pre>
 *
 * <p>반복 상한 도달 시 마지막 보고서를 채택하되 {@code critiquePassed=false}로
 * 표기해 호출부가 신뢰도를 알 수 있게 한다 (무한루프 방지 게이트).</p>
 */
@Slf4j
@Component
public class AuditWorkflow {

    private final AuditorAgent auditor;
    private final CritiqueAgent critic;
    private final RemediatorAgent remediator;
    private final LlmPort llm;
    private final int maxIterations;

    public AuditWorkflow(
        AuditorAgent auditor,
        CritiqueAgent critic,
        RemediatorAgent remediator,
        LlmPort llm,
        @Value("${agent.workflow.max-iterations:2}") int maxIterations
    ) {
        this.auditor = auditor;
        this.critic = critic;
        this.remediator = remediator;
        this.llm = llm;
        this.maxIterations = Math.max(1, maxIterations);
    }

    public ComplianceReport run(String adCopy, List<RegulationSnippet> regulations) {
        AuditReport audit = null;
        CritiqueResult critique = null;
        String feedback = null;
        int iteration = 0;

        while (iteration < maxIterations) {
            iteration++;
            log.info("AuditWorkflow iteration {}/{} 시작", iteration, maxIterations);

            audit = auditor.audit(adCopy, regulations, feedback);
            critique = critic.critique(adCopy, regulations, audit);

            if (critique.passed()) {
                log.info("비판관 통과 (iteration {})", iteration);
                break;
            }
            feedback = critique.feedback();
            log.info("비판관 반려 (iteration {}). issues={}", iteration,
                critique.issues() == null ? 0 : critique.issues().size());
        }

        boolean passed = critique != null && critique.passed();
        if (!passed) {
            log.warn("반복 상한({}) 도달 — 마지막 보고서를 critiquePassed=false로 채택", maxIterations);
        }

        RemediationResult remediation = remediator.remediate(adCopy, audit);

        return new ComplianceReport(adCopy, audit, remediation, regulations,
            iteration, passed, llm.modelName());
    }
}

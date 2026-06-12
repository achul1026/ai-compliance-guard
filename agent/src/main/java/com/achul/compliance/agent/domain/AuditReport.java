package com.achul.compliance.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * P2-2: 검사관(Auditor) 출력 — 위반 소지 리포트.
 *
 * <p>LLM이 JSON으로 직접 생성하는 계약이므로 필드명은 snake_case 매핑을 고정한다.
 * 비판관(P2-3)이 이 리포트의 인용 근거를 교차 검증한다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditReport(
    @JsonProperty("violations") List<Violation> violations,
    @JsonProperty("overall_risk") String overallRisk,   // none | low | medium | high
    @JsonProperty("summary") String summary
) {

    /** 위반이 하나도 없는 보고서인가. */
    public boolean clean() {
        return violations == null || violations.isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Violation(
        @JsonProperty("quote") String quote,                   // 광고 카피에서 위반 소지가 있는 원문 구절 (그대로 인용)
        @JsonProperty("violation_type") String violationType,  // 택소노미 분류 (예: 질병 예방·치료 표방)
        @JsonProperty("law_name") String lawName,              // 근거 법령명
        @JsonProperty("article") String article,               // 근거 조항
        @JsonProperty("risk_level") String riskLevel,          // low | medium | high
        @JsonProperty("reasoning") String reasoning            // 판단 근거 설명
    ) {}
}

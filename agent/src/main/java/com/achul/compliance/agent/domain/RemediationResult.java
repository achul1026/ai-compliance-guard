package com.achul.compliance.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * P2-4: 교정관(Remediator) 출력 — 위반 구절별 안전한 대체 문구.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemediationResult(
    @JsonProperty("remediations") List<Remediation> remediations
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Remediation(
        @JsonProperty("violation_index") int violationIndex,     // AuditReport.violations 인덱스 (0-base)
        @JsonProperty("alternatives") List<String> alternatives, // 대체 문구 후보 1~3개
        @JsonProperty("rationale") String rationale              // 왜 이 표현이 안전한지
    ) {}
}

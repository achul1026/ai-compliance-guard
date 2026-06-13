package com.achul.compliance.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * P2-3: 비판관(Critique) 출력 — 검사 보고서 교차 검증 결과.
 *
 * <p>{@code passed=false}면 오케스트레이터(P2-5)가 {@code feedback}을 검사관에 넘겨
 * 재검사 루프를 돈다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CritiqueResult(
    @JsonProperty("passed") boolean passed,
    @JsonProperty("issues") List<Issue> issues,
    @JsonProperty("feedback") String feedback        // 검사관 재검사용 지시문 (passed=true면 null 허용)
) {

    public static CritiqueResult pass() {
        return new CritiqueResult(true, List.of(), null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issue(
        @JsonProperty("violation_index") Integer violationIndex,  // 문제가 있는 위반 항목 인덱스 (0-base, 전체 문제면 null)
        @JsonProperty("issue_type") String issueType,             // quote_not_found | citation_not_found | unsupported_reasoning | other
        @JsonProperty("detail") String detail
    ) {}
}

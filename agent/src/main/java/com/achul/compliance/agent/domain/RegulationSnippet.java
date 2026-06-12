package com.achul.compliance.agent.domain;

/**
 * P2-2: 에이전트에 전달되는 근거 규정 조각.
 *
 * <p>rag 모듈의 검색 DTO와 의도적으로 분리한 경량 계약 — 오케스트레이터(P2-6)가
 * 검색 결과를 이 형태로 매핑해 넘긴다.</p>
 */
public record RegulationSnippet(
    String lawName,       // 법령명
    String article,       // 조·항·호 (예: "제8조 제1항 1호")
    String text           // 조항 본문
) {}

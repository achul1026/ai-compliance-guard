package com.achul.compliance.api.search;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RAG-005: 검색 결과 항목 DTO.
 *
 * <p>응답 항목 예시:
 * <pre>
 * {
 *   "law": "식품 등의 표시·광고에 관한 법률",
 *   "article": "제8조 제1항 제1호",
 *   "text": "...",
 *   "relevance_score": 0.93
 * }
 * </pre>
 *
 * @param law            법령명
 * @param article        조·항·호를 결합한 식별 문자열
 * @param text           조항 본문(청크 텍스트)
 * @param relevanceScore 관련도 점수(0~1, Re-ranker 점수 우선, 폴백 시 하이브리드 점수)
 */
public record SearchResultDto(
    String law,
    String article,
    String text,
    @JsonProperty("relevance_score")
    Float relevanceScore
) {}

package com.achul.compliance.api.search;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RAG-005: 검색 API 응답 DTO.
 *
 * <p>응답 본문 예시:
 * <pre>
 * {
 *   "query": "이 상품은 당뇨병을 예방합니다",
 *   "results": [
 *     {
 *       "law": "식품 등의 표시·광고에 관한 법률",
 *       "article": "제8조 제1항 제1호",
 *       "text": "...",
 *       "relevance_score": 0.93
 *     }
 *   ],
 *   "execution_time_ms": 247
 * }
 * </pre>
 *
 * @param query           원본 질의
 * @param results         관련도 순 검색 결과(상위 5~10개)
 * @param executionTimeMs 검색 수행 시간(ms)
 */
public record SearchResponse(
    String query,
    List<SearchResultDto> results,
    @JsonProperty("execution_time_ms")
    long executionTimeMs
) {}

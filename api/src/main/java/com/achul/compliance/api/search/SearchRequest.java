package com.achul.compliance.api.search;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * RAG-005: 검색 API 요청 DTO.
 *
 * <p>요청 본문 예시:
 * <pre>
 * {
 *   "query": "이 상품은 당뇨병을 예방합니다",
 *   "top_k": 10
 * }
 * </pre>
 *
 * @param query 검색 질의(필수)
 * @param topK  반환 결과 수(1~20, 기본 10). 요청 JSON 필드명은 {@code top_k}.
 */
public record SearchRequest(
    @NotBlank
    String query,

    @JsonProperty("top_k")
    @JsonAlias({"topK"})
    @Min(1)
    @Max(20)
    Integer topK
) {
    /** {@code top_k} 미지정 시 사용할 기본 값. */
    public static final int DEFAULT_TOP_K = 10;

    /** {@code top_k}가 null인 경우 기본 값을 반환한다. */
    public int effectiveTopK() {
        return topK == null ? DEFAULT_TOP_K : topK;
    }
}

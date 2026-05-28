package com.achul.compliance.rag.dto;

/**
 * RAG-003: BM25 키워드 검색 요청 DTO.
 *
 * @param query           검색어 (예: "식품표시광고법 제8조")
 * @param lawNameFilter   법령명 정확 필터 (선택)
 * @param articleFilter   조항 번호 정확 필터 (선택, 예: "제8조")
 * @param limit           최대 결과 개수 (기본 10)
 */
public record SearchRequest(
    String query,
    String lawNameFilter,
    String articleFilter,
    Integer limit
) {
    public static SearchRequest of(String query) {
        return new SearchRequest(query, null, null, 10);
    }

    public int effectiveLimit() {
        return (limit == null || limit <= 0) ? 10 : limit;
    }
}

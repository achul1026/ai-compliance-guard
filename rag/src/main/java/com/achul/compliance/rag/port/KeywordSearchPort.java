package com.achul.compliance.rag.port;

import java.util.List;

/**
 * ADR-002: BM25 키워드 검색 포트.
 * 1차: ParadeDB (PostgreSQL 확장)
 * 2차 옵션: OpenSearch+nori (별도 클러스터)
 *
 * 구현체를 교체 가능하도록 인터페이스로 격리.
 */
public interface KeywordSearchPort {

    /**
     * 키워드 기반 BM25 검색.
     * 조항 번호, 법령명, 고유 용어 정확 매칭용.
     *
     * @param query 검색 쿼리 (예: "제8조", "식품표시광고법")
     * @param limit 반환 최대 건수
     * @return 검색 결과 리스트
     */
    List<KeywordSearchResult> search(String query, int limit);

    /**
     * 메타데이터 필터 + 키워드 검색.
     * 법령, 조항별로 먼저 필터한 후 BM25 검색.
     *
     * @param query 검색 쿼리
     * @param lawName 법령명 필터 (null이면 필터 안 함)
     * @param articleNumber 조항 번호 필터 (null이면 필터 안 함)
     * @param limit 반환 최대 건수
     * @return 검색 결과 리스트
     */
    List<KeywordSearchResult> searchWithFilters(
        String query,
        String lawName,
        String articleNumber,
        int limit
    );

    /**
     * 키워드 검색 결과.
     */
    record KeywordSearchResult(
        Long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        Float bm25Score,
        String version
    ) {}
}

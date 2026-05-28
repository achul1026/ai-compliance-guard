package com.achul.compliance.rag.port;

import java.util.List;

/**
 * D4: Re-ranking 포트.
 * 후보 검색 결과를 의미 정합도 기준으로 재정렬.
 * Phase 1-7에서 구현 (D4 기술 결정 후).
 *
 * 후보:
 * - Cohere Rerank API
 * - Upstage Solar-reranker
 * - Cross-encoder 로컬 모델
 */
public interface RerankerPort {

    /**
     * 검색 결과 재정렬.
     * 쿼리와 문서 간 관련도를 재계산해 순서를 재조정.
     *
     * @param query 원본 질의
     * @param candidates 재정렬할 후보 리스트
     * @param topK 최종 반환 건수
     * @return 재정렬된 결과
     */
    List<RerankedResult> rerank(String query, List<RerankCandidate> candidates, int topK);

    /**
     * 재정렬 입력 (후보 문서).
     */
    record RerankCandidate(
        Long documentId,
        String content
    ) {}

    /**
     * 재정렬 결과.
     */
    record RerankedResult(
        Long documentId,
        Float relevanceScore,   // 0~1 정규화된 점수
        Integer newRank         // 재정렬 후 순위
    ) {}
}

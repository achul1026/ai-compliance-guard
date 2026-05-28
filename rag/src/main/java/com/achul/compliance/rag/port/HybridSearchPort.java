package com.achul.compliance.rag.port;

import java.util.List;

/**
 * P1-7: Hybrid RAG 검색 결합 포트.
 * BM25 (키워드) + Vector (의미) + Re-ranking 결합.
 */
public interface HybridSearchPort {

    /**
     * 하이브리드 검색 실행.
     * 1. BM25 검색 top-k
     * 2. Vector 검색 top-k
     * 3. RRF(Reciprocal Rank Fusion)로 결합
     * 4. Re-ranker로 최종 정렬
     *
     * @param query 사용자 질의
     * @param options 검색 옵션 (top-k, 필터 등)
     * @return 하이브리드 검색 결과 (점수 포함)
     */
    List<HybridSearchResult> search(String query, HybridSearchOptions options);

    /**
     * 검색 결과 (메타데이터 + 점수 정보 포함).
     */
    record HybridSearchResult(
        Long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        String violationType,        // 택소노미 매핑
        String version,
        Float bm25Score,             // BM25 점수
        Float vectorScore,           // 코사인 유사도
        Float rerankerScore,         // Re-ranker 점수
        Float finalScore,            // 최종 통합 점수
        String searchMethod          // "bm25", "vector", "hybrid" 중 어떤 것으로 매칭됐는지
    ) {}

    /**
     * 하이브리드 검색 옵션.
     */
    record HybridSearchOptions(
        Integer topK,                 // 결과 반환 건수 (기본: 10)
        Integer bm25TopK,             // BM25 top-k (기본: 20)
        Integer vectorTopK,           // Vector top-k (기본: 20)
        String lawNameFilter,         // 법령명 필터 (optional)
        String articleNumberFilter,   // 조항 필터 (optional)
        Float bm25Weight,             // RRF 가중치 (기본: 0.5)
        Float vectorWeight,           // RRF 가중치 (기본: 0.5)
        Boolean useReranker           // Re-ranker 사용 여부 (기본: true)
    ) {
        /**
         * 기본 옵션 생성.
         */
        public static HybridSearchOptions defaults() {
            return new HybridSearchOptions(10, 20, 20, null, null, 0.5f, 0.5f, true);
        }
    }
}

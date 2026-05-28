package com.achul.compliance.rag.port;

import java.util.List;

/**
 * P1-7: Vector 의미 검색 포트.
 * pgvector + HNSW 인덱스 기반 유사도 검색.
 */
public interface VectorSearchPort {

    /**
     * 벡터 유사도 검색.
     * 사용자 질의의 임베딩 벡터와 규정 청크의 유사도를 계산해 상위 top-k를 반환.
     *
     * @param queryEmbedding 질의 임베딩 벡터
     * @param limit 반환 최대 건수
     * @return 유사도 상위 결과
     */
    List<VectorSearchResult> search(float[] queryEmbedding, int limit);

    /**
     * 텍스트 쿼리 기반 벡터 검색.
     * 내부적으로 쿼리를 임베딩 후 벡터 검색 수행.
     *
     * @param query 텍스트 질의
     * @param limit 반환 최대 건수
     * @param embeddingPort 임베딩 생성용 포트
     * @return 유사도 상위 결과
     */
    List<VectorSearchResult> searchByText(String query, int limit, EmbeddingPort embeddingPort);

    /**
     * 벡터 검색 결과.
     */
    record VectorSearchResult(
        Long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        Float similarity,        // 코사인 유사도 (0~1)
        String version
    ) {}
}

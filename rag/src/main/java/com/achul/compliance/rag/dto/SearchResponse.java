package com.achul.compliance.rag.dto;

import java.util.List;

/**
 * RAG-003: BM25 키워드 검색 응답 DTO.
 *
 * @param query        원본 쿼리
 * @param totalHits    반환된 결과 수
 * @param elapsedMs    검색 소요 시간(ms)
 * @param hits         결과 리스트
 */
public record SearchResponse(
    String query,
    int totalHits,
    long elapsedMs,
    List<Hit> hits
) {
    /**
     * @param regulationId   regulations.id
     * @param lawName        법령명
     * @param articleNumber  조항 번호
     * @param paragraphNumber 항 번호
     * @param itemNumber     호 번호
     * @param chunkText      본문 청크
     * @param bm25Score      ParadeDB BM25 점수 (raw)
     * @param boostedScore   메타 부스팅 적용 후 점수
     * @param version        규정 버전
     */
    public record Hit(
        Long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        Float bm25Score,
        Float boostedScore,
        String version
    ) {}
}

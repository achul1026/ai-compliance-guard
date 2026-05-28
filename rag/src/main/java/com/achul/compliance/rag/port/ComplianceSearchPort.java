package com.achul.compliance.rag.port;

import java.util.List;

/**
 * P1-8: 컴플라이언스 검색 API 포트.
 * 사용자 광고 카피 입력 → 관련 규정 조항 검색 → 에이전트에 전달.
 */
public interface ComplianceSearchPort {

    /**
     * 광고 카피 관련 규정 검색.
     *
     * @param advertisementCopy 검사할 광고 문구
     * @return 관련 규정 리스트 (컨텍스트 포함)
     */
    ComplianceSearchResponse searchRelatedRegulations(String advertisementCopy);

    /**
     * 검색 응답 모델.
     */
    record ComplianceSearchResponse(
        String query,                                    // 원본 광고 카피
        List<RegulationContext> relevantRegulations,     // 관련 규정 조항 리스트
        Integer totalHits,                               // 매칭된 전체 건수
        Long searchTimeMs                                // 검색 수행 시간 (모니터링용)
    ) {}

    /**
     * 규정 조항 컨텍스트 (에이전트에 전달될 정보).
     */
    record RegulationContext(
        Long regulationId,
        String lawName,                  // 법령명
        String articleNumber,            // 조·항·호
        String chunkText,                // 조항 본문
        String violationType,            // 택소노미 분류
        String version,                  // 규정 버전 (메타)
        Float relevanceScore,            // 0~1 관련도 점수
        String searchMatchMethod         // "bm25", "vector", "hybrid" 등
    ) {}
}

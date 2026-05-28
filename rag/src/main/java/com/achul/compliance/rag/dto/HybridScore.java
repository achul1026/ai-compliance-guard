package com.achul.compliance.rag.dto;

import com.achul.compliance.rag.port.KeywordSearchPort;
import com.achul.compliance.rag.port.VectorSearchPort;

/**
 * RAG-004: 하이브리드 결합 중간 점수 DTO.
 *
 * <p>벡터(코사인 유사도)와 키워드(BM25 정규화) 점수를 가중 합산(0.6·벡터 + 0.4·키워드)하여
 * 단일 정렬 키 {@link #totalScore()}를 산출한다.</p>
 *
 * <p>두 검색 경로 중 한쪽에서만 매칭된 후보도 표현할 수 있도록 양쪽 점수를 모두 보존한다.
 * 매칭되지 않은 쪽은 0.0f로 표현한다.</p>
 *
 * @param regulationId   regulations.id
 * @param vectorScore    벡터 가중치 적용 후 점수 (0~VECTOR_WEIGHT 범위)
 * @param keywordScore   BM25 가중치 적용 후 점수 (0~KEYWORD_WEIGHT 범위)
 * @param vectorResult   원본 벡터 검색 결과 (없으면 null)
 * @param keywordResult  원본 키워드 검색 결과 (없으면 null)
 */
public record HybridScore(
    Long regulationId,
    float vectorScore,
    float keywordScore,
    VectorSearchPort.VectorSearchResult vectorResult,
    KeywordSearchPort.KeywordSearchResult keywordResult
) {
    /**
     * 가중 합산된 최종 점수.
     */
    public float totalScore() {
        return vectorScore + keywordScore;
    }

    /**
     * 출처 청크 텍스트. 벡터 결과가 있으면 그것을, 없으면 키워드 결과를 사용.
     */
    public String chunkText() {
        if (vectorResult != null) return vectorResult.chunkText();
        if (keywordResult != null) return keywordResult.chunkText();
        return null;
    }

    /**
     * 두 경로 중 어디서 매칭되었는지 (디버깅·로깅용).
     * "vector", "keyword", "both" 중 하나.
     */
    public String matchedBy() {
        boolean v = vectorResult != null;
        boolean k = keywordResult != null;
        if (v && k) return "both";
        if (v) return "vector";
        return "keyword";
    }
}

package com.achul.compliance.rag.dto;

/**
 * RAG-004: Re-ranking 후 최종 결과 DTO.
 *
 * <p>Upstage Reranker(혹은 폴백 어댑터)로 재정렬된 결과를 표현한다.
 * 하이브리드 결합 단계의 {@link HybridScore}를 원본 보존하고
 * Reranker 점수와 새 순위를 부여한다.</p>
 *
 * @param source       하이브리드 결합 단계의 원본 점수 (벡터·키워드 점수 포함)
 * @param rerankScore  Reranker 점수 (구현체 정의 — Upstage Solar는 통상 0~1 정규화 값)
 * @param rank         재정렬 후 순위 (1부터 시작)
 */
public record RerankedResult(
    HybridScore source,
    float rerankScore,
    int rank
) {
    public Long regulationId() {
        return source.regulationId();
    }

    public String chunkText() {
        return source.chunkText();
    }
}

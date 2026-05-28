package com.achul.compliance.rag.port;

import java.util.List;

/**
 * ADR-003: 임베딩 모델 추상화 포트.
 * 1차: Upstage Solar-embedding (REST API)
 * 2차 옵션: BGE-m3 (로컬 배포)
 *
 * 구현체를 교체 가능하도록 인터페이스로 격리.
 */
public interface EmbeddingPort {

    /**
     * 단일 텍스트를 임베딩으로 변환.
     * 사용처: 사용자 질의, 에이전트 중간 결과 임베딩
     *
     * @param text 임베딩할 텍스트
     * @return float 배열 (차원은 dimension() 참고)
     */
    float[] embedQuery(String text);

    /**
     * 복수 텍스트를 배치로 임베딩.
     * 사용처: 규정 청크 초기 적재, 갱신 재적재
     *
     * @param texts 텍스트 리스트
     * @return float[][] 배열 리스트 (각 행의 길이는 dimension())
     */
    List<float[]> embedDocuments(List<String> texts);

    /**
     * 임베딩 차원 반환.
     *
     * @return 벡터 차원 (Upstage Solar: 4096, BGE-m3: 1024)
     */
    int dimension();

    /**
     * 모델 이름 반환 (로깅/모니터링용).
     *
     * @return 모델 식별자
     */
    String modelName();
}

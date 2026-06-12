package com.achul.compliance.agent.port;

/**
 * P2-1: LLM 호출 포트 (ADR-006).
 *
 * <p>에이전트(Auditor/Critique/Remediator)는 이 포트로만 LLM에 접근한다.
 * 프로바이더는 {@code agent.llm.provider}로 토글한다 — gemini(기본 무료 티어) | mock(테스트).
 * 수익화 후 유료 모델 전환 시 어댑터만 추가하면 된다.</p>
 */
public interface LlmPort {

    /**
     * 시스템 프롬프트 + 사용자 메시지로 완성 텍스트를 생성한다.
     *
     * @param systemPrompt 에이전트 역할 정의 (null 허용)
     * @param userMessage  입력 (광고 카피, 검색 컨텍스트 등)
     * @return LLM 응답 텍스트
     */
    String complete(String systemPrompt, String userMessage);

    /** 사용 중인 모델 식별자 (로깅·리포트용). */
    String modelName();
}

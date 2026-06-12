package com.achul.compliance.agent.adapter;

import com.achul.compliance.agent.port.LlmPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 테스트·개발용 Mock LLM (외부 호출 0건 — 비용 가드).
 *
 * <p>{@code agent.llm.provider} 미설정 시 기본 활성(matchIfMissing). 결정적 응답을
 * 반환하므로 단위 테스트와 CI에서 API 키 없이 동작한다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmAdapter implements LlmPort {

    @Override
    public String complete(String systemPrompt, String userMessage) {
        log.debug("MockLlmAdapter called. systemPromptLength={}, userMessageLength={}",
            systemPrompt == null ? 0 : systemPrompt.length(), userMessage.length());
        return "[MOCK] system=" + (systemPrompt == null ? "" : systemPrompt.strip())
            + " | user=" + userMessage.strip();
    }

    @Override
    public String modelName() {
        return "mock";
    }
}

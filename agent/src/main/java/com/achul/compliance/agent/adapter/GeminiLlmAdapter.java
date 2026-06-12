package com.achul.compliance.agent.adapter;

import com.achul.compliance.agent.port.LlmPort;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ADR-006: Gemini Flash 무료 티어 어댑터.
 *
 * <p>무료 한도(10 RPM / 1,500 RPD)를 전제로 하며, 한도 초과 시 LangChain4j의
 * 재시도(maxRetries)로 1차 흡수한다. 실고객 데이터 수신 시점에는 유료 티어로
 * 전환해야 한다(무료 티어 입력은 Google 모델 학습에 사용될 수 있음 — ADR-006 §트레이드오프).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "gemini")
public class GeminiLlmAdapter implements LlmPort {

    private final ChatModel chatModel;
    private final String modelName;

    public GeminiLlmAdapter(
        @Value("${agent.llm.gemini.api-key}") String apiKey,
        @Value("${agent.llm.gemini.model:gemini-flash-latest}") String modelName,
        @Value("${agent.llm.gemini.temperature:0.2}") double temperature,
        @Value("${agent.llm.gemini.timeout-seconds:60}") long timeoutSeconds,
        @Value("${agent.llm.gemini.max-retries:2}") int maxRetries
    ) {
        this.modelName = modelName;
        this.chatModel = GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(temperature)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .maxRetries(maxRetries)
            .build();
        log.info("GeminiLlmAdapter initialized. model={}", modelName);
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        long start = System.currentTimeMillis();
        String response = chatModel.chat(messages).aiMessage().text();
        log.debug("Gemini completion done. elapsedMs={}, responseLength={}",
            System.currentTimeMillis() - start, response == null ? 0 : response.length());
        return response;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}

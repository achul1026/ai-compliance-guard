package com.achul.compliance.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P2-2: LLM 응답에서 JSON을 관대하게 추출·파싱한다.
 *
 * <p>LLM은 코드펜스(```json ... ```)나 부연 설명을 붙이는 경우가 있어,
 * 첫 '{'부터 마지막 '}'까지를 잘라 파싱한다. 실패 시 호출부가 재시도를 결정한다.</p>
 */
public final class LlmJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJsonParser() {
    }

    /**
     * @throws LlmJsonParseException JSON 블록이 없거나 역직렬화 실패 시
     */
    public static <T> T parse(String llmResponse, Class<T> type) {
        if (llmResponse == null || llmResponse.isBlank()) {
            throw new LlmJsonParseException("LLM 응답이 비어 있음", llmResponse);
        }
        int start = llmResponse.indexOf('{');
        int end = llmResponse.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmJsonParseException("응답에 JSON 객체가 없음", llmResponse);
        }
        String json = llmResponse.substring(start, end + 1);
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new LlmJsonParseException("JSON 역직렬화 실패: " + e.getMessage(), llmResponse);
        }
    }

    public static class LlmJsonParseException extends RuntimeException {
        private final String rawResponse;

        public LlmJsonParseException(String message, String rawResponse) {
            super(message);
            this.rawResponse = rawResponse;
        }

        public String rawResponse() {
            return rawResponse;
        }
    }
}

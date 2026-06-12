package com.achul.compliance.agent.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-1: Mock LLM 어댑터 — 외부 호출 없이 결정적 응답을 보장한다.
 */
class MockLlmAdapterTest {

    private final MockLlmAdapter adapter = new MockLlmAdapter();

    @Test
    void complete_returnsDeterministicResponse_containingInputs() {
        String response = adapter.complete("시스템 역할", "광고 카피 검사");

        assertTrue(response.startsWith("[MOCK]"));
        assertTrue(response.contains("시스템 역할"));
        assertTrue(response.contains("광고 카피 검사"));
    }

    @Test
    void complete_handlesNullSystemPrompt() {
        String response = adapter.complete(null, "입력");

        assertTrue(response.startsWith("[MOCK]"));
        assertTrue(response.contains("입력"));
    }

    @Test
    void modelName_isMock() {
        assertEquals("mock", adapter.modelName());
    }
}

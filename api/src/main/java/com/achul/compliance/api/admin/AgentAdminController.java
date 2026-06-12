package com.achul.compliance.api.admin;

import com.achul.compliance.agent.port.LlmPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P2-1: 단일 에이전트 호출 E2E 검증 엔드포인트.
 *
 * <p>오케스트레이션 도입 전 "광고 카피 입력 → LLM 응답 1회"가 끝까지 통하는지 확인한다.
 * provider 토글: {@code AGENT_LLM_PROVIDER=gemini} + {@code GEMINI_API_KEY} 주입 시 실호출.</p>
 */
@Slf4j
@RestController
@RequestMapping("/admin/agent")
public class AgentAdminController {

    private final LlmPort llmPort;

    public AgentAdminController(LlmPort llmPort) {
        this.llmPort = llmPort;
    }

    /**
     * 엔드포인트: {@code POST /api/v1/admin/agent/ping} — 본문 {@code {"message": "..."}}.
     */
    @PostMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "건강기능식품 광고 문구를 한 문장으로 평가해줘: 이 영양제는 피로 회복에 도움을 줄 수 있습니다.");

        long start = System.currentTimeMillis();
        String response = llmPort.complete(
            "너는 한국 식품·화장품 표시광고 규제 전문가다. 간결하게 답한다.",
            message
        );
        long elapsed = System.currentTimeMillis() - start;

        log.info("Agent ping completed. model={}, elapsedMs={}", llmPort.modelName(), elapsed);
        return ResponseEntity.ok(Map.of(
            "model", llmPort.modelName(),
            "response", response,
            "elapsedMs", elapsed
        ));
    }
}

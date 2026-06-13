package com.achul.compliance.api;

import com.achul.compliance.api.health.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 레이어 스모크 테스트.
 *
 * <p>Phase 1부터 애플리케이션이 DB(PostgreSQL+pgvector)를 필수로 요구하므로
 * "DB 없는 풀컨텍스트 로드"(Phase 0 계약)는 더 이상 성립하지 않는다.
 * 풀컨텍스트 검증은 Testcontainers 기반 통합 테스트 트랙(Task #3)에서 수행한다.</p>
 */
@WebMvcTest(HealthController.class)
class ApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointResponds() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}

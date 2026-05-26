package com.achul.compliance.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("default")
class ApplicationTests {

    @Test
    void contextLoads() {
        // Phase 0: 기본 프로파일에서 ApplicationContext가 정상 로드되는지 확인.
        // DB autoconfigure가 제외되어 있어 DB 없이도 통과해야 한다.
    }
}

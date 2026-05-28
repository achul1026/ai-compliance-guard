package com.achul.compliance.api.admin;

import com.achul.compliance.infra.persistence.entity.RegulationEntity;
import com.achul.compliance.infra.persistence.repository.RegulationRepository;
import com.achul.compliance.rag.service.BatchEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * P1-5: 배치 임베딩 관리 API (테스트용).
 * 더미 데이터 생성, 임베딩 상태 조회, 배치 임베딩 트리거.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/embedding")
public class EmbeddingAdminController {

    private final RegulationRepository regulationRepository;
    private final BatchEmbeddingService batchEmbeddingService;

    public EmbeddingAdminController(
        RegulationRepository regulationRepository,
        BatchEmbeddingService batchEmbeddingService
    ) {
        this.regulationRepository = regulationRepository;
        this.batchEmbeddingService = batchEmbeddingService;
    }

    /**
     * 더미 규정 데이터 생성 (테스트용).
     */
    @PostMapping("/init-test-data")
    public ResponseEntity<Map<String, Object>> initTestData(@RequestParam(defaultValue = "10") int count) {
        log.info("Creating {} dummy regulation records", count);

        for (int i = 1; i <= count; i++) {
            RegulationEntity regulation = new RegulationEntity();
            regulation.setSource("test_source");
            regulation.setLawName("식품표시광고법");
            regulation.setArticleNumber("제" + (i % 30 + 1) + "조");
            regulation.setParagraphNumber("제" + (i % 5 + 1) + "항");
            regulation.setItemNumber("제" + (i % 10 + 1) + "호");
            regulation.setChunkText("이것은 테스트 규정 청크입니다. 테스트 번호: " + i + ". " +
                "당뇨병을 예방한다는 표현은 금지됩니다. 건강기능식품 표시·광고 시 주의가 필요합니다.");
            regulation.setViolationType("질병_예방_치료_표방");
            regulation.setEffectiveDate(LocalDate.of(2026, 1, 1));
            regulation.setVersion("1.0");

            regulationRepository.save(regulation);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Created " + count + " test records");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * 임베딩 상태 조회.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getEmbeddingStatus() {
        var stats = batchEmbeddingService.getEmbeddingStats();

        Map<String, Object> response = new HashMap<>();
        response.put("total", stats.total());
        response.put("embedded", stats.embedded());
        response.put("notEmbedded", stats.notEmbedded());
        response.put("embeddingRate", stats.total() > 0 ?
            String.format("%.1f%%", 100.0 * stats.embedded() / stats.total()) : "N/A");

        return ResponseEntity.ok(response);
    }

    /**
     * 배치 임베딩 시작.
     */
    @PostMapping("/batch-embed")
    public ResponseEntity<Map<String, Object>> startBatchEmbedding() {
        log.info("Starting batch embedding...");

        try {
            int embedded = batchEmbeddingService.embedMissingRegulations();

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Batch embedding completed");
            response.put("embeddedCount", embedded);
            response.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Batch embedding failed", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 전체 규정 삭제 (개발 전용).
     */
    @DeleteMapping("/clear-all")
    public ResponseEntity<Map<String, Object>> clearAllRegulations() {
        log.warn("Clearing all regulations");
        regulationRepository.deleteAll();

        Map<String, Object> response = new HashMap<>();
        response.put("message", "All regulations cleared");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}

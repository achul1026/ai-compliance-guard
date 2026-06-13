package com.achul.compliance.api.audit;

import com.achul.compliance.agent.workflow.ComplianceAuditService;
import com.achul.compliance.agent.workflow.ComplianceReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2-6: 컴플라이언스 분석 API — Phase 2 핵심 엔드포인트.
 *
 * <p>엔드포인트: {@code POST /api/v1/audit} (context-path {@code /api/v1} + 매핑 {@code /audit}).
 * 광고 카피 입력 → Hybrid RAG 검색 → 검사관→비판관(루프)→교정관 → 최종 리포트.</p>
 */
@Slf4j
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final ComplianceAuditService auditService;

    public AuditController(ComplianceAuditService auditService) {
        this.auditService = auditService;
    }

    @PostMapping
    public ResponseEntity<ComplianceReport> audit(@Valid @RequestBody AuditRequest request) {
        log.info("Audit request received. copyLength={}", request.copy().length());
        return ResponseEntity.ok(auditService.audit(request.copy()));
    }

    public record AuditRequest(
        @NotBlank(message = "광고 카피는 비어 있을 수 없습니다")
        @Size(max = 5000, message = "광고 카피는 5,000자를 초과할 수 없습니다")
        String copy
    ) {}
}

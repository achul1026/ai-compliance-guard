package com.achul.compliance.api.audit;

import com.achul.compliance.agent.workflow.ComplianceAuditService;
import com.achul.compliance.agent.workflow.ComplianceReport;
import com.achul.compliance.api.auth.JwtAuthenticationFilter.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * P2-6 / P3-3: 컴플라이언스 분석 API.
 *
 * <p>로그인 필수(ADR-008) + Free Tier 월 {@code app.usage.free-monthly-limit}회 제한(P3-3).
 * 광고 카피 → Hybrid RAG → 검사관→비판관(루프)→교정관 → 리포트 + 이력 저장.</p>
 */
@Slf4j
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final ComplianceAuditService auditService;
    private final UsageService usageService;

    public AuditController(ComplianceAuditService auditService, UsageService usageService) {
        this.auditService = auditService;
        this.usageService = usageService;
    }

    @PostMapping
    public ResponseEntity<ComplianceReport> audit(
        @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody AuditRequest request
    ) {
        Long userId = principal.userId();

        int remaining = usageService.remainingThisMonth(userId);
        if (remaining <= 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "이번 달 무료 검사 " + usageService.freeMonthlyLimit() + "회를 모두 사용했습니다.");
        }

        log.info("Audit request. userId={}, copyLength={}, remaining={}", userId, request.copy().length(), remaining);
        ComplianceReport report = auditService.audit(request.copy());
        usageService.record(userId, request.copy(), report);

        return ResponseEntity.ok()
            .header("X-RateLimit-Limit", String.valueOf(usageService.freeMonthlyLimit()))
            .header("X-RateLimit-Remaining", String.valueOf(remaining - 1))
            .body(report);
    }

    /** P3-3: 이번 달 무료 사용량 조회 (UI 한도 표시용). */
    @GetMapping("/usage")
    public ResponseEntity<UsageResponse> usage(@AuthenticationPrincipal AuthPrincipal principal) {
        int remaining = usageService.remainingThisMonth(principal.userId());
        return ResponseEntity.ok(new UsageResponse(usageService.freeMonthlyLimit(), remaining));
    }

    /** P3-1/P3-2: 내 분석 이력(복호화된 평문, 최근 20건). */
    @GetMapping("/history")
    public ResponseEntity<List<UsageService.HistoryItem>> history(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(usageService.recentHistory(principal.userId(), 20));
    }

    public record AuditRequest(
        @NotBlank(message = "광고 카피는 비어 있을 수 없습니다")
        @Size(max = 5000, message = "광고 카피는 5,000자를 초과할 수 없습니다")
        String copy
    ) {}

    public record UsageResponse(int limit, int remaining) {}
}

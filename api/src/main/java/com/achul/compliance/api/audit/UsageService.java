package com.achul.compliance.api.audit;

import com.achul.compliance.agent.workflow.ComplianceReport;
import com.achul.compliance.infra.persistence.entity.AuditHistoryEntity;
import com.achul.compliance.infra.persistence.repository.AuditHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * P3-3: Free Tier 사용량 카운팅 + 분석 이력 저장.
 *
 * <p>사용량은 별도 카운터 없이 {@code audit_history}의 이번 달 행 수로 센다(이력=카운터).
 * 월 경계는 한국 시간(Asia/Seoul) 기준 매월 1일 0시.</p>
 */
@Slf4j
@Service
public class UsageService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AuditHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;
    private final int freeMonthlyLimit;

    public UsageService(
        AuditHistoryRepository historyRepository,
        ObjectMapper objectMapper,
        @Value("${app.usage.free-monthly-limit:5}") int freeMonthlyLimit
    ) {
        this.historyRepository = historyRepository;
        this.objectMapper = objectMapper;
        this.freeMonthlyLimit = freeMonthlyLimit;
    }

    public int freeMonthlyLimit() {
        return freeMonthlyLimit;
    }

    /** 이번 달 남은 무료 횟수(0 이상). */
    @Transactional(readOnly = true)
    public int remainingThisMonth(Long userId) {
        long used = historyRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, monthStart());
        return Math.max(0, freeMonthlyLimit - (int) used);
    }

    /** 분석 결과를 이력으로 저장(= 사용량 1 소진). */
    @Transactional
    public void record(Long userId, String adCopy, ComplianceReport report) {
        String json = serialize(report);
        String risk = report.audit() == null ? null : report.audit().overallRisk();
        historyRepository.save(new AuditHistoryEntity(userId, adCopy, json, risk));
    }

    /** 사용자 최근 분석 이력(복호화된 평문). adCopy는 컨버터가 자동 복호화한다. */
    @Transactional(readOnly = true)
    public List<HistoryItem> recentHistory(Long userId, int limit) {
        return historyRepository
            .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
            .map(h -> new HistoryItem(h.getId(), h.getAdCopy(), h.getOverallRisk(), h.getCreatedAt()))
            .getContent();
    }

    public record HistoryItem(Long id, String adCopy, String overallRisk, OffsetDateTime createdAt) {}

    private String serialize(ComplianceReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.warn("리포트 직렬화 실패, 빈 객체로 저장", e);
            return "{}";
        }
    }

    private OffsetDateTime monthStart() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        return now.toLocalDate().withDayOfMonth(1).atStartOfDay(KST).toOffsetDateTime();
    }
}

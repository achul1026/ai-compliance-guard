package com.achul.compliance.api.audit;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.workflow.ComplianceReport;
import com.achul.compliance.infra.persistence.entity.AuditHistoryEntity;
import com.achul.compliance.infra.persistence.repository.AuditHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * P3-3: 사용량 카운팅 + 이력 저장 단위 테스트.
 */
class UsageServiceTest {

    private final AuditHistoryRepository repository = mock(AuditHistoryRepository.class);
    private final UsageService service = new UsageService(repository, new ObjectMapper(), 5);

    private static ComplianceReport report(String risk) {
        return new ComplianceReport(
            "광고 카피",
            new AuditReport(List.of(), risk, "요약"),
            null, List.of(), 1, true, "mock");
    }

    @Test
    void remaining_subtractsUsedFromLimit() {
        given(repository.countByUserIdAndCreatedAtGreaterThanEqual(eq(1L), any())).willReturn(2L);
        assertEquals(3, service.remainingThisMonth(1L));
    }

    @Test
    void remaining_isZero_whenLimitReached() {
        given(repository.countByUserIdAndCreatedAtGreaterThanEqual(eq(1L), any())).willReturn(5L);
        assertEquals(0, service.remainingThisMonth(1L));
    }

    @Test
    void remaining_neverNegative_whenOverLimit() {
        given(repository.countByUserIdAndCreatedAtGreaterThanEqual(eq(1L), any())).willReturn(7L);
        assertEquals(0, service.remainingThisMonth(1L));
    }

    @Test
    void record_persistsHistory_withSerializedReportAndRisk() {
        service.record(42L, "당뇨병 예방", report("high"));

        ArgumentCaptor<AuditHistoryEntity> captor = ArgumentCaptor.forClass(AuditHistoryEntity.class);
        verify(repository).save(captor.capture());
        AuditHistoryEntity saved = captor.getValue();

        assertEquals(42L, saved.getUserId());
        assertEquals("당뇨병 예방", saved.getAdCopy());
        assertEquals("high", saved.getOverallRisk());
        assertTrue(saved.getReportJson().contains("\"overall_risk\":\"high\""),
            "리포트가 JSON으로 직렬화되어야 함");
    }

    @Test
    void freeMonthlyLimit_isConfigured() {
        assertEquals(5, service.freeMonthlyLimit());
    }
}

package com.achul.compliance.infra.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 분석 이력 + 사용량 카운터 엔티티 (P3-3, V7).
 * adCopy·reportJson은 P3-2에서 AES-256 암호화 예정.
 */
@Entity
@Table(name = "audit_history")
public class AuditHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ad_copy", nullable = false, columnDefinition = "TEXT")
    private String adCopy;

    @Column(name = "report_json", nullable = false, columnDefinition = "TEXT")
    private String reportJson;

    @Column(name = "overall_risk", length = 20)
    private String overallRisk;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditHistoryEntity() {
    }

    public AuditHistoryEntity(Long userId, String adCopy, String reportJson, String overallRisk) {
        this.userId = userId;
        this.adCopy = adCopy;
        this.reportJson = reportJson;
        this.overallRisk = overallRisk;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAdCopy() {
        return adCopy;
    }

    public String getReportJson() {
        return reportJson;
    }

    public String getOverallRisk() {
        return overallRisk;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

package com.achul.compliance.infra.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 규정 청크 엔티티.
 * P1-4: DB 스키마 기반 JPA 엔티티 (V2 마이그레이션)
 */
@Entity
@Table(name = "regulations", indexes = {
    @Index(name = "idx_regulations_law_name", columnList = "law_name"),
    @Index(name = "idx_regulations_article_number", columnList = "article_number"),
    @Index(name = "idx_regulations_law_article_composite", columnList = "law_name, article_number, paragraph_number, item_number"),
    @Index(name = "idx_regulations_effective_date", columnList = "effective_date DESC"),
    @Index(name = "idx_regulations_version", columnList = "version")
})
public class RegulationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 규정 소스 (예: "law.go.kr", "협회자율심의", "식약처고시")
     */
    @Column(nullable = false, length = 255)
    private String source;

    /**
     * 법령명 (예: "식품표시광고법", "건강기능식품법")
     */
    @Column(nullable = false, length = 255)
    private String lawName;

    /**
     * 조 번호 (예: "제8조")
     */
    @Column(nullable = false, length = 50)
    private String articleNumber;

    /**
     * 항 번호 (예: "제1항", null 가능)
     */
    @Column(length = 50)
    private String paragraphNumber;

    /**
     * 호 번호 (예: "제1호", null 가능)
     */
    @Column(length = 50)
    private String itemNumber;

    /**
     * 청크 텍스트 (BM25 인덱싱 대상)
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    /**
     * 임베딩 벡터 (4096차원, ADR-003)
     * pgvector 확장 타입 사용.
     */
    @Column(columnDefinition = "vector(4096)")
    private float[] embedding;

    /**
     * 택소노미 분류 (예: "violation_type_1", 저장용 JSONB는 metadata에)
     */
    @Column(length = 100)
    private String violationType;

    /**
     * 추가 메타데이터 (JSONB).
     * 예: {"source_url": "...", "collected_date": "2026-05-26", "note": "..."}
     */
    @Column(columnDefinition = "JSONB")
    private String metadata;

    /**
     * 규정 시행일 (버전 관리용, OPERATIONS.md)
     */
    @Column
    private LocalDate effectiveDate;

    /**
     * 규정 버전 (예: "2026-05-26", 갱신 추적용)
     */
    @Column(length = 50)
    private String version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    // Getters & Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getLawName() {
        return lawName;
    }

    public void setLawName(String lawName) {
        this.lawName = lawName;
    }

    public String getArticleNumber() {
        return articleNumber;
    }

    public void setArticleNumber(String articleNumber) {
        this.articleNumber = articleNumber;
    }

    public String getParagraphNumber() {
        return paragraphNumber;
    }

    public void setParagraphNumber(String paragraphNumber) {
        this.paragraphNumber = paragraphNumber;
    }

    public String getItemNumber() {
        return itemNumber;
    }

    public void setItemNumber(String itemNumber) {
        this.itemNumber = itemNumber;
    }

    public String getChunkText() {
        return chunkText;
    }

    public void setChunkText(String chunkText) {
        this.chunkText = chunkText;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public String getViolationType() {
        return violationType;
    }

    public void setViolationType(String violationType) {
        this.violationType = violationType;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

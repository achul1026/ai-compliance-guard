package com.achul.compliance.infra.persistence.repository;

import com.achul.compliance.infra.persistence.entity.AuditHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/**
 * 분석 이력 저장소 (P3-3).
 */
@Repository
public interface AuditHistoryRepository extends JpaRepository<AuditHistoryEntity, Long> {

    /** Free Tier 월 사용량: 기준 시각 이후 해당 사용자의 분석 횟수. */
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, OffsetDateTime since);

    /** 사용자 분석 이력(최신순). */
    Page<AuditHistoryEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}

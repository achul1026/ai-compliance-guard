package com.achul.compliance.infra.persistence.repository;

import com.achul.compliance.infra.persistence.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Refresh token 화이트리스트 저장소 (ADR-008, P3-1).
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Transactional
    void deleteByTokenHash(String tokenHash);

    /** 로그아웃 시 사용자의 모든 세션 무효화. */
    @Modifying
    @Transactional
    void deleteByUserId(Long userId);

    /** 만료 토큰 정리 (배치/스케줄러용). */
    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshTokenEntity t WHERE t.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}

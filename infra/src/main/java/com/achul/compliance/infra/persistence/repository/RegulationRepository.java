package com.achul.compliance.infra.persistence.repository;

import com.achul.compliance.infra.persistence.entity.RegulationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * P1-4: 규정 청크 저장소.
 * DB 쿼리 최적화: 벡터 검색/BM25 구현체가 의존.
 */
@Repository
public interface RegulationRepository extends JpaRepository<RegulationEntity, Long> {

    /**
     * 법령명으로 규정 조회.
     */
    List<RegulationEntity> findByLawName(String lawName);

    /**
     * 법령명 + 조항 번호 복합 조회.
     */
    List<RegulationEntity> findByLawNameAndArticleNumber(String lawName, String articleNumber);

    /**
     * 법령명 + 조항 + 항 복합 조회.
     */
    List<RegulationEntity> findByLawNameAndArticleNumberAndParagraphNumber(
        String lawName,
        String articleNumber,
        String paragraphNumber
    );

    /**
     * 위반 유형별 조회 (택소노미).
     */
    List<RegulationEntity> findByViolationType(String violationType);

    /**
     * 시행일 기준 최신 규정 조회.
     */
    List<RegulationEntity> findByEffectiveDateGreaterThanEqual(LocalDate effectiveDate);

    /**
     * 버전별 규정 조회.
     */
    List<RegulationEntity> findByVersion(String version);

    /**
     * 소스별 규정 수 (데이터 품질 모니터링용).
     */
    Long countBySource(String source);

    /**
     * 전체 규정 수.
     */
    @Query("SELECT COUNT(r) FROM RegulationEntity r")
    Long countAllRegulations();

    /**
     * 임베딩이 설정된 규정 수 (P1-5 진행 상황 모니터링).
     */
    @Query("SELECT COUNT(r) FROM RegulationEntity r WHERE r.embedding IS NOT NULL")
    Long countEmbeddedRegulations();

    /**
     * 페이지네이션: 전체 규정 조회.
     */
    Page<RegulationEntity> findAll(Pageable pageable);

    /**
     * 임베딩 미설정 규정 전체 조회 (배치 임베딩용).
     */
    @Query("SELECT r FROM RegulationEntity r WHERE r.embedding IS NULL")
    List<RegulationEntity> findNotEmbeddedRegulations();

    /**
     * 페이지네이션: 임베딩 미설정 규정 조회.
     */
    @Query("SELECT r FROM RegulationEntity r WHERE r.embedding IS NULL")
    Page<RegulationEntity> findNotEmbeddedRegulations(Pageable pageable);

    /**
     * 임베딩이 설정된 규정 수.
     */
    @Query("SELECT COUNT(r) FROM RegulationEntity r WHERE r.embedding IS NOT NULL")
    Long countByEmbeddingIsNotNull();

    /**
     * 법령명 + 조항 + 항 정확 매칭 (메타데이터 필터링).
     */
    @Query("SELECT r FROM RegulationEntity r " +
           "WHERE r.lawName = :lawName " +
           "AND r.articleNumber = :articleNumber " +
           "AND (:paragraphNumber IS NULL OR r.paragraphNumber = :paragraphNumber) " +
           "AND (:itemNumber IS NULL OR r.itemNumber = :itemNumber)")
    List<RegulationEntity> findByMetadataExact(
        @Param("lawName") String lawName,
        @Param("articleNumber") String articleNumber,
        @Param("paragraphNumber") String paragraphNumber,
        @Param("itemNumber") String itemNumber
    );

    /**
     * 텍스트 검색 (LIKE, 간단한 BM25 이전 검색).
     * Phase 1-6에서 ParadeDB BM25로 대체되지만, 임시 구현용.
     */
    @Query(value = "SELECT r FROM RegulationEntity r WHERE r.chunkText LIKE CONCAT('%', :keyword, '%')")
    Page<RegulationEntity> findByChunkTextContains(@Param("keyword") String keyword, Pageable pageable);
}

-- V5: BGE-m3 임베딩 차원 전환 (4096 → 1024) + HNSW 인덱스 신규 생성
-- ADR-005 적용. Upstage Solar 어댑터는 보존(@ConditionalOnProperty 토글).
-- 사유:
--   1) pgvector HNSW vector_*_ops 최대 2000차원 → 1024는 직접 적용 가능
--   2) 외부 API 의존 제거 (FastAPI 임베딩 서버 로컬 운영)
--   3) V3 정책(인덱스 미생성)의 전제(4096차원)가 해소됨

-- 1) 기존 4096차원 임베딩 무효화 (BGE-m3로 전량 재임베딩 예정)
UPDATE regulations SET embedding = NULL WHERE embedding IS NOT NULL;

-- 2) 벡터 컬럼 차원 변경 (4096 → 1024)
ALTER TABLE regulations ALTER COLUMN embedding TYPE vector(1024);

-- 3) 임베딩 모델 식별 컬럼 (다중 임베딩 운영 추적용)
ALTER TABLE regulations ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);

-- 4) HNSW 벡터 인덱스 신규 생성 (cosine 거리, pgvector 권장 기본값)
CREATE INDEX IF NOT EXISTS idx_regulations_embedding_hnsw
    ON regulations
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 5) 통계 갱신
ANALYZE regulations;

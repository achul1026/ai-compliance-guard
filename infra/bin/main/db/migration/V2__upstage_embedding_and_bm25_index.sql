-- Phase 1 - P1-4: Upstage Solar-embedding 도입 (ADR-003)
-- Vector 차원: 1536 → 4096 (Upstage solar-embedding-1-large 기준)
-- 추가 메타데이터: BM25 검색 및 규정 버전 관리용 컬럼

-- 1. regulations 테이블 확장 (메타데이터 컬럼 추가)
ALTER TABLE regulations ADD COLUMN IF NOT EXISTS paragraph_number VARCHAR(50);
ALTER TABLE regulations ADD COLUMN IF NOT EXISTS item_number VARCHAR(50);
ALTER TABLE regulations ADD COLUMN IF NOT EXISTS effective_date DATE;
ALTER TABLE regulations ADD COLUMN IF NOT EXISTS version VARCHAR(50);
-- 위반 유형 택소노미 분류 (DATA-003, RegulationEntity.violationType 매핑)
ALTER TABLE regulations ADD COLUMN IF NOT EXISTS violation_type VARCHAR(100);

-- 2. 벡터 차원 업그레이드 (1536 → 4096)
-- PostgreSQL에서는 기존 컬럼 타입 변경 시 데이터가 있으면 복잡하므로
-- 임베딩이 아직 채워지지 않은 Phase 0 상태라고 가정하고 직접 변경
-- (실제 환경에서는 데이터 백업 후 진행 권장)
ALTER TABLE regulations ALTER COLUMN embedding TYPE vector(4096);

-- 3. ParadeDB BM25 인덱스 (ADR-002)
-- pg_search 확장이 ParadeDB 이미지에 포함되어 있다고 가정
CREATE EXTENSION IF NOT EXISTS pg_search;

-- chunk_text를 BM25 검색 대상으로 지정
-- pg_search 0.10+ 문법: 인덱스에 포함될 컬럼과 key_field를 명시
CREATE INDEX IF NOT EXISTS idx_regulations_bm25_chunk
    ON regulations
    USING bm25 (id, chunk_text)
    WITH (key_field = 'id');

-- 4. 메타데이터 부스팅용 인덱스 (ADR-002 메타데이터 필터링)
CREATE INDEX IF NOT EXISTS idx_regulations_law_article_composite
    ON regulations (law_name, article_number, paragraph_number, item_number);

CREATE INDEX IF NOT EXISTS idx_regulations_effective_date
    ON regulations (effective_date DESC);

CREATE INDEX IF NOT EXISTS idx_regulations_version
    ON regulations (version);

-- 5. HNSW 벡터 인덱스는 임베딩 적재 후 V3 마이그레이션에서 추가
-- (대규모 데이터 로딩 후 인덱스 빌드는 성능 고려)
-- CREATE INDEX ON regulations USING hnsw (embedding vector_cosine_ops);

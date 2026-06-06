-- Phase 0 초기 스키마.
-- pgvector 확장은 Docker init(01-init.sql)에서 이미 생성되어 있지만,
-- 다른 환경(RDS 등)에서도 안전하도록 idempotent하게 다시 보장한다.
CREATE EXTENSION IF NOT EXISTS vector;

-- Phase 1에서 규정 청크를 적재할 테이블 골격.
-- Phase 0 검증 목적으로 미리 생성한다. embedding 차원은 D3 확정 후 변경 가능.
CREATE TABLE regulations (
    id              BIGSERIAL PRIMARY KEY,
    source          VARCHAR(255) NOT NULL,
    law_name        VARCHAR(255) NOT NULL,
    article_number  VARCHAR(50)  NOT NULL,
    chunk_text      TEXT         NOT NULL,
    embedding       vector(1536),
    metadata        JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_regulations_law_name       ON regulations (law_name);
CREATE INDEX idx_regulations_article_number ON regulations (article_number);
-- 벡터 인덱스는 임베딩 적재 후 Phase 1에서 추가:
-- CREATE INDEX ON regulations USING hnsw (embedding vector_cosine_ops);

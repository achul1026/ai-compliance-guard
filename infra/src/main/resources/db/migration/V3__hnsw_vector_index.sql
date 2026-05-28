-- P1-7: HNSW 벡터 인덱스 생성 (성능 최적화)
-- pgvector HNSW 인덱스: 대규모 데이터셋에서 빠른 유사도 검색
-- 사전조건: V2 마이그레이션으로 vector(4096) 컬럼 생성 완료
-- 실행 시점: 초기 규정 청크 임베딩 완료 후 (약 6000~10000개)

-- HNSW 인덱스 생성
-- 파라미터:
--   m=16: HNSW 그래프의 연결 정도 (기본값, 메모리 효율성과 성능의 균형)
--   ef_construction=200: 구성 시 탐색 폭 (높을수록 정확하지만 느림)
CREATE INDEX CONCURRENTLY idx_regulations_embedding_hnsw
  ON regulations USING hnsw (embedding vector_cosine_ops)
  WITH (m=16, ef_construction=200);

-- 통계 업데이트 (쿼리 계획 최적화)
ANALYZE regulations;

-- 인덱스 상태 확인 쿼리 (별도 실행 권장):
-- SELECT indexname, indexdef FROM pg_indexes
-- WHERE tablename = 'regulations' AND indexname LIKE '%hnsw%';
--
-- pgvector 인덱스 정보 조회:
-- SELECT * FROM pg_stat_user_indexes
-- WHERE relname = 'regulations' AND indexrelname LIKE '%hnsw%';

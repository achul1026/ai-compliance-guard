-- P1-7: 벡터 인덱스 정책 (RAG-001 검증 결과 반영)
-- 사전조건: V2 마이그레이션으로 vector(4096) 컬럼 생성 완료
--
-- 중요 제약 (RAG-001 검증 중 발견):
--   pgvector HNSW: 최대 2000차원 (vector_*_ops)
--   pgvector HNSW: 최대 4000차원 (halfvec_*_ops)
--   Upstage solar-embedding-1-large는 4096차원 → HNSW 직접 적용 불가
--
-- 해결 정책 (ADR-003 후속 결정, Phase 1 규모에 한정):
--   1) Phase 1 (수백~수천 청크): 인덱스 없이 시퀀셜 스캔으로 충분.
--      127개 청크(DATA-002) 기준 cosine 거리 brute-force는 수 ms 수준.
--      → HNSW 인덱스 생성을 건너뛴다.
--   2) Phase 1.5+ (수만 청크): 다음 중 택1로 마이그레이션 V4 작성 예정
--      a) bit quantization (binary HNSW): 4096-bit 이진화 → hnsw bit_hamming_ops
--         원본 vector(4096)로 후처리 재정렬 (two-stage retrieval)
--      b) halfvec subvector(앞 2000차원) 표현식 인덱스 + 원본 재정렬
--      c) Solar Matryoshka 차원 축소 모델 출시 시 vector(2000)로 마이그레이션
--   3) Phase 2+ (수십만+ 청크): BGE-m3(1024차원) 또는 외부 벡터 DB 검토

-- 본 마이그레이션은 통계만 갱신한다 (쿼리 플래너 힌트).
ANALYZE regulations;

-- 인덱스 상태 확인 쿼리 (별도 실행):
-- SELECT indexname, indexdef FROM pg_indexes
-- WHERE tablename = 'regulations';

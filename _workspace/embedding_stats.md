# RAG-002: 임베딩 파이프라인 통계 (P1-5)

**작성일:** 2026-05-28
**담당:** AI/RAG Specialist
**의존성:** DATA-002 완료 (regulations_chunks.jsonl, 127 청크)
**구현물:**
- `rag/src/main/java/com/achul/compliance/rag/UpstageEmbeddingClient.java`
- `rag/src/main/java/com/achul/compliance/rag/EmbeddingPipeline.java`
- `api/src/main/java/com/achul/compliance/api/admin/EmbeddingAdminController.java` (트리거 엔드포인트 추가)

---

## 1. 입력 데이터 요약 (DATA-002 산출 기준)

| 항목 | 값 |
|---|---|
| 입력 파일 | `_workspace/regulations_chunks.jsonl` |
| 총 청크 수 | **127** |
| 입력 파일 크기 | 약 178 KB |
| 청크당 토큰 평균 | 약 380 (cl100k_base 인코딩, DATA-002 기준) |
| 청크당 토큰 분포 | 300~500 토큰 (목표 범위 내) |

### 법령별 청크 수 (DATA-002 산출)

| 법령/문서 | 청크 수 | 비고 |
|---|---:|---|
| 식품등의 부당한 표시 또는 광고의 내용 기준 (식약처 고시) | mfds_notice 파서 | 본문 청크 다수 |
| 건강기능식품 인체적용시험 표시·광고 가이드라인 | mfds_guideline | 섹션 기반 청크 |
| 화장품 표시·광고 관리 지침 | mfds_guideline | 섹션 기반 청크 |
| 건강기능식품 표시·광고 자율심의기구 운영규정 | association_pdf | 조항 단위 |
| 건강기능식품에 관한 법률 (HTML 스텁) | lawgokr_html_stub | 1 (스텁) |
| 식품 등의 표시ㆍ광고에 관한 법률 (HTML 스텁) | lawgokr_html_stub | 1 (스텁) |
| 화장품법 (HTML 스텁) | lawgokr_html_stub | 1 (스텁) |
| 온라인 부당광고 사례집 (OCR 스텁) | ocr_required_stub | 1 (스텁) |
| **합계** | **127** | — |

> 정확한 법령별 카운트는 파이프라인 실행 후 `GET /api/v1/admin/embedding/pipeline/stats` 응답의 `by_law` 필드에서 확인된다.

---

## 2. 임베딩 모델 선택 (TECH-003 / ADR-003)

| 항목 | 값 |
|---|---|
| 모델 | **upstage/solar-embedding-1-large** |
| 임베딩 차원 | **4096** |
| API URL | `https://api.upstage.ai/v1/embeddings` |
| 인코딩 형식 | float (Phase 1 기본) |
| 한국어 성능 | KLUE-STS Spearman ≥ 0.85 (Upstage 공식 벤치마크 기준, ADR-003 §4.2) |
| 비용 단가 | 약 USD 0.10 / 1M tokens (2026-05 기준) |

### 비용 추정 (127 청크 × 평균 380 토큰)

- 총 입력 토큰: 127 × 380 ≈ **48,260 tokens**
- 예상 비용: 48,260 / 1,000,000 × $0.10 ≈ **USD 0.005 (1센트 미만)**

---

## 3. 파이프라인 동작 (구현 완료)

### 3.1 처리 단계

1. **JSONL 로드 (`EmbeddingPipeline#ingestJsonl`)**
   - 라인별 JSON 파싱 (`ChunkRecord` DTO)
   - 빈 텍스트/파싱 실패 라인 스킵 + 경고 로그
   - 동일 `(law_name, article_number, paragraph, item, chunk_text)` 조합 이미 있으면 INSERT 스킵 (멱등)
   - `RegulationEntity` 매핑:
     - `source` ← `"DATA-002:" + parser`
     - `metadata` ← `{chunk_id, source_file, parser, tokens, order, item, notes}` JSON
     - `effectiveDate` ← `last_revised` 우선, 없으면 `enactment_date`
     - `version` ← `last_revised` or `enactment_date`

2. **임베딩 생성 (`EmbeddingPipeline#embedMissing`)**
   - `regulationRepository.findNotEmbeddedRegulations()` 로 미임베딩만 추출
   - 배치 크기 100 (설정: `pipeline.embedding-batch-size`)
   - 배치별 Upstage REST 호출 (`UpstageEmbeddingClient#embedBatch`)
   - 실패 시 지수 백오프 (0.5s → 1s → 2s) × 3회 재시도
   - 응답 차원 검증 (4096 != length 시 IllegalStateException)
   - 성공 배치는 `saveAll()` 로 일괄 UPDATE

3. **샘플 검증 (`EmbeddingPipeline#verifySimilarity`)**
   - 메모리 내 cosine 유사도 brute-force
   - 127 × 4096 → 약 50ms 추정
   - seed 청크 N개에 대해 top-K 유사 청크 반환

### 3.2 멱등성 보장

- **재실행 가능**: 동일 청크 이미 적재되어 있으면 INSERT 스킵
- **부분 실패 복구**: `embedding IS NULL` 조건으로 미임베딩만 재처리
- **배치 단위 격리**: 한 배치 실패해도 다음 배치 진행

### 3.3 에러 처리

| 에러 유형 | 대응 |
|---|---|
| Upstage API 타임아웃 | 지수 백오프 재시도 (최대 3회) |
| 응답 차원 불일치 | IllegalStateException, 배치 실패로 기록 |
| 응답 개수 불일치 | 경고 로그 + 배치 실패 카운트 증가 |
| JSONL 파싱 실패 | 라인 스킵 + 경고 로그 (전체 중단 없음) |
| DB 트랜잭션 실패 | 호출자(@Transactional) 전파, 롤백 |

---

## 4. 실행 절차 (운영자용)

### 사전 준비

```bash
# 1. 환경변수 설정
cp .env.example .env
# .env 파일에서 UPSTAGE_API_KEY 실제 키 입력

# 2. Docker DB 가동 (ParadeDB + pgvector)
docker-compose up -d
docker ps  # compliance-postgres healthy 확인
```

### 파이프라인 실행

```bash
# 3. Spring Boot 기동 (local 프로파일로 DB + Flyway 활성)
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

# 4. 다른 터미널에서 파이프라인 트리거
curl -X POST http://localhost:8080/api/v1/admin/embedding/pipeline/run | jq
```

### 예상 응답 (성공 시)

```json
{
  "jsonl_parsed": 127,
  "db_inserted": 127,
  "db_skipped": 0,
  "embedded": 127,
  "embed_failed": 0,
  "batches": 2,
  "total_in_db": 127,
  "embedded_in_db": 127,
  "elapsed_ms": 8500,
  "vector_dimension": 4096,
  "model_name": "solar-embedding-1-large"
}
```

### 통계 조회

```bash
curl http://localhost:8080/api/v1/admin/embedding/pipeline/stats | jq
```

### 샘플 유사도 검증

```bash
curl "http://localhost:8080/api/v1/admin/embedding/pipeline/verify-similarity?samples=5&topK=5" | jq
```

---

## 5. 검증 체크리스트

| 검증 항목 | 기준 | 확인 방법 |
|---|---|---|
| 모든 127 청크 임베딩 생성 | `embedded == 127` | `/pipeline/stats` 응답 |
| 벡터 차원 4096 | `vector_dimension == 4096` | `/pipeline/run` 응답 |
| 멱등성 | 재실행 시 `db_inserted == 0`, `db_skipped == 127` | 2회 실행 후 비교 |
| 배치 적재 완료 | `total_in_db == embedded_in_db == 127` | DB 직접 조회 가능 |
| 샘플 유사도 검증 | top-1 cosine ≥ 0.7 동일 법령 내 유사 조항 | `/pipeline/verify-similarity` |

DB 직접 조회 (psql):

```sql
SELECT COUNT(*) AS total,
       COUNT(embedding) AS embedded,
       COUNT(*) - COUNT(embedding) AS missing
FROM regulations;

-- 차원 확인 (샘플 1건)
SELECT id, law_name, article_number,
       array_length(embedding::real[], 1) AS dim
FROM regulations
WHERE embedding IS NOT NULL
LIMIT 1;
```

---

## 6. 성능/비용 추정

| 지표 | 추정값 | 근거 |
|---|---|---|
| 총 API 호출 횟수 | 2회 | 127건 / 배치 100 = 2 배치 |
| 총 처리 시간 | < 10초 | Upstage 한국 리전 응답 < 100ms × 2 + DB UPDATE × 127 |
| 총 비용 | < USD 0.01 | 약 48k 토큰 × $0.10/1M |
| DB 저장 용량 | 약 2 MB | 127 × 4096 × 4byte = 2,080,768 bytes |

---

## 7. 한계 및 후속 작업

### Phase 1.5+ 권고

- **벡터 인덱스 (V4 마이그레이션)**: 현재 ParadeDB는 vector(4096) 컬럼만 사용. HNSW는 4000차원 제한(`halfvec_*_ops`) 또는 bit quantization 필요. ADR-003 §6 참조.
- **재임베딩 자동화**: 청크 텍스트 변경 감지 → 자동 재임베딩 (현재는 수동 트리거)
- **다국어 보강**: BGE-m3(1024차원) 백업 모델로 폴백 가능 (Phase 1.5+)

### Phase 2 권고

- **프롬프트 캐싱 / 토큰 최적화**: 입력 텍스트 사전 정규화로 비용 절감
- **임베딩 버전 관리**: 모델 변경 시 `embedding_version` 컬럼 추가, 점진적 마이그레이션

---

## 8. 산출물 위치

| 파일 | 경로 | 역할 |
|---|---|---|
| Upstage 클라이언트 | `rag/src/main/java/com/achul/compliance/rag/UpstageEmbeddingClient.java` | REST 호출 + 응답 검증 |
| 파이프라인 | `rag/src/main/java/com/achul/compliance/rag/EmbeddingPipeline.java` | JSONL → DB → 임베딩 → 통계/검증 |
| Admin 컨트롤러 | `api/src/main/java/com/achul/compliance/api/admin/EmbeddingAdminController.java` | 3개 엔드포인트 추가 (`/pipeline/run`, `/pipeline/stats`, `/pipeline/verify-similarity`) |
| 설정 | `api/src/main/resources/application.yml` | `pipeline.*` 키 추가 |
| 통계 보고서 | `_workspace/embedding_stats.md` | 본 문서 |
| 샘플 유사도 | `_workspace/embedding_sample_validation.md` | 별도 문서 |

---

## 9. 참고

- ADR-003: 임베딩 모델 선정 (`_workspace/ADR-003-embedding-selection.md`)
- DATA-002 산출: `_workspace/regulations_chunks.jsonl`, `_workspace/CHUNKING_REPORT.md`
- 후속 의존: RAG-003 (BM25), RAG-004 (Hybrid + Re-ranking), RAG-005 (검색 API)

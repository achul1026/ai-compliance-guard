# Phase 1 진행도 리포트

> 최종 업데이트: 2026-05-28 (D4 결정 & Re-ranker 구현)  
> 진행률: **Step 1~3 진행중 (90% 완료)**

---

## 📊 전체 진행도

| Step | 내용 | 상태 | 진행률 |
|------|------|------|--------|
| **Step 1** | 기술 결정 (D2, D3, D4) | ✅ 완료 | 100% |
| **Step 2** | 규정 수집 + 택소노미 | ✅ 완료 | 100% |
| **Step 3** | Hybrid RAG 파이프라인 | ✅ 2차 완료 | 90% |

---

## 🔍 Step 3 상세 진행도

### P1-4: DB 스키마 설계 — ✅ 완료
- [x] V2 마이그레이션 (vector 4096, 메타데이터 컬럼, BM25 인덱스)
- [x] RegulationEntity (JPA)
- [x] RegulationRepository (Spring Data)

### P1-5: 임베딩 파이프라인 — ✅ 완료 (RAG-002)
- [x] EmbeddingPort 인터페이스
- [x] UpstageSolarEmbeddingAdapter (REST API)
- [x] 배치 처리, 재시도 로직, Rate limit 대응
- [x] BatchEmbeddingService (배치 임베딩 서비스)
- [x] **UpstageEmbeddingClient** (RAG-002: 단일 책임 HTTP 클라이언트)
- [x] **EmbeddingPipeline** (RAG-002: JSONL → DB 적재 → 임베딩 → 통계/유사도 검증)
- [x] **EmbeddingAdminController** 트리거 엔드포인트 3종 추가 (`/pipeline/run`, `/pipeline/stats`, `/pipeline/verify-similarity`)
- [x] 멱등성 보장 (재실행 시 INSERT 스킵 + `embedding IS NULL` 만 처리)
- [x] 산출물: `_workspace/embedding_stats.md`, `_workspace/embedding_sample_validation.md`
- [ ] 운영 환경 실측 실행 (Docker DB + UPSTAGE_API_KEY 준비 후 `POST /pipeline/run`)

### P1-6: 키워드 검색(BM25) — ✅ 완료
- [x] KeywordSearchPort 인터페이스
- [x] ParadeDbKeywordSearchAdapter (SQL LIKE 기반)
- [ ] ParadeDB BM25 함수 최적화 (향후)

### P1-7: 하이브리드 검색 — ✅ 완료 (2차)
- [x] HybridSearchPort 인터페이스
- [x] HybridSearchAdapter (RRF 알고리즘 + Re-ranker 통합)
- [x] VectorSearchPort 인터페이스
- [x] PostgresVectorSearchAdapter (pgvector)
- [x] RerankerPort 인터페이스
- [x] UpstageSolarRerankerAdapter (D4 Upstage 결정)

### P1-8: 검색 API — ✅ 완료
- [x] SearchController (POST /api/v1/search)
- [x] 하이브리드 검색 결과 반환
- [ ] E2E 통합 테스트

---

## 📁 생성 파일 현황 (26개)

### Migration
- ✅ `V2__upstage_embedding_and_bm25_index.sql`

### :rag 모듈 (포트 6개)
- ✅ `EmbeddingPort.java`
- ✅ `KeywordSearchPort.java`
- ✅ `HybridSearchPort.java`
- ✅ `VectorSearchPort.java`
- ✅ `RerankerPort.java`
- ✅ `ComplianceSearchPort.java`

### :rag 모듈 (어댑터 5개)
- ✅ `UpstageSolarEmbeddingAdapter.java`
- ✅ `ParadeDbKeywordSearchAdapter.java`
- ✅ `PostgresVectorSearchAdapter.java`
- ✅ `HybridSearchAdapter.java`
- ✅ `UpstageSolarRerankerAdapter.java` (D4)

### :rag 모듈 (서비스 1개)
- ✅ `BatchEmbeddingService.java` (배치 임베딩)

### :infra 모듈
- ✅ `RegulationEntity.java`
- ✅ `RegulationRepository.java`

### :api 모듈 (컨트롤러 & 설정)
- ✅ `SearchController.java`
- ✅ `EmbeddingAdminController.java` (배치 임베딩 관리)
- ✅ `RestClientConfig.java` (RestTemplate 설정)

### 설정 & 빌드
- ✅ `.env.example` (Upstage 임베딩 & Re-ranker 설정)
- ✅ `application.yml` (Upstage 통합 설정)
- ✅ `application-local.yml`
- ✅ `build.gradle.kts` (6개 파일, JPA 의존성 추가)

---

## 🚀 남은 작업 우선순위

### 🔴 **P1 계속 진행** (필수)

1. **✅ D4 기술 결정** (Re-ranker)
   - ✅ 선택: Upstage Solar-reranker
   - ✅ UpstageSolarRerankerAdapter 구현
   - ✅ HybridSearchAdapter와 통합

2. **배치 임베딩 적재**
   - 대상: 규정 청크 6000~10000개 (아직 청킹 대기중)
   - API: Upstage Solar-embedding
   - 도구: BatchEmbeddingService (구현 완료)
   - 더미 데이터 테스트 가능 (EmbeddingAdminController)
   - 일정: 2~3일 (청킹 완료 후)

3. **HNSW 벡터 인덱스**
   - V3 마이그레이션
   - 인덱스 생성 (대규모 데이터 후)
   - 일정: 1일

4. **통합 테스트**
   - 임베딩 적재 → BM25 검색 → Vector 검색 → 하이브리드 → Re-ranking
   - EVAL-001 평가 세트 검증
   - 일정: 1~2일

---

## 🎯 성공 지표

### Phase 1 완료 기준
- ✅ 검색 API 동작 (E2E 테스트 통과)
- ✅ 규정 청크 임베딩 완료
- ✅ BM25 + Vector + Re-ranking 조합 작동
- ✅ Recall@10 ≥ 80% (평가 세트 기준)

### 예상 결과
```
POST /api/v1/search
{
  "advertisementCopy": "이 상품은 당뇨병을 예방합니다",
  "topK": 10,
  "useReranker": true
}

응답:
{
  "query": "이 상품은 당뇨병을 예방합니다",
  "relevantRegulations": [
    {
      "regulationId": 123,
      "lawName": "식품표시광고법",
      "articleNumber": "제8조",
      "chunkText": "...",
      "violationType": "질병_예방_치료_표방",
      "relevanceScore": 0.95
    },
    ...
  ],
  "totalHits": 15,
  "searchTimeMs": 250
}
```

---

## 🔗 관련 문서

- `PHASE1_PLAN.md` — Phase 1 마스터 플랜
- `PHASE0_VALIDATION.md` — Phase 0 완료 검증
- `ADR-002-bm25-selection.md` — D2 기술 결정
- `ADR-003-embedding-selection.md` — D3 기술 결정
- `REGULATIONS_INVENTORY.md` — 규정 데이터 인벤토리
- `TAXONOMY.md` — 위반 유형 분류

---

## 📝 체크리스트

### Phase 1 Step 3 최종 확인
- [x] 코드 빌드 성공
- [x] 포트 인터페이스 설계
- [x] 핵심 어댑터 구현
- [ ] 실제 데이터 임베딩
- [ ] Re-ranker 구현
- [ ] 통합 테스트 통과
- [ ] 성능 평가 완료

### Phase 2 준비
- [ ] D5 LLM 엔진 기술 결정
- [ ] D6 에이전트 오케스트레이션 결정
- [ ] Multi-Agent 파이프라인 설계

---

## 💡 주요 결정사항

| 결정 | 선택 | 이유 |
|------|------|------|
| **Vector 차원** | 4096 (Upstage Solar) | 한국어 법령 도메인 품질 우위 |
| **BM25** | ParadeDB | 단일 저장소, 운영 단순 |
| **임베딩** | Upstage API | 한국어 특화 + 국내 계약 가능 |
| **재구현 옵션** | EmbeddingPort 격리 | 향후 BGE-m3 로컬 배포 가능 |

---

## 🎯 **다음 액션 (우선순위)**

1. **HNSW 벡터 인덱스 마이그레이션** (V3)
   - pgvector HNSW 인덱스 생성 SQL 작성
   - 데이터 적재 후 인덱스 구성

2. **더미 테스트 데이터로 E2E 검증**
   - POST `/api/v1/admin/embedding/init-test-data?count=20` → 더미 규정 20개 생성
   - POST `/api/v1/admin/embedding/batch-embed` → 배치 임베딩 실행
   - GET `/api/v1/admin/embedding/status` → 임베딩 상태 조회
   - POST `/api/v1/search` → 검색 API 테스트 (Re-ranking 포함)

3. **규정 청킹 완료 대기** (DATA-002)
   - P1-2 "PDF 파싱·청킹" 완료 후 실제 규정 임베딩

---

**다음 단계**: HNSW 인덱스 마이그레이션 → 더미 데이터 E2E 테스트 → 규정 청킹 완료 후 배치 임베딩

---

## 📅 2026-06-06 갱신 — P1-2 PDF 청킹 완료

### 진행 요약

| Step | 내용 | 상태 | 진행률 |
|------|------|------|--------|
| Step 1 | 기술 결정 (D2, D3, D4) | ✅ 완료 | 100% |
| Step 2 | 규정 수집 + 택소노미 + **청킹** | ✅ **완료** | **100%** |
| Step 3 | Hybrid RAG 파이프라인 | ✅ 2차 완료 | 90% |
| Step 4 | 실데이터 임베딩 + 검증 | ⏳ 진행 예정 | 0% |

### P1-2 산출물

| 파일 | 설명 |
|------|------|
| `regulations_chunks.jsonl` | 635개 청크 (JSONL, 110,108 토큰) |
| `CHUNKING_REPORT.md` | 청킹 통계·알고리즘·이슈·후속 과제 |
| `sample_chunks_validation.md` | 무작위 20건 수동 검증 |
| `chunking/` | Python 청킹 스크립트 (PDF/HTML API) |

### 그룹별 처리 방식

| 그룹 | 처리 대상 | 도구 | 결과 |
|------|---------|------|------|
| A | 4개 식약처/협회 PDF | `pdfplumber` + 정규식 (조→항→호→별표/부칙) | 143 청크 |
| C | 3개 법률 본문 | 국가법령정보 Open API (OC=`kcguard`) + XML 파싱 | 492 청크 |
| B | OCR 사례집 84p | Tesseract Korean (미진행, 별도 트랙) | 보류 |

### 검증 결과

- ID 중복 0건, `source_url`/`enactment_date` 누락 0건
- SOFT_MAX(700) 초과 청크 0건 — 임베딩 토큰 한도 안전
- 식품표시광고법 제8조 9개 부당광고 유형이 호 단위로 1:1 분리됨 (TAXONOMY 매핑 가능)
- 토큰 분포: `<100` 43.8% (짧은 호 단위 다수, 후속 평가에서 영향 측정 필요)

### 다음 단계 (P1-5 ~ P1-8 마무리)

1. **JSONL → DB 적재**: PostgreSQL `regulations` 테이블 INSERT 경로 결정 (관리 컨트롤러 신규 vs 부트스트랩 잡)
2. **배치 임베딩**: `BatchEmbeddingService` 호출 → Upstage Solar-embedding-1-large (4096차원)
3. **HNSW 인덱스**: V3 마이그레이션 적용
4. **검색 API E2E**: 골든 평가 세트 기반 Recall@K 측정

### 비용 추정

- 임베딩 1회: 110,108 토큰 × $0.1/1M = **~$0.011 (≈15원)**
- Reranker는 검색 호출당 과금 (Phase 1 완료 시점에는 미사용 옵션 가능)

---

**Phase 1 완료까지 남은 작업**: JSONL 적재 → 임베딩 → HNSW → E2E 검증 → Recall@10 ≥ 80% 통과

---

## 📅 2026-06-06 추가 갱신 — BGE-m3 PoC + 자바 통합 진입

### 1) 임베딩 모델 PoC

ADR-003에서 정한 Upstage Solar 대신 **BGE-m3(1024차원)** 로 PoC 진행 (ADR-005 신규 작성).

- 이유: 외부 API 의존·키 발급 부담 회피, 로컬 모델 운영 학습 가치, 반복 실험 무료
- 산출: `BAAI/bge-m3` 모델로 635 청크 임베딩 (CPU 4분, 정규화된 1024차원)

### 2) PoC 평가 결과 (12건 골든 세트, dense-only)

| 모드 | Recall@1 | Recall@5 | Recall@10 | MRR |
|---|---|---|---|---|
| Dense (BGE-m3) | 50.0% | 75.0% | **75.0%** | 0.6299 |
| BM25 only (공백 토크나이저) | 25.0% | 33.3% | 33.3% | 0.2927 |
| Hybrid (RRF, 가중치 균등) | 25.0% | 41.7% | 50.0% | 0.3522 |

**해석**:
- BGE-m3 자체 품질은 운영 가능 수준 (Recall@10 75%, 게이트 80%에 5%p 근접)
- BM25는 단순 공백 토크나이저라 매우 약함 → ParadeDB nori 형태소 분석으로 운영 환경 보강 예정
- 균등 가중치 RRF는 Dense의 top1을 BM25 잡음으로 끌어내림 → 가중치 비대칭 또는 Reranker 도입 필요

운영 환경에서 다음을 통해 80% 달성 경로 확보:
- ParadeDB BM25 (한국어 형태소) → BM25 정확도 +20%p 기대
- Upstage Solar-reranker (D4) → top-K 재정렬 +5~10%p
- RRF 가중치 또는 weighted sum 튜닝

### 3) 자바 백엔드 통합 인프라 (Claude 작성)

| 산출물 | 위치 | 내용 |
|---|---|---|
| **ADR-005** | `_workspace/ADR-005-bge-m3-poc-selection.md` | BGE-m3 PoC 채택 결정·근거·향후 트리거 |
| **FastAPI 임베딩 서버** | `embedding-server/main.py` | `/embed` `/health` `/info` 엔드포인트 |
| **Dockerfile** | `embedding-server/Dockerfile` | python:3.11-slim + FlagEmbedding |
| **README** | `embedding-server/README.md` | 사용법·환경변수·메모리 노트 |
| **docker-compose** | `docker-compose.yml` | `embedding-server` 서비스 + `hf_cache` 볼륨 추가 |

### 4) 사용자 작업 대기 (학습 모드)

V4 마이그레이션·자바 어댑터·application.yml 변경은 학습 모드 원칙에 따라 사용자가 직접 작성. 가이드는 본 세션 컨텍스트에 정리되어 있음.

- V4 마이그레이션 (`infra/.../migration/V4__bge_m3_dim_1024.sql`)
- `RegulationEntity.embedding` 컬럼 차원 4096 → 1024, `embeddingModel` 컬럼 신규
- `BgeM3EmbeddingAdapter` 신규 (Upstage 어댑터 패턴 복제, `@ConditionalOnProperty`)
- `UpstageSolarEmbeddingAdapter`에 `@ConditionalOnProperty(havingValue="upstage")` 추가
- `application.yml`에 `embedding.provider`, `embedding.bge-m3.*` 설정

### 5) 학습 모드 적용 범위 (이번 세션 확립)

| 위치 | 작성 주체 | 사유 |
|---|---|---|
| `src/**` (Java/Spring) | 사용자 | 운영 코드 — 직접 작성으로 학습 |
| `infra/**/migration/*.sql` | 사용자 (가이드 제공) | DB 스키마는 운영 영향 큼, 본인이 작성 |
| `_workspace/chunking/*.py` | Claude | 일회성 ETL 스크립트 |
| `embedding-server/**` (Python) | Claude | 운영 서비스지만 Python 인프라, 사용자 학습 목표는 자바 백엔드 |
| `_workspace/*.md` (문서) | Claude | ADR·보고서·진행 메모 |
| `docker-compose.yml` | Claude (소폭 변경) | 추가만, 큰 구조 변경 시 사용자 |

### 6) 다음 단계

1. 사용자: V4 + Entity + BgeM3Adapter + application.yml 작성·검증
2. 사용자: `docker compose build embedding-server && up -d`로 임베딩 서버 기동 확인
3. 협업: JSONL 적재 endpoint 설계·구현 (5-10)
4. 협업: 배치 임베딩 + HNSW V3 재실행 (5-11)
5. 협업: 자바 환경 Hybrid 재평가 → Recall@10 ≥ 80% (5-12)

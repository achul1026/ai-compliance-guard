# Phase 1 마스터 플랜 — ai-compliance-guard

> 작성일: 2026-05-28
> 오케스트레이터: phase1-orchestrator
> 팀원: 5명 (Architect, Backend Specialist, AI/RAG Specialist, Database/Infra Engineer, QA/Validator)
> Data Engineer 추가 예정 (Step 2부터)

---

## 1. Phase 1 목표

규정 데이터 구축 + Hybrid RAG 검색 API 완성.
"이 광고 문구가 위반되는가?"라는 질의에 정확한 근거 조항을 찾아오는 검색 API 완성.

---

## 2. Phase 1 구성: 3 Step

### **Step 1: 기술 결정** ✅ 완료 (2026-05-26)

D2, D3, D4 기술 결정 확정

| 결정 | 선택 | ADR |
|------|------|-----|
| D2: BM25 검색 | ParadeDB (Tantivy) | ADR-002 |
| D3: 임베딩 모델 | Upstage Solar (4096차원) | ADR-003 |
| D4: Re-ranking | TBD (Cohere/Upstage/Cross-encoder) | - |

### **Step 2: 규정 데이터 수집** ✅ 완료 (2026-05-27)

- 규정 인벤토리 (REGULATIONS_INVENTORY.md): 6개 핵심 문서
- 위반 유형 택소노미 (TAXONOMY.md): 9가지 분류

### **Step 3: Hybrid RAG 파이프라인** ✅ 1차 완료 (2026-05-28)

#### P1-4: DB 스키마 설계 ✅
- V2 마이그레이션: vector(4096), 메타데이터 컬럼, BM25 인덱스
- RegulationEntity + RegulationRepository

#### P1-5: 임베딩 파이프라인 ✅
- EmbeddingPort + UpstageSolarEmbeddingAdapter
- 배치 처리, 재시도 로직, Rate limit 대응

#### P1-6: 키워드 검색(BM25) ✅
- KeywordSearchPort + ParadeDbKeywordSearchAdapter
- SQL LIKE 기반 (Phase 2에서 BM25 함수 최적화)

#### P1-7: 하이브리드 검색 + Re-ranking ✅
- HybridSearchPort + HybridSearchAdapter (RRF)
- VectorSearchPort + PostgresVectorSearchAdapter
- RerankerPort (인터페이스만, D4 결정 후 구현)

#### P1-8: 검색 API ✅
- SearchController (POST /api/v1/search)
- 하이브리드 검색 결과 반환

---

## 3. 작업 분담표 (RACI)

| ID | 작업 | 담당 | 의존성 | 일정 | 상태 |
|----|------|------|--------|------|------|
| TECH-002 | D2/D3/D4 기술 결정 | Architect | Step 1 결정 | 1일 | ✅ |
| DATA-001 | 규정 수집 | Data Engineer | 없음 | 1일 | ✅ |
| DATA-002 | PDF 파싱·청킹 | Data Engineer | DATA-001 | 2일 | ⏳ |
| DATA-003 | 위반 유형 택소노미 | Data Engineer | DATA-001 | 1일 | ✅ |
| RAG-001 | DB 스키마 설계 | DB Engineer | TECH-002 | 1일 | ✅ |
| RAG-002 | 임베딩 파이프라인 | AI/RAG Specialist | RAG-001 | 2일 | ✅ |
| RAG-003 | 키워드 검색(BM25) | AI/RAG Specialist | RAG-001 | 1일 | ✅ |
| RAG-004 | 하이브리드 + Re-rank | AI/RAG Specialist | RAG-002, RAG-003 | 2일 | ✅ 1차 |
| API-001 | 검색 API | Backend Specialist | RAG-004 | 1일 | ✅ |
| QA-001 | Phase 1 검증 | QA/Validator | 모두 | 1일 | ⏳ |

---

## 4. 현재 진행 상황

### 완료 (2026-05-28)
- ✅ Step 1: 기술 결정 (D2, D3, D4)
- ✅ Step 2: 규정 수집 + 택소노미
- ✅ Step 3: Hybrid RAG 파이프라인 (1차)

### 남은 작업 (Phase 1 계속)
- ⏳ **D4: Re-ranker 구현** (기술 선택 필요)
- ⏳ **배치 임베딩 적재** (6000~10000 청크)
- ⏳ **HNSW 벡터 인덱스** (V3 마이그레이션)
- ⏳ **통합 테스트** (E2E)
- ⏳ **파이프라인 최적화**

---

## 5. 기술 스택 확정

| 레이어 | 기술 | 상태 |
|--------|------|------|
| **Backend** | Spring Boot 3.3.4 + Gradle 멀티모듈 | ✅ |
| **DB** | PostgreSQL + pgvector (4096) | ✅ |
| **BM25** | ParadeDB (Tantivy) | ✅ |
| **Embedding** | Upstage Solar-embedding (REST API) | ✅ |
| **Vector Search** | pgvector + HNSW | ✅ |
| **Re-ranking** | TBD (D4 결정 필요) | ⏳ |
| **LLM** | Phase 2 결정 (D5) | ⏳ |
| **Agent Orchestration** | Phase 2 결정 (D6, LangChain4j 잠정) | ⏳ |

---

## 6. 산출물 목록

### Phase 1 완성 시 제공
- 📂 규정 데이터: 6000~10000 청크 (임베딩 포함)
- 🔍 검색 API: `POST /api/v1/search` (하이브리드)
- 📊 성능 평가: Recall@K, MRR (EVAL-001)
- 🐳 Docker 환경: ParadeDB + PostgreSQL + Spring Boot

### Phase 1 진행 중 산출
- ✅ V2 마이그레이션 (vector 4096, BM25 인덱스)
- ✅ RegulationEntity + Repository
- ✅ 6개 포트 인터페이스 (EmbeddingPort, KeywordSearchPort 등)
- ✅ 4개 어댑터 구현체
- ✅ SearchController

---

## 7. 예상 일정 (7~10일)

| Day | 작업 | 담당 | 상태 |
|-----|------|------|------|
| 1 (5/28) | Step 1 기술 결정 + Step 2 수집 | All | ✅ |
| 2~3 (5/29~30) | Step 3 Hybrid RAG (P1-4~8) | RAG Specialist + Backend | ✅ 1차 |
| 4~5 (5/31~6/1) | Re-ranker (D4) + 배치 임베딩 | RAG Specialist | ⏳ |
| 6 (6/2) | HNSW 인덱스 + 최적화 | DB Engineer | ⏳ |
| 7 (6/3) | 통합 테스트 + 평가 | QA/Validator | ⏳ |
| 8~9 (6/4~5) | 보완 및 버그 수정 | All | ⏳ |

---

## 8. 변경 이력

| 날짜 | 변경 내용 | 대상 |
|------|----------|------|
| 2026-05-28 | Phase 1 마스터 플랜 작성 | 전체 |
| 2026-05-28 | Step 3 Hybrid RAG 1차 완료 | P1-4~8 |

---

## 9. 다음 액션

1. **D4 기술 결정**: Re-ranker 선택 (Cohere vs Upstage vs Cross-encoder)
2. **배치 임베딩**: 규정 청크 6000~10000개 Upstage API로 임베딩
3. **Re-ranker 구현**: D4 선택 후 RerankerPort 구현체
4. **HNSW 인덱스**: V3 마이그레이션 + 벡터 인덱스 생성
5. **통합 테스트**: 검색 API E2E 테스트

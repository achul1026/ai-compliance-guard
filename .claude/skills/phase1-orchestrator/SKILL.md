---
name: Phase 1 Orchestrator
description: ai-compliance-guard Phase 1 전체 워크플로우를 조율한다. 규정 데이터 구축 + Hybrid RAG 검색 API 구현을 6명의 에이전트 팀으로 병렬 진행. 기술 결정(D2/D3/D4) → 규정 수집 → RAG 파이프라인 구현 → 검증까지. "Phase 1 시작", "Hybrid RAG 구축", "규정 검색 API" 요청에서 반드시 사용할 것.
type: general-purpose
model: opus
---

## 핵심 역할

ai-compliance-guard의 Phase 1(규정 데이터 구축 + Hybrid RAG)을 완성한다. 6명의 전문가 팀(Architect, Backend Specialist, Database/Infra Engineer, AI/RAG Specialist, Data Engineer, QA/Validator)을 구성하여 병렬 진행하고, 최종 검색 API(`POST /api/search`)가 정확한 근거 조항을 반환할 때까지 반복한다.

## 실행 모드

**에이전트 팀** — 6명이 협업하며 자체 조율. TaskCreate로 작업 할당, SendMessage로 의존성 해결.

---

## Phase 1 목표

> "이 광고 문구가 위반되는가?"라는 질의에 정확한 근거 조항을 찾아오는 검색 API를 완성한다.

---

## 실행 계획

### Step 1: 기술 결정 (2~3일, 병렬)

**Architect 주도:**
- **D2 (한국어 BM25)**: ParadeDB vs OpenSearch+nori vs Lucene 비교
  - 성능: 조항 번호 + 고유명사 매칭 정확도
  - 비용: 인프라 복잡도 및 운영 비용
  - 결정: ADR-002 문서화
  
- **D3 (임베딩 모델)**: OpenAI text-embedding-3 vs Upstage solar-embedding vs BGE-m3
  - 평가: 한국어 법률 문서 특화 성능
  - 비용: API 호출 비용 vs 로컬 임베딩 비용
  - 결정: ADR-003 문서화
  
- **D4 (Re-ranking)**: Cohere Rerank vs Upstage vs cross-encoder
  - 사전 조건: D3 완료 후 (임베딩 결과의 후처리)
  - 결정: ADR-004 문서화

**TaskCreate:**
```
TECH-002: D2 BM25 기술 결정 ADR 작성 (Architect)
TECH-003: D3 임베딩 모델 기술 결정 ADR 작성 (Architect)
TECH-004: D4 Re-ranking 기술 결정 ADR 작성 (Architect, D3 완료 후)
```

### Step 2: 규정 데이터 구축 (4~6일, Step 1과 병렬)

**Data Engineer 주도:**

**TaskCreate:**
```
DATA-001: 규정 인벤토리 작성 (P1-1)
  - 1차 6개 문서 수집
  - 출처: 국가법령정보센터, 식약처, 협회
  - 결과: _workspace/REGULATIONS_INVENTORY.md
  - 검증: 인벤토리 완성도 확인

DATA-002: PDF 파싱 및 청킹 (P1-2)
  - 도구: Apache Tika, PDFBox, pdfplumber 중 선택
  - 청킹 단위: 300~500 토큰 (조항 경계 보존)
  - 메타데이터 부착: 법령명, 조항번호, 시행일
  - 결과: regulations_chunks.jsonl
  - 검증: 샘플 20건 수동 검토

DATA-003: 위반 유형 택소노미 (P1-3)
  - 협력: AI/RAG Specialist
  - 정의: 질병 예방·치료 표방, 의약품 오인, 거짓·과장 등
  - 결과: _workspace/TAXONOMY.md
  - 검증: 각 유형별 근거 조항 맵핑 확인
```

### Step 3: Hybrid RAG 파이프라인 (3~5일, Step 1 완료 후)

**AI/RAG Specialist + Backend Specialist + Database/Infra Engineer 협력:**

**TaskCreate:**
```
RAG-001: DB 스키마 최적화 (P1-4, Database/Infra Engineer)
  - regulations 테이블 재검토
  - vector(1536) → vector(D3 임베딩 차원) 조정 (D3 결정 기반)
  - HNSW 인덱스 설정 (pgvector HNSW)
  - BM25 인덱스 설정 (D2 선택 기반)
  - 검증: 마이그레이션 실행 성공

RAG-002: 임베딩 파이프라인 (P1-5, AI/RAG Specialist)
  - 청크 → 임베딩 배치 작업
  - 모델: D3 결정에 기반
  - 결과: regulations 테이블에 embedding 컬럼 적재
  - 검증: 전체 청크 임베딩 확인, 샘플 유사도 확인

RAG-003: 키워드 검색 (BM25) (P1-6, Database/Infra Engineer + AI/RAG Specialist)
  - 구현: D2 선택 기반 (ParadeDB/OpenSearch/Lucene)
  - 인덱스: 조항 번호, 법령명, 내용
  - 테스트: "식품표시광고법 제8조" 같은 정확한 질의 통과
  - 검증: 5개 테스트 케이스 통과

RAG-004: 하이브리드 결합 + Re-ranking (P1-7, AI/RAG Specialist)
  - 벡터 검색 + 키워드 검색 병합
  - 가중치: 벡터(0.6) + 키워드(0.4) (초기값, 평가로 조정)
  - Re-ranking: D4 선택 기반
  - 결과: 관련도 상위 5~10개 조항 반환
  - 검증: 샘플 질의 Top-5 확인

RAG-005: 검색 API 완성 (P1-8, Backend Specialist)
  - Endpoint: POST /api/v1/search
  - Request: { "query": "광고 문구", "top_k": 10 }
  - Response: [ { "law": "...", "article": "...", "text": "...", "relevance_score": 0.95 } ]
  - 검증: 엔드투엔드 통합 테스트 통과
```

### Step 4: 검증 및 평가 (2일)

**QA/Validator 주도:**

**TaskCreate:**
```
EVAL-001: 골든 평가 세트 작성
  - 위반 샘플 20~30건 (여러 위반 유형 포함)
  - 정답 라벨: 근거 조항 (1~3개)
  - 결과: _workspace/golden_eval_set.jsonl
  - 검증: 팀 리뷰 완료

EVAL-002: Recall@K 측정
  - 평가: 각 샘플마다 정답 조항이 상위 K개(K=5,10) 안에 포함되는 비율
  - 목표: Recall@5 ≥ 80%, Recall@10 ≥ 90%
  - 결과: _workspace/PHASE1_EVALUATION.md
  - 검증: 목표 달성 또는 미달성 원인 분석
```

---

## 병렬 진행 타이밍

```
Day 1-3:  [Step 1] D2/D3 병렬                    [Step 2] DATA-001 병렬 시작
          ├─ D2 ADR 완료 (2일)
          ├─ D3 ADR 완료 (2일)
          └─ D4는 Day 3부터 시작 (D3 결과 기반)

Day 4-6:  [Step 3] RAG 구현 (D4 완료 후)         [Step 2] DATA-002~003 진행
          ├─ RAG-001 DB 스키마 (D3 임베딩 차원)
          ├─ RAG-002 임베딩 파이프라인
          ├─ RAG-003 BM25 구현 (D2 기반)
          └─ RAG-004~005 통합

Day 7-8:  [Step 4] 검증
          ├─ 골든 평가 세트 작성
          ├─ Recall@K 측정
          └─ 최종 보고

임계 경로: D2/D3(2~3) → RAG(3~5) → 검증(2) = 7~10일
```

---

## 데이터 전달 프로토콜

| 대상 | 방식 | 예시 |
|------|------|------|
| 팀원 간 | TaskCreate/SendMessage | "D3 임베딩 결과 나왔으니 RAG-001부터 시작해" |
| 파일 산출물 | 파일 기반 (_workspace/) | REGULATIONS_INVENTORY.md, regulations_chunks.jsonl |
| DB 데이터 | 데이터베이스 | regulations 테이블에 청크 + 임베딩 적재 |

---

## 에러 핸들링

| 문제 | 대응 |
|------|------|
| PDF 파싱 실패 | 수동 OCR 또는 다른 형식 사용, 조항 수 감소 |
| 임베딩 비용 초과 | 배치 크기 조정, 저가 모델 평가 |
| Recall@K 미달 | 가중치(벡터/키워드) 조정, Re-ranking 파라미터 튜닝 |
| API 응답 시간 초과 | 인덱스 최적화, 캐싱 추가 |

---

## 산출물 체크리스트

- [ ] ADR-002, ADR-003, ADR-004 (D2/D3/D4 결정 문서)
- [ ] _workspace/REGULATIONS_INVENTORY.md (1차 6개 문서)
- [ ] regulations_chunks.jsonl (청크 데이터)
- [ ] _workspace/TAXONOMY.md (위반 유형 분류)
- [ ] V2__regulations_optimized.sql (스키마 마이그레이션)
- [ ] regulations 테이블 (청크 + 임베딩 적재 완료)
- [ ] POST /api/v1/search 엔드포인트
- [ ] _workspace/golden_eval_set.jsonl (평가 세트)
- [ ] _workspace/PHASE1_EVALUATION.md (Recall@K 결과)

---

## 테스트 시나리오

### Happy Path
```
1. 질의: "건강기능식품에서 질병 예방 효능을 표방하면 위반되나?"
2. 시스템: BM25 검색 + 벡터 검색 + Re-ranking
3. 결과: 
   - 건강기능식품법 제12조 (상위 1)
   - 식품표시광고법 제10조 (상위 2)
   - Recall@5: 100% (2/2 정답 포함)
```

### 엣지 케이스
```
1. 모호한 질의: "좋은 제품인가?"
   → 관련도 낮음 (Recall@5 = 0%, 예상)
   
2. 법적 용어 혼합: "이게 의약품처럼 쓰면 안 되나?"
   → 의약품 오인 관련 조항 반환 (Recall@5 = 100% 목표)
```

---

## 다음 액션

Phase 1 Orchestrator 트리거 후:
1. TeamCreate → 6명 팀 구성
2. TaskCreate → Step 1~4 작업 할당
3. 병렬 진행 모니터링
4. 최종 검증 및 Phase 2 진입 준비

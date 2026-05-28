# Phase 1.5 기술 분석: Hybrid RAG 평가 결과와 개선 계획

## 0. 핵심 요약

| 항목 | 값 |
|------|-----|
| 평가 날짜 | 2026-05-29 |
| 평가 대상 | EVAL-002 (25개 샘플, Recall@K 측정) |
| 평가 결과 | Recall@5 = 20%, Recall@10 = 20% |
| 목표 | Recall@5 ≥ 80%, Recall@10 ≥ 90% |
| **판정** | ❌ **미달** |
| 검색 모드 | BM25 단독 (ParadeDB pg_search) |
| 미작동 모듈 | 벡터 검색, Re-ranker |

---

## 1. 근본 원인 분석

### 1.1 즉시 원인: 임베딩 0건

**DB 상태:**
```sql
SELECT law_name, COUNT(*) as total, COUNT(embedding) as embedded 
FROM regulations 
GROUP BY law_name 
ORDER BY total DESC;

결과:
- 건강기능식품 인체적용시험 가이드라인: 55개 (embedded: 0)
- 식품등의 부당한 표시 또는 광고의 내용 기준: 34개 (embedded: 0)
- 화장품 표시·광고 관리 지침: 27개 (embedded: 0)
- ... 전체: 127개 (embedded: 0)
```

**원인 체인:**
```
UPSTAGE_API_KEY 미설정
  ↓
EmbeddingAdminController.runPipeline() 호출 안 함
  ↓
EmbeddingPipeline.run() 미실행
  ↓
UpstageEmbeddingClient 호출 안 함
  ↓
regulations 테이블의 embedding 컬럼 = NULL (전체 127개)
  ↓
PostgresVectorSearchAdapter.search() → 0건
  ↓
HybridSearchService → BM25 결과만으로 동작
  ↓
UpstageSolarRerankerAdapter 미작동
```

**영향:**
- 벡터 검색 기여도 0%
- Re-ranker 순위 조정 불가
- BM25 단독 검색만으로 Recall@5 = 20% 달성

### 1.2 근본 원인: 법령 3종 본문 미적재

**문제 문서:**
1. 건강기능식품에 관한 법률 (건강기능식품법)
2. 식품 등의 표시·광고에 관한 법률 (식품표시광고법)
3. 화장품법

**저장 상태:**
```json
{
  "id": "건강기능식품법_001_STUB",
  "law_name": "건강기능식품에 관한 법률",
  "article_number": null,  // ← N/A
  "text": "[스텁] 본문이 포함되지 않은 ...",
  "source_file": "regulations/건강기능식품법_law.go.kr_20260526.html",
  "tokens": 280
}
```

**근본 기술 원인:**
```
law.go.kr 웹페이지
  ↓
HTML 정적 저장 (pdfplumber, BeautifulSoup)
  ↓
<script> 태그만 수집됨
  ↓
JavaScript 비동기 로드 (window.onload, XMLHttpRequest, Fetch API)
  ↓
실제 본문은 JS 실행 후 DOM에 렌더링
  ↓
정적 HTML에는 본문 내용 없음
  ↓
스텁 청크만 DB에 적재됨
  ↓
조항 단위 정확도 검색 불가 (article_number = N/A)
```

**영향:**
```
골든 정답 조항 17개 중:
- 건강기능식품법 조항: 0개 (DB 미수록)
- 식품표시광고법 조항: 0개 (DB 미수록)
- 화장품법 조항: 0개 (DB 미수록)
- 합계: strict match 불가능 (17/17 = 100% 손실)

부분적 매칭 (법령명 일치만):
- 부당표시광고 기준에 인용된 법령명: 부분 매칭 가능
- 화장품 지침에 인용된 조항: 부분 매칭 가능
```

---

## 2. 위반 유형별 상세 분석

### 2.1 성과: 화장품 효능 광고 위반 (Recall@5 = 60%)

**정답 조항:**
- 화장품법 제13조, 제14조 (DB 미수록 - 법령 미적재)
- 화장품 표시·광고 관리 지침 (DB 적재됨 - 27개 청크)

**BM25 매칭 성공 사례:**
```
쿼리: "아토피 완화 화장품 광고"
  ↓
BM25 검색 결과:
  1. "화장품 표시·광고 관리 지침 제2장 - 아토피, 건선, 습진 표현 금지"
  2. "화장품법 제14조 기능성화장품 실증자료 제출 의무"
  3. "화장품 표시·광고 관리 지침 - 천연/유기농 오인 금지"
  ↓
Hit! → Recall@5 = 60%
```

**학습 포인트:**
- 구체 어휘 (아토피, 건선, 습진)는 BM25가 강함
- 가이드라인이 조항보다 설명이 상세하여 키워드 매칭 유리

### 2.2 실패: 질병 예방·치료 표방 (Recall@5 = 0%)

**정답 조항:**
- 식품표시광고법 제8조 (DB 미수록)
- 건강기능식품법 제18조 (DB 미수록)
- 부당표시광고 기준 제2조 1호 (DB 적재됨)

**BM25 매칭 실패 원인:**
```
쿼리: "건강기능식품에서 당뇨병 예방 표현"
  ↓
사용자 어휘: "당뇨병", "예방", "혈당 관리"
  ↓
법령 어휘: "질병의 예방·치료를 목적으로 인식할 우려"
  ↓
어휘 격차: 
  - 사용자: 구체적 질병명 (당뇨병, 고혈압, 암)
  - 법령: 추상적 원리 (질병의 예방·치료)
  ↓
키워드 매칭 실패 (Levenshtein 거리 > threshold)
  ↓
0건 반환
```

**해결 필요:**
- 동의어 사전 (당뇨병 → "질병", "대사질환", "혈당 조절")
- 형태소 분석기 강화 (ParadeDB의 약점)
- 벡터 검색으로 의미적 유사도 계산 (현재 미작동)

### 2.3 부분 성공: 객관적 근거 부재 (Recall@5 = 40%)

**정답 조항:**
- 건강기능식품법 (DB 미수록)
- 화장품 표시·광고 관리 지침 (DB 적재됨 - 27개 중 2개 매칭)

**BM25 매칭 성공 사례:**
```
쿼리: "화장품에 유효성 입증 없이 효능 표시"
  ↓
BM25:
  1. "화장품 표시·광고 관리 지침 제3장 - 실증자료 없이 의료용어 사용 금지"
  2. "화장품법 제14조 실증자료 제출 의무"
  ↓
Hit! → Recall@5 = 40% (부분)
```

---

## 3. 기술 설계: Hybrid RAG의 역할 분담

### 3.1 현재 구현된 아키텍처

```
검색 요청 (쿼리)
  ↓
┌─────────────────────────────────────┐
│     SearchController                │
│  POST /api/v1/search                │
└────────────────┬────────────────────┘
                 ↓
         임베딩 생성 (쿼리)
         UpstageEmbeddingClient
             4096 차원
                 ↓
    ┌──────────────────────────┐
    │  하이브리드 검색         │
    │  HybridSearchService     │
    └────┬──────────────┬──────┘
         ↓              ↓
    벡터 검색        BM25 검색
    (미작동)        (작동 중)
         ↓              ↓
    상위 20개       상위 20개
         ↓              ↓
    ┌────────────────────────┐
    │  점수 정규화 + 결합   │
    │  score = vec*0.6 +   │
    │         bm25*0.4      │
    └────────────┬──────────┘
                 ↓
         상위 20개 결합
                 ↓
    ┌──────────────────────────┐
    │  Re-ranking              │
    │  UpstageSolarReranker    │
    │  (미작동)                │
    └────────────┬─────────────┘
                 ↓
         상위 10개 최종 반환
```

### 3.2 각 모듈의 설계 의도

| 모듈 | 입력 | 처리 | 출력 | 현재 상태 |
|------|------|------|------|---------|
| **벡터 검색** | 쿼리 임베딩 (4096) | cosine 유사도 | 상위 20개 | ❌ 임베딩 0건 |
| **BM25 검색** | 쿼리 문자열 | 용어 가중치 (tf-idf) | 상위 20개 | ✅ 작동 중 |
| **점수 결합** | 두 검색 결과 | 0.6:0.4 가중치 | 상위 20개 | ✅ 작동 (벡터 입력 없음) |
| **Re-ranker** | 상위 20개 | 의미적 관련도 | 상위 10개 | ❌ 미작동 |

### 3.3 가중치 설계 근거 (0.6 벡터 : 0.4 키워드)

```
선택 이유:
1. 벡터 (0.6):
   - 의미적 유사도에 우선권 부여
   - "의약품 오인", "질병 예방 표현" 같은 추상 개념 포착
   - 형태소/동의어 미보강 보완

2. 키워드 (0.4):
   - 법령명, 조항 번호 정확 매칭
   - 조항별 메타데이터 중요도 (article_number, section, item)
   - 빠른 응답 (BM25는 O(log N))

3. Phase 1.5+ 재조정:
   - 법령 3종 본문 추가 후 BM25 성능 향상
   - 동의어/형태소 개선 후 재실험
   - 현재 0.6:0.4는 초기값, 평가 기반 조정 예정
```

---

## 4. 데이터베이스 정합성 검증

### 4.1 regulations 테이블 스키마 (V2+V3 마이그레이션)

```sql
-- V2: vector 차원 및 BM25 인덱스
CREATE TABLE regulations (
    id BIGINT PRIMARY KEY,
    source VARCHAR(255),
    law_name VARCHAR(255),
    article_number VARCHAR(50),
    chunk_text TEXT,
    embedding vector(4096),  -- ← V1에서 vector(1536) 변경
    metadata JSONB,
    paragraph_number VARCHAR(50),
    item_number VARCHAR(50),
    effective_date DATE,
    version VARCHAR(50),
    violation_type VARCHAR(100),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- ParadeDB BM25 인덱스 (V2)
CREATE INDEX idx_regulations_bm25_chunk 
ON regulations 
USING bm25 (id, chunk_text) 
WITH (key_field=id);

-- V3: HNSW 미적용 (차원 제약)
-- pgvector HNSW 지원 한계: ≤2000 차원
-- Upstage 4096 > 한계 → Phase 1은 시퀀셜 스캔 사용
-- Phase 1.5+ bit quantization 예정
```

### 4.2 데이터 정합성 확인

```
적재 상태:
├── 스텁 청크 (법령 3종): 3개
│   ├── 건강기능식품법: article_number=NULL, text=[STUB]
│   ├── 식품표시광고법: article_number=NULL, text=[STUB]
│   └── 화장품법: article_number=NULL, text=[STUB]
│
├── 완전 청크 (가이드라인/고시): 122개
│   ├── 부당광고 기준: 34개 (33개 article_number 포함)
│   ├── 화장품 지침: 27개 (0개 article_number - 문서 특성)
│   ├── 인체적용시험 가이드라인: 55개 (0개 article_number)
│   └── 협회 운영규정: 7개 (7개 article_number)
│
└── 임베딩 상태: 0건 (전체 NULL)

메타데이터 누락:
- article_number = NULL인 청크: 58개 (45.7%)
- violation_type 누락: ~20개 (미입력)
```

---

## 5. Phase 1.5 실행 로드맵

### 5.1 즉시 실행 (Step 1)

**작업:**
1. `.env` 파일 생성 (UPSTAGE_API_KEY 포함)
2. docker-compose 환경 변수 주입
3. `POST /api/v1/admin/embedding/pipeline/run` 호출

**예상 결과:**
```
응답 시간: 2-3분 (127개 청크 × 100~500 배치)
임베디드 건수: 127개 (100%)
저장 공간: ~2MB (4096-dim float32 × 127)
비용: ~$0.05 (Upstage API 기준)

검증:
- GET /api/v1/admin/embedding/status → embedded: 127
- GET /api/v1/admin/embedding/pipeline/verify-similarity → 샘플 5개 코사인 유사도 확인
```

### 5.2 병렬 실행 (Step 2)

**작업:**
1. law.go.kr 법령 3종 본문 추출
   - 옵션 A: PDF 다운로드 (국가법령정보센터)
   - 옵션 B: elaw Open API 사용
   - 옵션 C: Selenium으로 JavaScript 렌더링 후 추출

2. 조항 단위 청킹 (300~500토큰)

3. regulations_chunks.jsonl 업데이트

**예상 결과:**
```
추가 청크: 100~150개 (법령 3종 × 30~50 조항)
전체 청크: 227~277개

DB 적재:
- INSERT regulations (새 청크)
- UPDATE regulations (스텁 대체)
```

### 5.3 최종 실행 (Step 3)

**작업:**
1. 전체 청크 재임베딩 (227~277개)
2. EVAL-002 재실행 (Hybrid + Re-ranker 포함)

**예상 결과:**
```
Recall@5 목표: ≥ 80% (현재 20%)
Recall@10 목표: ≥ 90% (현재 20%)
평균 응답 시간: < 500ms

개선 기대 효과:
- 벡터 검색 추가: +30~40% (의미적 유사도)
- Re-ranker 추가: +10~20% (최종 순위 조정)
- 법령 3종 본문: +20~30% (조항 정확도)
- 합계 기대: 20% → 60~90% (목표 달성 가능)
```

---

## 6. 학습 포인트 및 개선안

### 6.1 Hybrid RAG의 보완 구조

```
문제: 단일 검색 모드의 한계
- 벡터만: 이상치에 약함, 차원의 저주, 임베딩 모델 성능 의존
- BM25만: 어휘 격차, 형태소 분석 한계, 의미 누락

해결: Hybrid = 벡터 + BM25
- 벡터: 의미적 유사도 (concept, entity, relation)
- BM25: 용어 정확도 (exact match, term frequency)
- Re-ranker: 의미적 관련도로 최종 순위 조정

Phase 1.5+ 개선안:
1. 동의어 사전 보강
   - "당뇨병" → ["질병", "대사질환", "혈당"]
   - BM25 쿼리 확장 (query expansion)

2. 형태소 분석기 강화
   - ParadeDB + 한국어 NLP (Kiwi, MeCab)
   - 또는 다국어 임베딩 모델 (BGE-m3, mxbai)

3. Re-ranker 파라미터 튜닝
   - 상위 K 개만 Re-rank (현재 20개 → 10개로 축소)
   - Re-ranker 모델 변경 (cross-encoder vs encoder-based)
```

### 6.2 벡터 DB 설계 교훈

**발견: pgvector HNSW의 차원 제약**
```
pgvector 버전별 HNSW 최대 차원:
- v0.5: 2000차원
- v0.6: 4000차원 (최근)
- v0.8: 4000차원 (현재 설치)

Upstage embedding: 4096차원
→ 한 단계 초과 (Phase 1은 시퀀셜 스캔)

Phase 1.5+ 솔루션:
1. 낮은 차원 모델로 변경 (OpenAI text-embedding-3-small: 1536)
   - 비용 저렴 ($0.02/1M)
   - 성능 약간 하락 (1~3%)

2. Bit quantization (벡터 압축)
   - 4096 → 256 또는 128차원
   - HNSW 인덱싱 가능
   - 성능 손실 미미 (재평가 필요)

3. 분산 벡터 DB 전환 (Phase 2+)
   - Milvus, Weaviate, Pinecone
   - 자동 인덱싱, 수평 확장
```

### 6.3 임베딩 파이프라인 멱등성 설계

```
현재 구현:
- 조건: WHERE embedding IS NULL
- 효과: 재실행 시 NULL 청크만 처리
- 비용 절감: 중복 API 호출 제거

개선안 (Phase 1.5+):
1. 임베딩 버전 관리
   - embedding_model: "solar-embedding-1-large"
   - embedding_version: "v1"
   - 모델 변경 시 전체 재임베딩 자동화

2. 체크섬 기반 검증
   - chunk_text의 MD5 저장
   - 텍스트 변경 감지 시 재임베딩

3. 배치 모니터링
   - 배치당 성공/실패 로그
   - 실패 시 지수 백오프 재시도 (현재 구현됨)
```

---

## 7. 다음 단계 (Phase 2)

**기술 결정 대기:**
- D5: LLM 엔진 (GPT-4o vs Upstage Solar)
- D6: 에이전트 오케스트레이션 (LangChain4j vs LangGraph)

**구현 목표:**
- 3-Agent 순환 루프 (Inspector → Critic → Corrector)
- 환각 차단 (Fact Checker via RAG)
- Remediation (대체 문구 제시)


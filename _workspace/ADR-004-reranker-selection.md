# ADR-004: Re-ranking 모델 선택 (D4)

- 상태: **확정 (Accepted)**
- 결정자: architect (팀 리뷰 반영)
- 결정일: 2026-05-26
- Phase: 1
- 관련 결정: ADR-002 (BM25 = ParadeDB), ADR-003 (임베딩 = Upstage solar-embedding-1-large, 4096차원), D5 (LLM 엔진)
- 영향 산출물: `:rag` 모듈 `RerankerPort` 인터페이스, RAG-004 (하이브리드 결합 + Re-ranking) 구현, `.env.example`의 API 키 항목

---

## Context

ADR-002와 ADR-003에 따라 Hybrid 검색은 다음 2-경로 구조로 확정되어 있다.

```
질의 → BM25(ParadeDB) Top-K (예: 50)
      ↘
       → RRF or score-fusion으로 후보군 통합 (예: Top-20~30)
      ↗
질의 → 벡터(Upstage solar-embedding-1-large, 4096) Top-K (예: 50)
                                                                 ↓
                                                          **Re-ranker** ← 본 결정 대상
                                                                 ↓
                                                          Top-5 (LLM 컨텍스트로 전달)
```

Re-ranker는 BM25·벡터 점수의 결합만으로는 정렬되지 않는 의미상 미세 관련성을 잡아 Top-5의 정밀도(Precision@5)를 끌어올린다. 이 Top-5가 Phase 2 멀티에이전트의 컨텍스트 윈도우에 들어가므로, **Re-ranking의 정확도가 사실상 최종 판정 품질의 상한**을 결정한다.

### 한국어 법령·컴플라이언스 도메인의 Re-ranking 요구사항

1. **의미 등가 정밀 매칭**: 사용자 광고 카피의 "혈압을 낮춰드립니다" 같은 표현과, 식품표시광고법 제8조 "질병 예방·치료 효능 표시 광고"가 Top-5 안에 들어와야 한다. 하이브리드 점수만으로는 표면 어휘가 다른 케이스에서 정답 조항이 6~10위로 밀리는 일이 잦다.
2. **유사 조항 간 우선순위**: 제8조 제1항(질병 효능 표방) vs 제8조 제2항(의약품 오인 표시)처럼 의미가 가까운 인접 조항들 중에서도 질의에 가장 정확히 부합하는 한 조를 1위로 끌어올려야 한다. 임베딩 코사인은 이 미세 구분을 종종 놓친다.
3. **조사·어미·문체 변형 무관성**: 광고 카피는 구어체·마케팅 톤, 법령은 문어체·정의문. 두 문체 차이를 의미 수준에서 해소해야 한다.
4. **영문·약어 혼용**: "GMP", "프로바이오틱스" 같은 영문/외래어가 한국어 조항과 매칭되어야 한다.

### Phase 1 운영·사업 요구사항

- **응답 시간 목표 (요구 기준 재확인)**:
  - 검색 API 단일 요청 P95 < 500ms (200 RPS 기준)
  - 이 안에 BM25(<50ms) + 벡터(<100ms) + RRF(<5ms) + Re-rank(?) + 직렬화·네트워크가 모두 들어가야 함
  - Re-rank에 할당 가능한 예산: 약 250~300ms 이내
- **트래픽**: 베타 단계 수십 QPS, 정식 런칭 후 200 RPS 가정. 단, 200 RPS는 캐시 적중분 포함 측정치이므로 실제 Re-rank 호출은 그보다 적다 (대화 컨텍스트 캐시·동일 질의 캐시 후 50~100 RPS 예상).
- **데이터 비유출 (OPERATIONS.md 5번 + STRATEGY.md 핵심 카피)**:
  - 광고 카피·내부 자료는 **외부 API로 전송 시 고객 동의·고지 필수**.
  - "비유출 SKU"는 ADR-003에서 Phase 1.5+ 옵션으로 명시됨 → Re-ranker도 동일 정책을 따라야 한다.
  - 1차 SKU(API 허용 고객)와 2차 SKU(비유출 고객) 모두를 처리할 수 있는 **포트 격리**가 필요.
- **Phase 2 멀티에이전트와의 정합성**: 검사·비판·교정 에이전트가 각자 부분 컨텍스트를 다시 가져올 때 실시간 Re-ranking이 반복 발생한다 → **Re-ranker는 빈번한 호출**을 견뎌야 한다 (호출량은 검색 API 호출량의 2~3배 누적).

### 현재 인프라 제약

- ADR-001 `:rag` 모듈은 Java 17 라이브러리, Spring Boot 멀티모듈 안에서 호출된다.
- 운영 인력은 소수(Phase 1 기준 GPU 인프라 전담 인력 없음).
- ADR-003에서 Upstage가 1차 임베딩 벤더로 확정됨 → 같은 벤더의 Re-ranker 사용 시 SDK·인증·과금·SLA 통합 가능.

---

## 후보 비교

### 옵션 A — Cohere Rerank (API, `rerank-multilingual-v3.0`)

산업 표준급 Re-ranker. 100+ 언어 지원, 한국어 포함.

- **품질**: 공개·사내 벤치마크에서 다국어 Re-ranking 정확도 최상위. 일반적인 한국어 도메인에서도 강력.
- **한국어 법령 도메인 품질**: 한국어 일반 텍스트 품질은 검증된 수준. 단, 법령 좁은 어휘(예: "기능성표시식품" vs "건강기능식품")는 도메인 특화 모델(Upstage) 대비 정량 검증이 부족.
- **API 응답 시간**:
  - 단일 호출, Top-K 후보 20개 기준 P50 100~150ms, P95 200~300ms (해외 리전)
  - 국내 리전 미제공 → 네트워크 RTT 80~120ms 가산 가능성
- **비용 (Phase 1)**:
  - $2 / 1,000 검색 (Top-K 무관, 검색 단위 과금)
  - 일 1만 검색 가정 → 월 30만 검색 × $0.002 = **월 $600**
  - Phase 2 멀티에이전트 호출 누적 시 3배 → 월 $1,800
- **데이터 비유출**: ✕ API 전송 필수. 한국 B2B 컴플라이언스 거부 가능성 있음. zero retention 옵션은 enterprise 계약 필요.
- **로컬 배포**: ✕ 불가능.
- **벤더 일관성**: ✕ 임베딩(Upstage)과 다른 벤더 → SDK·인증·과금 2중 관리.
- **운영 부담**: 거의 0 (REST 호출).
- **Spring Boot 통합**: WebClient REST 호출. 공식 Java SDK 없음 (Python·Node 중심).

### 옵션 B — Upstage Reranker (`solar-reranker`)

ADR-003 결정 임베딩과 같은 벤더의 자체 Re-ranking 모델.

- **품질**: 한국어 도메인 특화. 자체 벤치마크에서 한국어 법령·뉴스·계약서 도메인 우위 주장. 단, 공개 다국어 벤치마크 절대 순위는 Cohere 대비 알려진 바가 적음.
- **한국어 법령 도메인 품질**: 한국어 비즈니스 도메인 특화. "기능성표시식품" vs "건강기능식품" 같은 좁은 어휘 정확도가 임베딩과 정렬됨 → 의미 등가 매칭 일관성 확보.
- **API 응답 시간**:
  - 단일 호출, Top-K 20개 기준 P50 100~200ms, P95 250~400ms (국내 리전 제공 시)
  - 임베딩과 같은 호스트 → 두 호출 연속 시 keep-alive·인증 캐시 활용 가능
- **비용 (Phase 1)**:
  - 시점 가격 변동 가능. 토큰 기반 과금이 일반적이며 Cohere의 검색 단위 과금 대비 비쌀 수도/저렴할 수도 있음
  - 임베딩과 통합 과금 → 견적·결제 1건으로 관리
  - 보수적 추정: 월 $200~500 범위 (계약 조건에 따라 협상 가능)
- **데이터 비유출**: △ 국내 리전·국내 법인 계약 가능 → ADR-003 결정과 동일 수준의 수용성. 외부 전송 거부 고객은 여전히 수용 불가.
- **로컬 배포**: ✕ (Solar reranker weight 비공개).
- **벤더 일관성**: ◎ 임베딩과 동일 벤더 → SDK·인증·과금·SLA·계약 일원화. 운영 단순화 효과 큼.
- **운영 부담**: 거의 0 (REST 호출).
- **Spring Boot 통합**: WebClient REST 호출. Upstage 공식 Java SDK 없음(Python·OpenAI 호환 SDK 중심). 임베딩 Adapter와 코드 패턴 통일 가능.

### 옵션 C — Cross-encoder (로컬, BGE-reranker-v2-m3 또는 KoBERT 계열)

Sentence Transformers / FlagEmbedding 기반 cross-encoder. 후보 (질의, 문서) 쌍을 직접 점수화.

- **품질**: BGE-reranker-v2-m3는 다국어 Re-ranking에서 Cohere v3와 동급 또는 약간 아래로 평가되는 오픈소스 최상위 모델. 한국어 일반 품질 양호. 법령 도메인은 평가 필요.
- **한국어 법령 도메인 품질**: 평가 세트로 검증 필요. 일반 한국어 Recall은 양호하나 좁은 법령 어휘에서는 Upstage 대비 약할 가능성.
- **응답 시간**:
  - GPU(L4/A10) 1대 기준, (질의, 문서) 쌍 20개 배치 처리 < 50ms
  - CPU 추론은 (질의, 문서) 쌍 20개 직렬 처리 시 300~800ms → **CPU만으로는 P95 < 500ms 위태로움**
  - 진정한 < 50ms를 보장하려면 GPU 인스턴스 상시 운영 필요
- **비용**:
  - 모델 무료, 인프라 비용만 발생
  - GPU 상시 운영 (g5.xlarge 24/7) ≈ **월 $730**
  - 베타 트래픽 단계에선 CPU 추론(m6i.large $70/월)도 가능하나 P95 보장 어려움
  - 200 RPS 정식 트래픽에선 GPU 1~2대 + 오토스케일링 → 월 $1,000~2,000
- **데이터 비유출**: ◎ 완전 로컬. 고객 데이터가 인프라 밖으로 나가지 않음.
- **로컬 배포**: ◎ Hugging Face `BAAI/bge-reranker-v2-m3` 다운로드. MIT 라이선스.
- **벤더 일관성**: △ 임베딩(API)과 추론 모드 불일치(로컬). 비유출 SKU에서는 임베딩(BGE-m3) + Re-ranker(bge-reranker-v2-m3) 페어로 일관됨.
- **운영 부담**: 큼. 모델 서빙(text-embeddings-inference, BentoML 등), GPU OOM·동시성 튜닝, 모델 버전 관리, 인스턴스 헬스체크. **Phase 1 소수 인력에 부담.**
- **Spring Boot 통합**: 내부 추론 서버에 WebClient REST 호출 (gRPC도 가능). 격리 자체는 깔끔.

---

## 비교 매트릭스

| 기준 | A. Cohere Rerank | B. Upstage Reranker | C. Cross-encoder (로컬) |
|------|------------------|---------------------|-------------------------|
| 한국어 일반 의미 정렬 | ⭕ 매우 좋음 | ◎ 매우 좋음 (한국어 특화) | ⭕ 좋음 |
| 한국어 법령 도메인 정렬 | ⭕ 양호 (검증 필요) | ◎ 임베딩과 도메인 일관 | △ 보통 (평가 필요) |
| Top-5 Precision (예상) | ◎ | ◎ (한국어 도메인) | ⭕ |
| 응답 시간 P95 (Top-K 20) | 200~300ms + RTT 80~120ms | 250~400ms (국내 리전) | < 50ms (GPU) / 300~800ms (CPU) |
| 200 RPS 기준 P95 < 500ms 달성 | △ 해외 RTT 위험 | ⭕ 국내 리전 시 안정 | ◎ GPU 시 압도적 / ✕ CPU 시 위태 |
| Phase 1 월 비용 | $600 (베타) ~ $1,800 (Phase 2) | $200~500 | $70 (CPU) ~ $730 (GPU) |
| 데이터 비유출 | ✕ | △ 국내 리전 협상 | ◎ |
| 로컬 배포 | ✕ | ✕ | ◎ |
| 벤더 일관성 (ADR-003과) | ✕ 별도 벤더 | ◎ 같은 벤더 | △ 비유출 SKU에서만 일관 |
| 운영 부담 | ◎ 거의 0 | ◎ 거의 0 | ✕ 큼 (GPU 서빙) |
| Spring Boot 통합 난이도 | ⭕ WebClient | ◎ WebClient (Adapter 패턴 재사용) | ⭕ 내부 서버 REST |
| Phase 1 인력 적합성 | ◎ | ◎ | △ |
| 미래 비유출 옵션 제공 | ✕ | △ | ◎ |

---

## Decision

**옵션 B — Upstage Reranker (`solar-reranker`)를 1차 디폴트로 선택한다. 단, `:rag` 모듈에 `RerankerPort` 인터페이스를 두어 옵션 C(BGE-reranker-v2-m3 로컬)를 "비유출 SKU 대체 구현체"로, 옵션 A(Cohere)를 "벤더 리스크 폴백"으로 언제든 교체 가능하도록 격리한다.**

### 결정 요약

- **벤더 일관성 (결정적 이유)**: ADR-003에서 임베딩을 Upstage로 선택한 이유와 동일한 논리가 Re-ranker에 적용된다. 한국어 법령 도메인 특화 + 같은 벤더 → SDK·인증·과금·SLA·계약 1건으로 통합. 임베딩과 Re-ranker가 동일 도메인 토큰화·정규화 패턴을 공유하므로 의미 매칭 일관성이 자연스럽게 정렬된다.
- **응답 시간**: 국내 리전 기준 P95 250~400ms로 검색 API P95 < 500ms 예산 안에 들어옴. Cohere는 해외 리전 RTT 가산 시 예산 초과 리스크가 더 큼.
- **운영 부담**: Phase 1은 소수 인력. 옵션 C(GPU 서빙)는 임베딩(Upstage API) + Re-ranker(자체 GPU)로 운영 모드가 비대칭이 되어 RAG 파이프라인 자체 진척이 늦어진다. **Phase 1에선 운영 부담을 회피**한다.
- **비용**: 옵션 A 대비 절감 가능, 옵션 C(GPU 상시 운영) 대비 비슷하거나 저렴. 임베딩과 통합 견적·결제로 관리 단순.
- **비유출 요구사항**: ADR-003과 동일하게 "Phase 1.5+ 별도 SKU"로 옵션 C 페어 추가. 모든 고객이 비유출을 요구하지 않으므로 단계적 전략 합리적.

### 포트 인터페이스로 격리

ADR-003의 `EmbeddingPort`와 같은 패턴을 적용한다.

```java
public interface RerankerPort {
    /**
     * 후보 문서를 질의와 비교해 의미 관련도 순으로 재정렬.
     * @param query 사용자 질의(한국어 광고 카피 또는 검색어)
     * @param candidates 하이브리드 검색 후보 (BM25 + 벡터 통합, Top-K)
     * @param topN 반환할 상위 개수 (기본 5)
     * @return 재정렬된 결과. 원본 후보의 id·score를 보존하고 reranker score를 부여.
     */
    List<RerankedDocument> rerank(String query, List<Candidate> candidates, int topN);

    /** 어댑터 식별자 (메트릭/로그 태깅용) */
    String adapterId();
}
```

- **1차 구현체**: `UpstageSolarRerankerAdapter` (REST, 국내 리전 우선 계약)
- **2차 구현체 (Phase 1.5~2, 비유출 SKU)**: `BgeRerankerV2M3LocalAdapter` (GPU 추론 서버)
- **폴백 구현체 (Upstage 장애 시)**: `CohereRerankAdapter` (REST, 별도 API 키)
- **No-op 구현체 (로컬 개발·평가)**: `IdentityRerankerAdapter` (입력 순서 그대로 반환, 하이브리드 점수만으로 평가 시 baseline)

세 구현체 모두 동일 `RerankerPort` 구현 → 빈 교체만으로 런타임 전환 가능.

### Exit Strategy

다음 조건 중 하나 발생 시 Re-ranker를 교체한다.

1. **품질 미달 (1차)**: EVAL-002 한국어 법령 평가에서 Upstage Re-ranker 적용 후 Precision@5 또는 Recall@5가 baseline(IdentityRerankerAdapter, 즉 하이브리드 점수만) 대비 +10%p 이상 개선되지 않음 → Cohere Rerank A/B 테스트 (월 비용 $600 일시 부담 감수). A/B 결과 Cohere가 유의미 우위면 디폴트 교체.
2. **응답 시간 미달**: P95 > 500ms 지속 발생 → 국내 리전 SLA 재협상 또는 옵션 C(GPU 로컬) 전환.
3. **비유출 고객 실수요 발생**: 1개 이상 유료 고객이 "데이터 외부 전송 불가"를 계약 조건으로 요구 → `BgeRerankerV2M3LocalAdapter` 구현 + 해당 고객 전용 인스턴스(임베딩도 BGE-m3 페어). ADR-003 Exit Strategy 1과 동일 트리거 → **임베딩·리랭커 페어로 동시 교체**.
4. **벤더 리스크**: Upstage API SLA 사고, 가격 급변, 서비스 단종 → `CohereRerankAdapter`로 즉시 폴백.
5. **트래픽 임계**: 200 RPS 정식 트래픽에서 Upstage API rate limit 또는 비용이 임계 초과 → GPU 로컬 옵션 C로 자체 호스팅 검토.

### A/B 평가 계획

EVAL-002 단계에서 다음을 함께 측정한다 (저비용으로 정량 비교 가능):

| 조건 | 임베딩 | Re-ranker | 비고 |
|------|--------|-----------|------|
| Baseline 1 | Upstage solar-embedding | Identity (하이브리드 점수만) | Re-rank 효과 측정 baseline |
| 결정안 | Upstage solar-embedding | Upstage solar-reranker | 본 결정 |
| 대안 1 | Upstage solar-embedding | Cohere Rerank v3 multilingual | 비용 ~$600 1회 일시 부담 |
| 대안 2 (선택) | Upstage solar-embedding | BGE-reranker-v2-m3 (로컬) | 평가용 GPU 임시 인스턴스 |

지표: Precision@5, Recall@5, MRR@10, 평균 응답 시간 (Re-rank 구간만).

---

## 기술 영향

### 1. `:rag` 모듈 코드 구조

- `RerankerPort` 인터페이스 추가 (`:rag` 모듈의 `port` 패키지)
- `UpstageSolarRerankerAdapter` 구현체 (`:rag` 모듈의 `adapter` 패키지, WebClient 기반)
- `RerankerProperties` 설정 클래스: 모델명·topN 기본값·timeout·retry·국내 리전 엔드포인트
- `IdentityRerankerAdapter` 구현체: 단위 테스트·평가 baseline·로컬 개발용
- 향후 `CohereRerankAdapter`, `BgeRerankerV2M3LocalAdapter`는 별도 ADR 또는 Phase 1.5+에서 추가 (본 ADR 범위 외)

### 2. 환경 변수 (`.env.example` 추가 항목)

```
UPSTAGE_API_KEY=         # ADR-003에서 이미 정의됨 (임베딩과 공유)
UPSTAGE_RERANKER_MODEL=solar-reranker
UPSTAGE_RERANKER_TIMEOUT_MS=400
UPSTAGE_RERANKER_TOP_N=5
# 폴백·평가용 (선택, 미설정 시 어댑터 미등록)
COHERE_API_KEY=
COHERE_RERANKER_MODEL=rerank-multilingual-v3.0
```

ADR-003의 `UPSTAGE_API_KEY`를 임베딩·Re-ranker가 공유한다. 환경 변수 1건으로 두 어댑터 운영 가능.

### 3. Spring Boot 통합 패턴

- `:api` 모듈 `RagSearchService`에서 흐름:
  ```
  bm25Port.search(query, k=50) ∥ vectorPort.search(query, k=50)
        ↓ (RRF 통합, Top-30)
  rerankerPort.rerank(query, candidates, topN=5)
        ↓
  검색 응답 (조 단위 메타데이터 포함)
  ```
- `RerankerPort` 빈은 `@ConditionalOnProperty(name = "reranker.adapter", havingValue = "upstage", matchIfMissing = true)`로 디폴트 설정.
- 단위 테스트는 `IdentityRerankerAdapter`로 주입 → 외부 API 의존성 제거.

### 4. 응답 시간 예산 재확인 (P95 < 500ms)

| 구간 | 예산 | 비고 |
|------|------|------|
| 질의 임베딩 (Upstage) | 100~200ms | 자주 호출되는 질의는 Redis/Caffeine 캐시 적용 |
| BM25 (ParadeDB, 같은 DB hop) | 30~80ms | 인덱스 튜닝 가정 |
| 벡터 검색 (pgvector HNSW, 같은 DB hop) | 30~80ms | 4096차원 부담 고려 |
| RRF 결합 + 메타데이터 fetch | 10~30ms | 같은 DB hop |
| **Re-rank (Upstage)** | **150~250ms** | 국내 리전 가정 |
| 직렬화·네트워크·여유 | 50~100ms | |
| **합계 (P95)** | **370~740ms** | 캐시 적중 시 200~450ms |

- 캐시 비적중 P95가 500ms를 초과할 가능성이 있음 → **질의 임베딩 캐시 + 자주 본 후보군 캐시는 RAG-004 구현 시 필수**.
- 200 RPS는 캐시 적중 후 측정치 → 실제 Re-rank 호출 RPS는 50~100 가정. Upstage rate limit 사전 확인 필요.

### 5. 비용 누적 (Phase 1 + Phase 2)

| 항목 | Phase 1 (베타) | Phase 2 (정식) |
|------|----------------|----------------|
| 임베딩 (ADR-003) | $20/월 | $100~150/월 |
| **Re-rank (본 결정)** | **$200~500/월** | **$500~1,000/월** |
| 합계 (Upstage 전체) | $220~520/월 | $600~1,150/월 |

- 검사 1건 원가에서 Re-rank가 가장 큰 비중. **비용 가드레일(OPERATIONS.md 3번)**의 1순위 모니터링 대상.
- 모델 라우팅(쉬운 케이스는 Re-rank 생략, BM25 점수가 임계 초과 시 바로 LLM에)으로 호출량 30~50% 절감 여지 → Phase 2 비용 운영에서 검토.

### 6. OPERATIONS.md 정합성

| OPERATIONS 항목 | 본 결정과의 정합성 |
|-----------------|---------------------|
| 1. 규정 데이터 운영 | Re-ranker 자체는 규정 데이터를 학습하지 않음 → 영향 없음 |
| 2. AI 품질 운영 | EVAL-002에 Re-rank A/B 평가 추가. 변경 관리 대상 (모델·임계값 변경 시 회귀 평가 의무) |
| 3. LLM 비용 운영 | Re-rank가 검사 1건 원가의 주요 항목 → 대시보드 1순위 |
| 4. 인프라 운영 | 외부 API 의존 추가 → Upstage 가용성 모니터링 + Cohere 폴백 헬스체크 |
| 5. 법적 리스크 (고객 카피 비유출) | 1차 SKU는 외부 전송 → **개인정보 동의·고지 약관 필수**. 비유출 SKU는 Phase 1.5+에 옵션 C 페어로 제공 |
| 6. 고객 지원 운영 | 영향 없음 |

**고객 카피 비유출**과 관련해 본 결정의 명시적 한계:
- 1차 SKU(Upstage Reranker 사용)는 고객 광고 카피가 Upstage(국내 리전)로 전송됨.
- 이는 ADR-003 임베딩과 동일한 데이터 흐름이며, 이미 ADR-003에서 합의된 범위.
- "외부 전송 절대 불가" 고객은 ADR-003 + ADR-004를 **페어로 옵션 C(BGE-m3 + bge-reranker-v2-m3)로 전환**해야 함 → Phase 1.5+ 비유출 SKU의 전제.

---

## Consequences

### 긍정

- 임베딩과 동일 벤더 → SDK·인증·과금·SLA·계약 통합. 운영 단순.
- 한국어 법령 도메인 일관성: 임베딩과 Re-ranker가 같은 도메인 토큰화·정규화 → 의미 매칭 일관.
- 운영 부담 최소 (REST 호출). Phase 1 소수 인력으로 RAG 진척에 집중 가능.
- 국내 리전 시 응답 시간 예산 안에 안정적 운영 가능.
- `RerankerPort` 격리 → Cohere(폴백), BGE 로컬(비유출) 교체 비용 최소.
- ADR-003과 동일 결정 패턴 → 코드·문서 일관성 유지.

### 부정 / 대응

- (-) Cohere 대비 절대 품질이 미검증 → **EVAL-002 A/B로 정량 검증** 의무. Cohere가 +10%p 이상 우위면 교체.
- (-) Upstage Re-ranker SLA·rate limit이 OpenAI/Cohere 대비 검증 부족 → 계약 시 SLA·rate limit 명시 확보 + Cohere 폴백 어댑터 준비.
- (-) Phase 1 비용에서 Re-rank가 가장 큰 비중 → 비용 가드레일 1순위. 모델 라우팅으로 호출량 절감 옵션 Phase 2에서 도입.
- (-) Vendor lock-in (ADR-003 + ADR-004 페어로 Upstage 의존도 증가) → `RerankerPort`/`EmbeddingPort` 격리 + 페어 폴백 옵션 명시.
- (-) "고객 카피 비유출" 카피와 1차 SKU 충돌 → ADR-003과 동일 처리 (Phase 1.5+ 비유출 SKU). STRATEGY.md 카피 조정은 ADR-003 결정과 함께 처리.
- (-) 응답 시간 예산이 캐시 비적중 시 빠듯 → RAG-004 구현 시 질의 임베딩·자주 본 후보군 캐시 필수화 (별도 작업 아닌 RAG-004 작업 범위 안).

### 향후 결정 영향

- **D5 (LLM 엔진)**: Upstage Solar LLM과 임베딩·Re-ranker가 모두 같은 벤더 → 통합 SDK·과금·SLA 통합 가능. 단, LLM 품질 요구가 더 까다로우므로 D5는 독립 평가 (ADR-003에서 같은 결론).
- **D6 (에이전트 오케스트레이션)**: 영향 없음. Re-ranker는 오케스트레이션 프레임워크와 독립.
- **Phase 2 멀티에이전트**: 각 에이전트(검사·비판·교정)가 부분 컨텍스트 재조회 시 Re-rank가 반복 호출됨 → 호출량·비용·캐시 전략을 Phase 2 시작 시 재검토.
- **비유출 SKU 로드맵**: ADR-003 다음 액션 6번과 통합. 임베딩·Re-ranker 페어 교체로 단일 작업화.
- **STRATEGY.md 카피**: ADR-003과 동일 갭. 별도 작업으로 위임 (중복 액션 생성 회피).

---

## 팀 리뷰 요약

| 팀원 | 피드백 | 반영 |
|------|--------|------|
| ai-rag-specialist | 임베딩-리랭커 도메인 일관성이 한국어 법령 검색에서 결정적. EVAL-002 A/B 평가에 Cohere를 반드시 포함해 정량 비교 보장. Baseline(Identity)도 측정해 Re-rank 자체의 효과 분리. | "A/B 평가 계획" 반영 |
| backend-specialist | `RerankerPort` + WebClient Adapter 구조 좋음. timeout 400ms·retry 1회 보수적 설정. Cohere 폴백 어댑터를 빈으로 미리 등록하되 `@ConditionalOnProperty`로 비활성 유지. | "Spring Boot 통합 패턴"에 반영 |
| database-infra-engineer | Re-ranker는 DB 영향 없음. 단, 응답 시간 예산에서 pgvector(4096차원) + ParadeDB가 같은 DB hop이라는 이점을 살리도록 HNSW 인덱스 튜닝(`ef_search`) 필수. | "응답 시간 예산" 표에 명시 |
| data-engineer | Re-ranker가 후보 청크의 원문 길이에 민감 (Cross-encoder/transformer류는 입력 길이가 비용·시간에 비례). 청크 크기 상한을 RAG-002에서 1000~1500토큰으로 제한해 Re-rank 비용·시간 예측 가능하게 유지. | RAG-002 작업 노트로 위임 |
| qa-validator | EVAL-002에 Precision@5, Recall@5, MRR@10 모두 측정. Re-rank 적용 전후 비교를 기본 리포트에 포함. | "A/B 평가 계획" 표에 반영 |
| (사업 관점) | 비용에서 Re-rank가 검사 1건 원가의 최대 항목이 될 수 있음 → Phase 2에서 모델 라우팅·캐시·임계 기반 생략 등 비용 운영 수단 명시. | "비용 누적" 표 + Phase 2 향후 결정 영향에 명시 |

---

## 다음 액션

1. **`:rag` 모듈 인터페이스 추가**: `RerankerPort` + `UpstageSolarRerankerAdapter` + `IdentityRerankerAdapter` 스켈레톤 (RAG-004 작업으로 위임).
2. **환경 변수**: `.env.example`에 `UPSTAGE_RERANKER_MODEL`, `UPSTAGE_RERANKER_TIMEOUT_MS`, `UPSTAGE_RERANKER_TOP_N` 추가. 폴백용 `COHERE_API_KEY`, `COHERE_RERANKER_MODEL`은 선택 항목으로 추가하되 디폴트 비활성.
3. **EVAL-002 평가 계획 확장**: A/B 4조건(Identity / Upstage / Cohere / BGE 로컬)을 평가 세트에 추가. Cohere 일회성 평가 비용(~$600) 예산 사전 승인.
4. **Upstage 계약 검토**: 임베딩 계약에 Re-ranker SLA·rate limit·국내 리전 조항 함께 포함. ADR-003 다음 액션 7과 통합 처리.
5. **모니터링 메트릭**: Re-rank 호출 카운터·지연시간 히스토그램·실패율을 Phase 1 메트릭에 포함 (어댑터 ID 태그로 폴백 추적 가능하게).
6. **비용 가드레일 정의**: Re-rank 호출량 일일·월간 임계 알림. Phase 2 비용 운영 도입 전이라도 Phase 1에서 기준선 측정.
7. **비유출 SKU 로드맵 메모 통합**: ADR-003 다음 액션 6에 본 결정의 페어 전환(BGE-m3 + bge-reranker-v2-m3)을 추가. 사업·PM 측 공유 시 한 번에 처리.

---

## 참고

- 본 결정은 ADR-003과 같은 결정 패턴(한국어 도메인 특화 + 운영 부담 최소 + 포트 격리)을 일관 적용했다.
- "고객 카피 비유출"이라는 핵심 차별점은 임베딩·Re-ranker를 페어로 BGE 계열로 전환해 Phase 1.5+ 별도 SKU로 제공한다 — 본 결정은 그 가능성을 인터페이스로 미리 확보해둔다.
- 한국어 법령 도메인 품질, 응답 시간 예산, 운영 부담, 벤더 일관성이라는 4가지 기준이 옵션 B를 선택한 결정적 이유다.
- EVAL-002에서 정량 검증을 통해 본 결정을 사후 재검토할 수 있도록 A/B 평가 계획을 사전에 못박았다.

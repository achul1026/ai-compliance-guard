# RAG-004: 하이브리드 검색 + Re-ranking 샘플 결과

- 작성: 2026-05-26
- 담당: AI/RAG Specialist
- 대상 모듈: `:rag`
- 관련 ADR: ADR-002 (BM25=ParadeDB), ADR-003 (Embedding=Upstage solar-embedding-1-large 4096차원), ADR-004 (Reranker=Upstage solar-reranker)
- 관련 작업: Task #10 (RAG-004)

---

## 1. 구현 요약

### 결합 방식 (Score Normalization + Weighted Sum)

`HybridSearchService.combine()`은 두 검색 경로의 점수를 다음과 같이 결합한다.

```
hybrid_score = vector_score * 0.6 + normalized_bm25_score * 0.4
```

- `vector_score`: pgvector `1 - cosine_distance` → 이미 0~1 범위, 추가 정규화 불필요.
- `normalized_bm25_score`: 결과 집합 내 `max(bm25_score)`로 나눠 0~1로 정규화.
- 한쪽 경로에만 매칭된 후보는 누락하지 않고 상대 점수 0으로 처리.

### Re-ranking

- 하이브리드 점수 상위 20개를 `RerankerPort.rerank(query, candidates, topN=10)`로 전달.
- 1차 구현체: `UpstageSolarRerankerAdapter` (`solar-reranker` 모델, 국내 리전).
- Reranker 실패/빈 응답 시 하이브리드 점수 내림차순으로 폴백 (가용성 우선).

### 산출 파일

| 파일 | 역할 |
|------|------|
| `rag/src/main/java/com/achul/compliance/rag/HybridSearchService.java` | 결합 + Reranker 호출 메인 서비스 |
| `rag/src/main/java/com/achul/compliance/rag/dto/HybridScore.java` | 결합 단계 중간 점수 DTO |
| `rag/src/main/java/com/achul/compliance/rag/dto/RerankedResult.java` | 최종 결과 DTO |
| `rag/src/main/java/com/achul/compliance/rag/adapter/UpstageSolarRerankerAdapter.java` | Upstage Reranker REST 어댑터 (기존 구현체 재사용) |
| `rag/src/test/java/com/achul/compliance/rag/HybridSearchServiceTest.java` | 결합 가중치·폴백·blank 쿼리 단위 검증 |

### 보존된 기존 자산

- `HybridSearchAdapter` (RRF 기반): 비교 평가 시 baseline 비교 옵션으로 보존. 본 작업의 새 가중합 서비스와 공존.

---

## 2. 단위 테스트 결과 (인메모리 페이크 의존)

`./gradlew :rag:test --tests "com.achul.compliance.rag.HybridSearchServiceTest"` → BUILD SUCCESSFUL.

| 시나리오 | 검증 사항 | 기대 점수 |
|---|---|---|
| 벡터에만 매칭 (similarity=1.0) | `vectorScore=1.0 × 0.6` | totalScore = 0.60 |
| BM25에만 매칭 (raw=10, max=10 → 정규화 1.0) | `keywordScore=1.0 × 0.4` | totalScore = 0.40 |
| 양쪽 매칭 (vec=0.5, bm25=5 / max=10 → 0.5) | `0.5×0.6 + 0.5×0.4` | totalScore = 0.50 |
| Reranker 빈 응답 → 폴백 | 하이브리드 점수 내림차순 정렬 | doc(sim=0.9) > doc(sim=0.3) |
| blank/null 쿼리 | 외부 호출 없이 빈 리스트 | size = 0 |

---

## 3. 의도된 쿼리 시나리오 (3건)

본 코드 단계에서는 임베딩·BM25 인덱스가 실제 적재된 환경(`docker compose up postgres` + RAG-002·RAG-003 적재 완료)에서만 결과를 산출할 수 있다. 아래는 본 서비스가 받는 입력·각 단계 출력의 **기대 형상**과 **검증 기준**이다. 실제 수치는 RAG-005 통합 후 EVAL-002에서 확정한다.

### 시나리오 A — `"건강기능식품 질병 예방"`

- **검증 의도**: 광고 카피의 표면 어휘(질병 예방)와 법령 조항(질병의 예방·치료 효능 표시 광고 금지)의 의미 매칭.
- **벡터 검색 (Top-5 기대)**:
  1. 식품표시광고법 제8조 ①항 — "질병의 예방·치료 효능 표시·광고 금지"
  2. 건강기능식품법 제18조 — "허위·과대 광고 금지"
  3. 건강기능식품 표시·광고 심의 가이드라인 — "질병 관련 표현 금지" 절
  4. 식품표시광고법 시행령 제7조 — "구체적 광고 금지 표현"
  5. 부당표시광고 내용기준(식약처 고시) — "질병 예방·치료 표현" 항목
- **BM25 검색 (Top-5 기대)**: "건강기능식품"·"질병"·"예방" 정확 매칭이 강한 조항·고시 본문 위주. 가이드라인·고시 본문이 상위로 올라올 가능성이 높음.
- **결합 단계 기대**: 양쪽 모두에 등장하는 제8조 ①항이 가장 높은 totalScore. 정규화 후 둘 다 0.8 이상이면 `total ≈ 0.8×0.6 + 0.8×0.4 ≈ 0.80`.
- **Re-ranker 기대**: 의미 등가성이 가장 정확한 식품표시광고법 제8조 ①항을 rank=1로 끌어올림. "질병 예방"이라는 표현이 본문에 직접 등장하는 가이드라인이 rank=2~3.
- **합격 기준**: Top-5에 식품표시광고법 제8조와 건강기능식품법 제18조가 모두 포함.

### 시나리오 B — `"식품표시광고법 제8조"`

- **검증 의도**: 정확 조항 검색에서 BM25 강점이 발휘되는지. 가중치 0.4로 줄어든 키워드 점수만으로도 정답 조항이 Top-1로 올라올 수 있는지.
- **벡터 검색 (Top-5 기대)**: 식품표시광고법 제8조 전 항(①·②·③)이 임베딩상 인접해 함께 등장. 의미상 유사한 건강기능식품법 제18조도 후보.
- **BM25 검색 (Top-5 기대)**: "식품표시광고법"·"제8조" 정확 토큰 매칭으로 본 조항이 압도적 1위. BM25 점수 raw 값이 다른 후보의 2배 이상으로 벌어질 가능성이 높음 → 정규화 후 max=1.0.
- **결합 단계 기대**: 제8조 ①항이 양쪽에서 1위 → `total ≈ 1.0×0.6 + 1.0×0.4 = 1.00`. 다른 조항(예: 제9조, 시행령)은 BM25 raw가 max의 30~50% 수준이면 키워드 기여가 0.12~0.20으로 작아 total < 0.5.
- **Re-ranker 기대**: 제8조 ①·②·③항이 rank=1~3을 모두 차지. 제8조와 무관한 시행령·가이드라인 항목은 Top-5에서 밀려남.
- **합격 기준**: Top-1이 식품표시광고법 제8조의 항 중 하나, Top-3 안에 동일 조의 다른 항.

### 시나리오 C — `"의약품처럼 오인"`

- **검증 의도**: 광고 카피 톤("의약품처럼 오인")과 법령 톤(의약품으로 오인할 우려가 있는 표시·광고 금지)의 어순·문체 차이 흡수.
- **벡터 검색 (Top-5 기대)**:
  1. 식품표시광고법 제8조 ②항 — "의약품으로 오인할 우려가 있는 표시·광고 금지"
  2. 약사법 제68조 — "의약품으로 오인하게 하는 표시·광고 금지"
  3. 화장품법 제13조 — "의약품으로 잘못 인식할 우려가 있는 표시·광고 금지"
  4. 건강기능식품법 제18조 ②항 — 유사 취지
  5. 부당표시광고 내용기준 — "의약품 오인 사례" 항목
- **BM25 검색 (Top-5 기대)**: "오인"·"의약품" 정확 토큰 매칭. 표현이 "오인할 우려"와 정확 일치하는 약사법·식품표시광고법 조항이 상위.
- **결합 단계 기대**: 제8조 ②항이 벡터 1위 + BM25 상위 → totalScore 최상위. 약사법 제68조가 양쪽 2위로 따라옴.
- **Re-ranker 기대**: 식품 도메인 컨텍스트(건강기능식품 관련 카피라면)에서 식품표시광고법 제8조 ②항이 rank=1. 일반 의약품 광고라면 약사법 제68조가 rank=1로 교차.
- **합격 기준**: Top-5에 식품표시광고법 제8조 ②항, 약사법 제68조, 화장품법 제13조 중 최소 2개 포함.

---

## 4. 응답 시간 예산 (ADR-004 §4 재인용 + 본 서비스 분담)

| 단계 | 예산 (P95) | 비고 |
|------|------------|------|
| 질의 임베딩 (Upstage) | 100~200ms | 캐시 미적중 기준 |
| 벡터 검색 (pgvector HNSW, 4096차원) | 30~80ms | `searchByText` 내부 |
| BM25 검색 (ParadeDB, 동일 DB hop) | 30~80ms | 별도 DB hop이지만 동일 인스턴스 |
| 결합 (인메모리 가중합, 최대 40개 후보) | < 5ms | `combine()` 단순 Map 연산 |
| Re-rank (Upstage solar-reranker, 후보 20) | 150~250ms | 국내 리전 가정 |
| 직렬화/네트워크/여유 | 50~100ms | |
| **합계 (P95)** | **365~715ms** | 캐시 적중 시 200~450ms |

- 캐시 비적중 시 500ms 초과 가능 → RAG-005에서 질의 임베딩 캐시(Caffeine/Redis) 도입 시 안정화. 본 작업 범위 밖.
- 200 RPS 정식 트래픽 가정은 캐시 적중 후 측정치라는 ADR-004의 전제 유지.

---

## 5. 폴백·예외 처리

| 상황 | 동작 |
|------|------|
| 벡터 검색 실패 | 예외를 상위로 전파 (RAG-005에서 try/catch 정책 결정). 본 서비스는 결합만 책임. |
| BM25 결과가 비어 있음 | 벡터 점수만으로 결합 → `keywordScore=0`. 결과는 벡터 단독과 같음. |
| Reranker 빈 응답 / 예외 | 하이브리드 점수 내림차순으로 폴백, rank·rerankScore에 하이브리드 점수 대입. 가용성 우선. |
| 쿼리가 null/blank | 외부 호출 없이 빈 리스트 즉시 반환. |
| BM25 max=0 (모든 raw=0) | 분모 0 회피 → keywordScore 일괄 0. |

---

## 6. 다음 작업으로의 인계 (RAG-005 / EVAL-002)

- **RAG-005**: `HybridSearchService.search(query, topN)`을 `:api` 모듈의 `/search` 엔드포인트가 호출하도록 연결. 응답 페이로드에 `RerankedResult`를 `SearchResponse`로 변환하는 매핑 추가.
- **EVAL-002**: 본 문서 §3의 3개 쿼리를 골든 세트 일부로 등록. 합격 기준(Top-5 포함 여부)을 Precision@5 측정 기준으로 활용. ADR-004 §"A/B 평가 계획"에 따라 4조건(Identity / Upstage / Cohere / BGE 로컬)도 EVAL-002에서 별도 측정.

---

## 7. 검증 체크리스트

- [x] 가중치 0.6/0.4 적용 (`vectorScore × 0.6 + normalizedBm25 × 0.4`)
- [x] BM25 점수 max 기반 0~1 정규화
- [x] 한쪽 경로만 매칭된 후보 보존 (양쪽 합집합)
- [x] Reranker 호출은 상위 20개로 제한 (`candidate-top-k=20`)
- [x] Reranker 실패 시 하이브리드 점수 폴백
- [x] DTO 분리 (`HybridScore`, `RerankedResult`)
- [x] 단위 테스트 4건 통과 (`./gradlew :rag:test` BUILD SUCCESSFUL)
- [x] `./gradlew :rag:compileJava` 성공
- [ ] 실제 적재 DB 기반 3개 쿼리 검증 → RAG-005·EVAL-002에서 수행 (본 작업 범위 외)
- [ ] P95 < 500ms 실측 → RAG-005 통합 후 측정

---

## 8. 참고

- ADR-004는 1차 결합 방식으로 RRF와 가중합 모두 허용한다. 본 작업은 사용자 가이드(0.6/0.4 가중합)를 채택했고, RRF 구현체(`HybridSearchAdapter`)는 비교 baseline으로 보존했다.
- BM25 정규화는 결과 집합 내 max 기반 — 글로벌 max 통계가 없어도 동작하나, 결과 집합이 매우 작을 때(<3건) 정규화 의미가 약해진다. EVAL-002 결과에 따라 sigmoid·rank 기반 정규화로 교체 검토.

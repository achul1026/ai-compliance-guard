# ADR-003: 임베딩 모델 선택 (D3)

- 상태: **확정 (Accepted)**
- 결정자: architect (팀 리뷰 반영)
- 결정일: 2026-05-26
- Phase: 1
- 관련 결정: ADR-002 (BM25 = ParadeDB), D4 (Re-ranking), D5 (LLM 엔진)
- 영향 산출물: `infra/src/main/resources/db/migration/V1__init_schema.sql` (현재 `vector(1536)`) → V2 마이그레이션에서 차원 재조정

---

## Context

Hybrid RAG 파이프라인의 의미 검색(Vector Search) 단을 담당할 임베딩 모델을 확정해야 한다. ADR-002에서 BM25(ParadeDB)를 키워드 측으로 결정했고, 임베딩은 그 보완 측으로서 다음 문장 쌍의 유사도를 정확히 잡아야 한다.

### 한국어 법령·광고 컴플라이언스 도메인 요구사항

1. **의미 등가 매칭**: 사용자가 입력한 광고 카피의 "질병 예방·치료 효능 표방" 표현과, 식품표시광고법 제8조의 "질병의 예방·치료에 효능·효과가 있는 것으로 인식할 우려가 있는 표시·광고" 조항이 의미상 매칭되어야 한다. 표면 어휘는 다르지만 규제 의도가 동일한 케이스를 잡아야 한다.
2. **위반 유형 클러스터링**: "효능 과장", "허위 표시", "비교 광고", "체험기 광고" 등 위반 유형이 임베딩 공간에서 자연스럽게 군집을 형성해야 (Phase 2에서) 유형 분류 에이전트가 효과적으로 동작한다.
3. **한국어 도메인 어휘 이해**: "건강기능식품", "기능성표시식품", "일반식품" 같이 외형은 비슷하지만 법적 분류가 다른 용어를 구분할 수 있어야 한다.
4. **법령 영문 혼용 대응**: "GMP", "HACCP", "Probiotics" 같은 영문 약어가 한국어 본문에 섞여 있다 → 다국어 능력 필요.

### Phase 1 운영 요구사항

- **데이터 비유출 (STRATEGY.md의 핵심 차별점)**: 고객 광고 카피·내부 자료는 외부 API로 전송하면 안 되는 시나리오가 사업적으로 발생한다. 적어도 옵션으로 로컬 모델을 운영할 수 있어야 한다.
- **Phase 1 임베딩 규모**: 규정 청크 6,000~10,000건 (초기 1회) + 갱신분 분기 수백~수천 건. 사용자 질의는 1회 임베딩 → 캐시 가능.
- **Phase 2 재사용**: 멀티에이전트 프롬프트·중간 결과 임베딩에도 같은 모델을 사용할 가정. 비용·차원이 누적된다.
- **응답 시간 목표**: 사용자 질의 임베딩 < 500ms, 배치 6,000~10,000 청크 임베딩 < 30분 (API 한도 내 최적화 시 < 30초 수준 배치 청크).

### 현재 결정된 인프라 제약

- ADR-002로 PostgreSQL + pgvector 단일 저장소 확정 → 차원은 `vector(N)` 컬럼에 직접 영향.
- Flyway V1에 `vector(1536)`이 임시 정의되어 있음 → ADR-003 확정 후 V2 마이그레이션 필요.
- 인덱스는 HNSW (`vector_cosine_ops`)를 가정 → 차원이 크면 인덱스 크기·메모리·빌드 시간이 비례 증가.

---

## 후보 비교

### 옵션 A — OpenAI `text-embedding-3` 계열

- **품질**: MTEB 다국어 벤치마크 상위권. 일반 도메인에서 가장 안정적.
- **차원**:
  - `text-embedding-3-small`: 1536차원, $0.02/1M tokens
  - `text-embedding-3-large`: 3072차원, $0.13/1M tokens (Matryoshka 절단으로 256/1024 차원 축소 운영 가능)
- **한국어 법령 품질**: 일반 한국어 의미 검색은 양호. 단, 식약처 고시·법령 같은 좁은 도메인은 도메인 특화 모델(Upstage solar) 대비 미세하게 떨어진다는 사내·공개 리포트가 일관되게 보고됨.
- **API 응답시간**: 배치(2048건) 임베딩 약 5~15초. 단일 질의 임베딩 100~300ms. 목표 충족.
- **비용 (Phase 1)**:
  - 6,000~10,000 청크 × 평균 800 토큰 ≈ 800만 토큰
  - `text-embedding-3-small`: ≈ $0.16 (1회 적재) + 갱신·재임베딩 여유분 포함 연간 $5 이내
  - `text-embedding-3-large`: ≈ $1.0 (1회 적재) + 연간 $30 이내
  - 사용자 질의: 일 1만 건 × 50 토큰 ≈ 1500만 토큰/월 → small $0.30/월, large $2/월
- **데이터 비유출**: ✕ API 전송 필수. OpenAI는 API 입력을 학습에 사용하지 않는다고 명시(zero retention 옵션 별도 계약 필요)하나, 한국 B2B 고객 상당수는 "해외 클라우드 전송 자체"를 차단함.
- **로컬 배포**: ✕ 불가능.
- **Vendor lock-in**: 중 (모델 차원이 1536/3072로 고정. 다른 모델로 교체 시 재임베딩 필요).
- **운영 부담**: 거의 0 (REST 호출만).

### 옵션 B — Upstage `solar-embedding`

- **품질**: 한국 기업 제품. 한국어 MTEB·KLUE 자체 벤치마크에서 OpenAI 대비 한국어 의미 검색 우위 주장. 법령·뉴스·계약서 등 한국어 비즈니스 도메인에서 강함.
- **차원**:
  - `solar-embedding-1-large-query` / `-passage`: 4096차원 (질의·문서 비대칭 모델)
  - 별도 mini 라인은 시점에 따라 가용성 변동 (확정 시점에 SLA 재확인 필요)
- **한국어 법령 품질**: 도메인 특화 평가에서 OpenAI 대비 한국어 법령·약관 검색 Recall 우위 사례 다수. "건강기능식품" vs "기능성표시식품" 같은 좁은 어휘 구분도 양호.
- **API 응답시간**: 배치 API 제공. 동시 요청 한도가 OpenAI 대비 보수적이므로 6,000~10,000 청크는 분할 호출 권장.
- **비용 (Phase 1)**: 시점 가격에 따라 변동. OpenAI `text-embedding-3-large` 대비 토큰당 비슷하거나 약간 비쌈. 절대 금액은 Phase 1 규모에선 무시 가능 수준 (수십 달러 이내).
- **데이터 비유출**: ✕ API 전송 필수. 단, **국내 리전·국내 법인 계약 가능** → 한국 B2B 컴플라이언스 관점에서 OpenAI 대비 수용성 높음.
- **로컬 배포**: ✕ (Solar 모델 자체는 일부 weight 공개되나 임베딩 모델 weight는 비공개 — 상용 라이선스 확인 필요).
- **Vendor lock-in**: 중~상 (4096차원 + 질의·문서 비대칭 모델 구조. 교체 시 재임베딩 + 인덱스 재구축 필수).
- **운영 부담**: 거의 0 (REST 호출).
- **사업 정합성**: ◎ ai-compliance-guard의 1차 타겟이 한국 식약처 규제 대상 기업이라는 점과 정렬.

### 옵션 C — BGE-m3 (BAAI, 오픈소스)

- **품질**: MTEB 다국어 카테고리 상위. Dense + Sparse + ColBERT(다중 벡터) 임베딩을 동시 출력 가능한 멀티 모드 모델. 100여 언어 지원, 한국어 포함.
- **차원**: 1024차원 (Dense). Sparse·ColBERT 별도 출력 가능하나 Phase 1에선 Dense만 사용 가정.
- **한국어 법령 품질**: 일반 한국어는 양호. 좁은 법령 도메인은 Solar 대비 약하다는 평가가 있으나, Phase 1 평가 세트로 정량 검증 가능.
- **응답시간**: 로컬 GPU 1대(A10/L4 급)에서 배치 32~64 기준 초당 100~200 문장. CPU 추론은 단일 문장 200~500ms로 가능하나 배치 처리량은 낮음. 6,000~10,000 청크는 GPU 1대로 10분 이내 완료.
- **비용**: 모델 다운로드·실행 무료. 인프라 비용만 발생.
  - 1회 적재용으로 임시 GPU 인스턴스: AWS g5.xlarge ≈ $1.0/hour, 30분 이내 완료 → 회당 $0.5
  - 상시 추론용 GPU 인스턴스(질의 임베딩): g5.xlarge 24/7 ≈ $730/월. CPU 인스턴스로 단일 질의 임베딩만 처리한다면 m6i.large ≈ $70/월.
- **데이터 비유출**: ◎ 완전 로컬. 모델 weight가 인프라 안에 있음. 고객 데이터가 외부로 나가지 않는다.
- **로컬 배포**: ◎ 가능. Hugging Face `BAAI/bge-m3` 다운로드 → ONNX/TensorRT 변환 가능.
- **Vendor lock-in**: ⭕ 없음 (오픈소스 가중치, MIT 라이선스).
- **운영 부담**: 큼. 모델 서빙(예: `text-embeddings-inference`, BentoML), GPU 인스턴스, OOM·동시성 튜닝, 모델 버전 관리, 인덱스 재빌드 잡 직접 운영.
- **사업 정합성**: ◎ "비유출" 핵심 차별점에 정확히 부합. 단, 운영 인력이 적은 Phase 1 시점에 인프라 부담이 큼.

---

## 비교 매트릭스

| 기준 | A. OpenAI text-embedding-3-large | B. Upstage solar-embedding | C. BGE-m3 (로컬) |
|------|----------------------------------|----------------------------|------------------|
| 일반 한국어 의미 검색 품질 | ⭕ 좋음 | ◎ 매우 좋음 (한국어 특화) | ⭕ 좋음 |
| 한국어 법령·약관 도메인 품질 | △ 보통 | ◎ 우위 | △ 보통 (평가 필요) |
| 영문·약어 혼용 처리 | ◎ 강함 | ⭕ 양호 | ⭕ 양호 (다국어 모델) |
| 차원 (메모리) | 3072 (large) / 1536 (small) | 4096 | 1024 |
| Phase 1 API 비용 | $1 + 월 $2 (large 기준) | 수십 달러 이내 | 임시 GPU $0.5 + 월 CPU $70 |
| 응답 시간 (질의 1건) | ⭕ 100~300ms | ⭕ 100~300ms | ⭕ CPU 300~500ms / GPU < 50ms |
| 배치 6k~10k 응답 시간 | ⭕ 분 단위 | ⭕ 분 단위 | ⭕ GPU 10분 / CPU 1시간+ |
| 데이터 비유출 (해외 전송 차단 고객) | ✕ 불가 | △ 국내 리전 협상 가능 | ◎ 완전 로컬 |
| 로컬 배포 | ✕ | ✕ | ◎ |
| 운영 부담 | ◎ 거의 0 | ◎ 거의 0 | ✕ 큼 (GPU·서빙·튜닝) |
| Vendor lock-in | 중 | 중~상 | 없음 |
| Phase 1 인력 적합성 | ◎ | ◎ | △ (소수 인력엔 부담) |
| 미래 비유출 옵션 제공 | ✕ | △ | ◎ |

---

## Decision

**옵션 B — Upstage `solar-embedding-1-large` (4096차원)을 1차 디폴트로 선택한다. 단, `:rag` 모듈에 `EmbeddingPort` 인터페이스를 두어 옵션 C(BGE-m3)를 "비유출 고객용 대체 구현체"로 언제든 교체 가능하도록 격리한다.**

### 결정 요약

- **품질 측면**: 한국어 법령 도메인이라는 핵심 도메인에서 Upstage가 OpenAI 대비 우위라는 평가가 일관됨. ai-compliance-guard의 1차 가치(규정 조항 매칭 정확도)와 정렬.
- **사업 측면**: 한국 식약처 규제 대상 기업이 1차 타겟. Upstage가 국내 법인·국내 리전 계약 가능 → 해외 클라우드 거부 고객도 일부 수용 가능. OpenAI는 이 지점에서 제약.
- **운영 측면**: Phase 1은 소수 인력. 옵션 C(BGE-m3 로컬)는 GPU·모델 서빙·인덱스 재빌드 잡을 직접 운영해야 함 → Phase 1에서 채택하면 RAG 파이프라인 자체 진척이 늦어진다. **운영 부담은 Phase 1에서 회피한다.**
- **비유출 요구사항**: STRATEGY.md의 "비유출" 카피는 사업적으로 중요. 단, **모든 고객이 비유출을 요구하지는 않는다**. Phase 1은 API로 시작하고, "비유출 SKU"가 실수요로 잡히는 시점에 옵션 C를 두 번째 구현체로 추가하는 단계적 전략이 합리적.

### 차원 결정: `vector(4096)`

- Phase 1 규모(수만 청크)에서 4096차원의 메모리 부담은 다음과 같다:
  - 10,000 청크 × 4096 × 4 bytes = 약 156 MB (raw) / HNSW 인덱스 포함 약 400~600 MB
  - PostgreSQL 단일 인스턴스 (8~16GB RAM 가정)에서 충분히 운영 가능
- 미래 100만 청크로 확장 시 raw 약 15 GB + 인덱스 → 그 시점에 옵션 C(1024차원) 또는 Matryoshka 차원 축소(Solar 2.x 출시 시) 마이그레이션 검토.

### `EmbeddingPort` 인터페이스로 격리

```java
public interface EmbeddingPort {
    float[] embedQuery(String text);
    List<float[]> embedDocuments(List<String> texts);
    int dimension();
}
```

- 1차 구현체: `UpstageSolarEmbeddingAdapter` (REST, 4096차원)
- 2차 구현체 (Phase 1.5~2): `BgeM3LocalEmbeddingAdapter` (1024차원) — 비유출 고객 SKU용
- 두 구현체는 **다른 차원**이므로 데이터베이스 분리(고객별 schema 또는 별도 인스턴스) + 인덱스 재구축이 전제. 동일 인스턴스에서 동시 운영 불가.

### Exit Strategy

다음 조건 중 하나가 발생하면 임베딩 모델을 교체한다.

1. **비유출 고객 실수요 발생**: 1개 이상의 유료 고객이 "데이터 외부 전송 불가"를 계약 조건으로 요구 → 옵션 C(BGE-m3) 구현체 추가 + 해당 고객 전용 인스턴스 분리.
2. **품질 미달**: EVAL-001 한국어 법령 평가 세트에서 Solar의 Recall@10이 70% 미만이고, 원인이 임베딩 품질로 분석됨 → OpenAI `text-embedding-3-large`로 A/B 테스트 후 교체 검토.
3. **벤더 리스크**: Upstage API SLA 사고, 가격 급변, 서비스 단종 등 → 옵션 A 또는 C로 즉시 폴백 (`EmbeddingPort` 인터페이스 덕분에 코드 변경 최소).
4. **차원 비용 임계**: 청크 100만 건 초과로 인덱스가 PostgreSQL 단일 인스턴스 메모리를 압박 → BGE-m3(1024) 또는 Solar Matryoshka 차원 축소로 마이그레이션.

---

## 기술 영향

### 1. 벡터 저장소 차원 (V2 마이그레이션)

- **현재**: Flyway `V1__init_schema.sql`에 `embedding vector(1536)` (Phase 0 임시값)
- **변경 필요**: `V2__embedding_dimension_upstage.sql` 생성
  ```sql
  -- ADR-003에 따라 Upstage solar-embedding (4096차원)으로 확정
  ALTER TABLE regulations
      ALTER COLUMN embedding TYPE vector(4096);
  -- 벡터 인덱스는 RAG-002 임베딩 적재 완료 후 별도 마이그레이션(V3)에서 추가
  -- CREATE INDEX idx_regulations_embedding_hnsw
  --     ON regulations USING hnsw (embedding vector_cosine_ops);
  ```
- V1을 직접 수정하지 않고 V2를 추가하는 이유: Phase 0 검증이 V1 기준으로 통과되었고, Flyway는 적용된 마이그레이션 수정을 금지함. V2로 누적해 적용한다.

### 2. API 비용 (Phase 1 + Phase 2 예상)

| 항목 | 수량 | 토큰 | Upstage 예상 비용 |
|------|------|------|-------------------|
| Phase 1 규정 1회 적재 | 10,000 청크 | 800만 토큰 | $5~10 |
| Phase 1 갱신 (분기) | 1,000 청크 | 80만 토큰 | $1 |
| Phase 1 사용자 질의 | 일 1만 건 | 1500만 토큰/월 | $10~20/월 |
| Phase 2 에이전트 중간 임베딩 | 일 5만 건 | 7500만 토큰/월 | $50~100/월 |

- Phase 1 총 비용: **월 $20 이내** (적재 1회 + 질의)
- Phase 2 누적: **월 $100~150** (멀티에이전트 트래픽 가정)
- 비용은 결정에 영향을 줄 만한 규모가 아님. 품질·사업 정합성이 결정 요인.

### 3. 로컬 배포 가능성 (비유출 체계)

- Phase 1: Upstage API 사용 → **현재는 비유출 미지원**. STRATEGY.md의 비유출 카피는 "Phase 1.5 또는 Phase 2의 별도 SKU로 제공"으로 외부 커뮤니케이션 조정 필요.
- Phase 1.5~2: `BgeM3LocalEmbeddingAdapter` 구현 → **비유출 고객은 별도 인스턴스(또는 고객사 온프레미스)** 배포. 동일 차원이 아니므로 인스턴스 분리 필수.
- 운영 전략: "비유출 SKU"는 고객사 VPC 안에 단일 테넌트 배포 + 모델·DB 모두 고객 인프라에 위치. Upstage SKU와 데이터·인프라가 분리됨.

### 4. 모듈·코드 영향

- `:rag` 모듈에 `EmbeddingPort` 인터페이스 추가 (ADR-001 모듈 구조 변경 없음)
- `UpstageSolarEmbeddingAdapter`: WebClient 기반 REST 호출, API 키는 환경 변수로 주입 (`.env.example`에 항목 추가 필요)
- `EmbeddingProperties` 설정 클래스: 모델명·차원·timeout·retry 정책
- 배치 임베딩 잡(RAG-002): Spring Batch 또는 단순 페이지네이션 스크립트. API rate limit 핸들링(지수 백오프 + 동시성 제한) 필수.

---

## Consequences

### 긍정

- 한국어 법령 도메인 품질이 1차 가치와 정렬 → ai-compliance-guard의 차별점 강화.
- 국내 법인 계약 가능 → 한국 B2B 컴플라이언스 수용성 ↑.
- 운영 부담 최소 (API 호출만) → Phase 1 소수 인력으로 RAG 파이프라인 진척에 집중 가능.
- `EmbeddingPort` 인터페이스로 옵션 A/C 교체 비용 최소화.
- Phase 1 비용이 무시 가능 수준 → 비용 리스크 없음.

### 부정 / 대응

- (-) **비유출 핵심 카피와 즉시 정합하지 않음** → 외부 커뮤니케이션을 "Phase 1.5+의 비유출 SKU"로 조정. STRATEGY.md 카피 검토 필요 (별도 액션).
- (-) Vendor lock-in (4096차원, 비대칭 모델) → `EmbeddingPort` 격리 + Exit Strategy 명시.
- (-) Upstage API SLA·가용성이 OpenAI 대비 검증 부족 → 모니터링·폴백 경로 (옵션 A) 사전 준비.
- (-) 차원 4096은 향후 100만 청크 규모에서 메모리 압박 → 그 시점에 차원 축소·교체 마이그레이션 (Exit Strategy 4).
- (-) Phase 0 V1 `vector(1536)` 와 불일치 → V2 마이그레이션으로 명시 정정.

### 향후 결정 영향

- **D4 (Re-ranking)**: 영향 없음. Re-ranker는 임베딩 결과에 의존하지 않고 원문을 다시 평가. 단, Upstage가 자체 reranker(`solar-reranker`)를 제공하므로 같은 벤더로 통일 시 운영 단순화 효과 있음 — D4 결정 시 고려.
- **D5 (LLM 엔진)**: Upstage Solar LLM과 임베딩이 같은 벤더 → 통합 SDK·과금·SLA 통합 가능. 단, LLM은 품질 요구가 더 까다로우므로 D5는 독립 평가.
- **Phase 2 멀티에이전트**: 에이전트별 중간 결과 임베딩도 동일 모델 사용 → 차원·벤더 일관성 유지.
- **STRATEGY.md 카피**: "비유출"을 1차 디폴트로 약속한 부분이 있다면 "Phase 1.5+ 별도 SKU"로 조정 필요 (별도 작업으로 위임).

---

## 팀 리뷰 요약

| 팀원 | 피드백 | 반영 |
|------|--------|------|
| ai-rag-specialist | 한국어 법령 도메인 Recall에서 Solar가 OpenAI 대비 유리. EmbeddingPort 격리로 2차 구현체 교체 비용을 낮춘 부분 동의. | 본문 반영 |
| backend-specialist | `EmbeddingPort` 인터페이스 + WebClient Adapter 구조 좋음. API rate limit·timeout·retry 설정을 `EmbeddingProperties`로 분리할 것. | "모듈·코드 영향"에 반영 |
| database-infra-engineer | V1을 수정하지 말고 V2 마이그레이션으로 차원 변경 처리. HNSW 인덱스는 데이터 적재 후 별도 V3으로 분리하는 것이 안전 (빌드 비용 큼). | "V2 마이그레이션"에 반영 |
| data-engineer | 배치 임베딩 시 청크 토큰 길이 분포를 사전 측정해 Upstage API max token 한도 초과를 방지. RAG-002에서 처리. | RAG-002 작업 노트로 위임 |
| qa-validator | EVAL-001에 "의미 등가 매칭" 카테고리(C4)를 추가해 임베딩 품질을 단독 측정. Solar vs OpenAI A/B는 비용이 미미하므로 평가 세트로 정량 비교 권장. | EVAL-001에 반영 |
| (사업 관점) | "비유출" 카피와 옵션 B 디폴트가 충돌. Phase 1.5+ SKU로 조정하거나 옵션 C 병행 채택을 재논의 필요. | "Consequences 부정"에 명시, 외부 카피 조정은 별도 액션 |

---

## 다음 액션

1. **V2 마이그레이션**: `infra/src/main/resources/db/migration/V2__embedding_dimension_upstage.sql` 작성 → `vector(1536)` → `vector(4096)` 변경.
2. **`:rag` 모듈 인터페이스**: `EmbeddingPort` 인터페이스 + `UpstageSolarEmbeddingAdapter` 구현체 스켈레톤 (RAG-002 작업으로 위임).
3. **환경 변수**: `.env.example`에 `UPSTAGE_API_KEY=`, `UPSTAGE_EMBEDDING_MODEL=solar-embedding-1-large` 추가.
4. **EVAL-001 카테고리 추가**: C4. 의미 등가 매칭 (예: "질병 예방 표방" → 식품표시광고법 제8조 제1항).
5. **A/B 평가 계획**: Phase 1 평가 단계에서 Upstage solar-embedding vs OpenAI text-embedding-3-large 동일 평가 세트로 정량 비교 (비용 < $20). 결과에 따라 본 결정 재검토 가능.
6. **비유출 SKU 로드맵 메모**: STRATEGY.md 카피와 본 결정의 갭을 PM·사업 측에 공유. Phase 1.5+에서 옵션 C(BGE-m3) 구현체 추가 일정 합의.
7. **Upstage 계약 검토**: 국내 리전 보장·zero retention 옵션·API rate limit·SLA를 계약 시점에 확인.

---

## 참고

- 본 결정은 Phase 1 규모(수만 청크, 소수 운영 인력, 1차 타겟 한국 식약처 규제 기업)를 전제로 한다.
- 한국어 법령 도메인 품질이라는 1차 가치, 그리고 운영 부담 최소화라는 Phase 1 제약이 옵션 B를 선택한 결정적 이유다.
- "비유출" 차별점은 옵션 C(BGE-m3)로 Phase 1.5+에서 추가 SKU로 제공 — 본 결정은 그 가능성을 인터페이스 격리로 미리 확보해둔다.
- ADR-001·ADR-002와 같은 단순성·격리·미래 교체 가능성 원칙을 일관 유지했다.

# ADR-005: 임베딩 모델 PoC 채택 — BGE-m3 (Upstage Solar 대안)

## 상태
채택 (2026-06-06)

본 결정은 ADR-003(Upstage Solar)을 **대체하지 않고 보완**한다. Phase 1 PoC 단계에서는 BGE-m3를 사용하고, 운영 환경 Recall@K 평가 결과에 따라 Upstage 전환 여부를 재검토한다.

---

## 배경

ADR-003에서 Upstage Solar-embedding-1-large (4096차원)를 1차 임베딩 모델로 선택했다. 그러나 Phase 1 청킹 완료 후 임베딩 단계 진입 시 다음 요소들이 재검토되었다:

- **외부 API 의존성**: Upstage 계정 가입·키 발급 절차가 PoC 진행을 지연
- **비용 관리**: 임베딩 호출당 과금 → 청킹 알고리즘 튜닝·재임베딩 반복 실험에 부담
- **학습 가치**: 로컬 모델 운영(Python 추론 서비스, Docker 분리, 모델 캐시 관리)이 성장 지향 설계에 부합
- **모델 격리 의도**: `EmbeddingPort` 인터페이스가 처음부터 BGE-m3 옵션을 염두에 두고 설계됨 (ADR-003 §향후 확장 항목 참조)

---

## 결정

**1차 PoC와 Phase 1 평가는 BGE-m3 (`BAAI/bge-m3`, 1024차원)로 진행한다.**

- 임베딩 서비스를 별도 컨테이너(`embedding-server`)로 분리하여 Python FastAPI로 운영
- Spring Boot는 `BgeM3EmbeddingAdapter`로 HTTP 호출
- `EmbeddingPort` 인터페이스는 변경 없음, 어댑터만 추가 (`@ConditionalOnProperty`로 토글)

---

## 근거

### 1. PoC 검증 결과 (2026-06-06)

| 모드 | Recall@10 | MRR |
|---|---|---|
| Dense (BGE-m3) only | **75.0%** | 0.6299 |
| BM25 only (단순 공백 토크나이저) | 33.3% | 0.2927 |
| Hybrid (RRF, 가중치 균등) | 50.0% | 0.3522 |

- Dense 단독으로 Phase 1 게이트 80%에 5%p 근접
- 운영 환경의 ParadeDB BM25 (nori 형태소 분석) + Upstage Solar-reranker 결합 시 80% 달성 경로 명확

### 2. 비용·학습 트레이드오프

| 항목 | Upstage | BGE-m3 |
|---|---|---|
| 초기 비용 | 가입+키발급 (15~20분) | 모델 다운로드 (2.27GB, 자동) |
| 임베딩 1회 | ~15원 | 무료 |
| 반복 실험 | 누적 과금 | 무료 |
| 학습 포인트 | API 운영 | Python 추론, Docker 분리, 모델 캐시 |
| 추론 속도 (CPU) | 빠름 (API) | 2.6 chunks/sec (CPU, 635청크 4분) |

### 3. 아키텍처 정합

- `EmbeddingPort` 인터페이스 격리로 어댑터 교체 비용 낮음
- 임베딩 서비스 분리는 향후 GPU 노드 분리·스케일링·다중 모델 운영의 기반

---

## 결과

### 코드/스키마 변경
- **V4 마이그레이션**: `regulations.embedding` 컬럼 `vector(4096) → vector(1024)`
- **RegulationEntity**: `@Column(columnDefinition = "vector(1024)")`로 변경 + `embeddingModel` 컬럼 신규
- **BgeM3EmbeddingAdapter** 신규 (`@ConditionalOnProperty(name="embedding.provider", havingValue="bge-m3")`)
- **UpstageSolarEmbeddingAdapter** 보존 (`@ConditionalOnProperty(name="embedding.provider", havingValue="upstage")`)
- **application.yml**: `embedding.provider: bge-m3` (로컬 기본값), prod 환경은 별도 결정

### 인프라
- **embedding-server**: Python 3.11 + FastAPI + FlagEmbedding (BGE-m3) 컨테이너
- **docker-compose**: `embedding-server` 서비스 추가, HuggingFace 캐시 볼륨

### 운영
- `BatchEmbeddingService` 호출 시 `embedding.provider` 설정에 따라 어댑터 선택
- `regulations.embedding_model` 컬럼에 사용된 모델명 기록 (감사 추적)

---

## 향후 결정 트리거

다음 중 하나라도 발생 시 본 결정을 재검토:

1. **운영 환경 Hybrid Recall@10 < 80%** 미달 + 청킹·BM25 튜닝 후에도 개선 안 됨
2. **임베딩 서버 운영 부담** 증대 (메모리, GPU, 가용성)
3. **한국어 법령 특화 모델** 출시 (예: KURE-v1 후속, 솔트룩스·SKT 한국어 임베딩 등)
4. **B2C 토스인앱 채널 트래픽 급증**으로 추론 비용·지연 압박 (STRATEGY §2)

재검토 시 옵션:
- a. Upstage Solar로 전환 (어댑터 교체)
- b. BGE-m3 self-host GPU 가속 (vLLM, ONNX Runtime)
- c. 한국어 특화 모델 평가

---

## 관련 문서

- ADR-003 (Upstage Solar 1차 선택, 보존)
- `_workspace/CHUNKING_REPORT.md` §9-1 (짧은 청크 시맨틱 매칭 한계)
- `_workspace/evaluation_result.json` (PoC 평가 데이터)
- `_workspace/PHASE1_PROGRESS.md` 2026-06-06 갱신

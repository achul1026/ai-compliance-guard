# ADR-002: 한국어 BM25 검색 엔진 선택 (D2)

- 상태: **확정 (Accepted)**
- 결정자: architect (팀 리뷰 반영)
- 결정일: 2026-05-26
- Phase: 1
- 관련 결정: D3 (임베딩 모델), D4 (Re-ranking), ADR-001 (모듈 구조 — `:rag` 모듈에 격리)

---

## Context

ai-compliance-guard의 Hybrid RAG 파이프라인에서 "키워드 검색(BM25)" 역할을 담당할 엔진을 선택해야 한다. 임베딩 기반 의미 검색만으로는 다음 케이스를 안정적으로 처리할 수 없다.

### 한국어 법령 검색의 고유 요구사항

1. **조항 번호 정확 매칭**: `"식품표시광고법 제8조 제1항 제2호"` 같은 질의에서 조(條)·항(項)·호(號) 단위가 1:1로 매칭되어야 한다. 임베딩은 "제8조"와 "제9조"를 의미상 유사하게 보는 경향이 있다 → BM25의 정확 토큰 일치가 결정적.
2. **법령 고유명사 매칭**: `"식품표시광고법"`, `"건강기능식품에 관한 법률"`, `"화장품법"`, `"부당한 표시·광고 행위의 유형 및 기준 지정 고시"` 등 긴 고유명사를 분절 없이 매칭해야 한다.
3. **한국어 형태소 분석**: 조사·어미 변형(`"식품의약품안전처가"` ↔ `"식품의약품안전처"`)을 정규화해야 검색 누락이 줄어든다. PostgreSQL 기본 `to_tsvector('simple')`은 어절 단위라 한국어 처리가 빈약하고, 진정한 BM25가 아니라 ts_rank(tf-idf 변형) 점수다.
4. **복합 명사 분해**: `"건강기능식품"` ↔ `"건강 기능 식품"` 같이 띄어쓰기가 자유로운 한국어 입력을 동일 토큰으로 다뤄야 한다.
5. **Spring Boot 통합**: ADR-001에 따라 `:rag` 모듈(Java 17 라이브러리)에서 호출 가능해야 한다. JDBC/JPA 또는 HTTP 클라이언트로 접근 가능한 옵션이 선호된다.

### Phase 1 규모 가정

- 규정 청크: 초기 수천~수만 건 (식품표시광고법 + 건강기능식품법 + 화장품법 + 식약처 고시 + 협회 자율심의)
- 1차 사용자 트래픽: 베타~소수 B2B (수십 QPS 미만)
- 운영 인력: 소수 (별도 인프라 운영팀 없음)

---

## 후보 비교

### 옵션 A — ParadeDB (`pg_search` 확장)

PostgreSQL 확장으로 Tantivy(Rust Lucene) 기반 BM25를 PostgreSQL 안에서 제공.

- **한국어 처리**: Tantivy의 `cjk` 토크나이저 또는 ngram 토크나이저로 한국어 대응 가능. 단, 본격적인 형태소 분석(은전한닢/노리 등)은 기본 제공 안 됨. 조사·어미 정규화는 약함.
- **조항 번호 매칭**: BM25 정식 구현이라 `"제8조"` 토큰 일치 시 정확. ngram 토크나이저로 부분 매칭도 가능.
- **고유명사**: ngram·키워드 필드 조합으로 대응 가능. 형태소 분석기 부재로 긴 복합 고유명사의 분절 품질은 제한적.
- **인프라**: pgvector와 같은 PostgreSQL에 확장으로 설치 → 별도 클러스터·네트워크 hop 불필요. Docker Compose에 ParadeDB 이미지 한 줄 추가로 끝.
- **Spring 통합**: JPA/JDBC로 SQL 한 줄 → `:rag` 모듈에서 기존 `:infra` DataSource 그대로 재사용.
- **비용**: 추가 인프라 비용 0. 운영 비용 사실상 0 (PostgreSQL과 동일 라이프사이클).
- **리스크**: 한국어 형태소 분석 품질이 nori 대비 약함. 본격적인 한국어 NLP 검색 품질이 필요해지면 한계.

### 옵션 B — OpenSearch + nori (Elasticsearch fork)

분산 검색엔진. 한글 형태소 분석기 `nori` 공식 지원.

- **한국어 처리**: nori는 mecab-ko-dic 기반의 한국어 형태소 분석기로 조사·어미 정규화, 복합명사 분해가 가장 잘 됨. `"건강기능식품"` → `["건강", "기능", "식품", "건강기능식품"]` 분해 가능.
- **조항 번호 매칭**: BM25 정식 구현. `keyword` 필드로 정확 매칭, `text` 필드(nori)로 형태소 매칭을 동시 인덱싱 가능.
- **고유명사**: nori user dictionary에 법령명을 등록하면 `"식품등의표시·광고에관한법률"` 같이 띄어쓰기 변형을 안정적으로 흡수.
- **인프라**: **별도 클러스터 필요**. 최소 1노드 운영해도 메모리 2~4GB 추가, JVM 튜닝, 인덱스 백업·복구·매핑 마이그레이션이 별도 운영 부담.
- **Spring 통합**: `spring-data-elasticsearch`(OpenSearch 호환 클라이언트) 또는 OpenSearch Java Client. 동기화(DB ↔ OpenSearch) 파이프라인을 추가로 운영해야 함 → 규정 갱신 시 양쪽 갱신 정합성 보장 필요.
- **비용**: 자체 호스팅 시 인스턴스 비용(t3.small~medium 수준 + EBS) + 운영 시간. AWS OpenSearch Service 사용 시 월 $20~50+ 추가.
- **리스크**: Phase 1 규모(수만 청크, 수십 QPS)에 비해 명백한 오버엔지니어링. **이중 저장소(PG + OpenSearch)** 운영 정합성 이슈(규정 버전 메타데이터 동기화). OPERATIONS.md의 "갱신 파이프라인 → 임베딩 재생성" 단계가 OpenSearch 재색인까지 포함되어 운영 복잡도 증가.

### 옵션 C — Lucene (애플리케이션 내 임베드)

Java Lucene 라이브러리를 `:rag` 모듈에 직접 의존성으로 추가, 인덱스를 로컬 파일시스템에 저장.

- **한국어 처리**: `lucene-analyzers-nori`로 nori 형태소 분석 사용 가능. OpenSearch와 동일 품질.
- **조항 번호 매칭**: BM25 원조 구현. 정확.
- **고유명사**: nori user dictionary 등록 가능.
- **인프라**: 별도 서버 불필요. JVM 메모리 안에서 동작.
- **Spring 통합**: 직접 IndexWriter/IndexSearcher 관리 코드를 작성해야 함. 트랜잭션·동시성·인덱스 락 관리가 PostgreSQL이 알아서 해주던 부분을 직접 작성해야 함. 멀티 인스턴스 배포 시 인덱스 파일 공유(EFS/S3) 또는 인스턴스별 인덱스 일관성 문제 발생.
- **비용**: 인프라 비용 0. 그러나 **개발·운영 코드 비용이 가장 크다** (인덱싱 잡, 락, 백업, 멀티 인스턴스 동기화).
- **리스크**: Phase 3에서 멀티 인스턴스 배포(K8s 등)로 확장 시 인덱스 공유 전략을 다시 설계해야 함. ADR-001의 "단순성 우선" 원칙에 정면으로 어긋남.

---

## 비교 매트릭스

| 기준 | A. ParadeDB | B. OpenSearch+nori | C. Lucene 임베드 |
|------|-------------|---------------------|------------------|
| 조항 번호 정확 매칭 | ⭕ 우수 (BM25 정식) | ⭕ 우수 | ⭕ 우수 |
| 한국어 형태소 분석 | △ 보통 (cjk/ngram) | ◎ 최상 (nori) | ◎ 최상 (nori) |
| 고유명사 user dict | △ 제한적 | ◎ 강력 | ◎ 강력 |
| 인프라 복잡도 | ⭕ PG에 통합 (가장 단순) | ✕ 별도 클러스터 | △ 코드 부담 큼 |
| Spring/JPA 통합 | ⭕ SQL 한 줄 | △ 별도 클라이언트 | ✕ 직접 구현 |
| 이중 저장소 정합성 | ⭕ 단일 PG | ✕ DB ↔ OS 동기화 | △ 인덱스 파일 별도 |
| 인프라 비용 (월) | $0 | $20~50+ | $0 |
| 운영 비용 (시간) | 거의 0 | 큼 (튜닝·백업·매핑) | 중 (인덱스 관리 코드) |
| Phase 1 적합성 | ◎ | △ 오버엔지니어링 | ✕ 부적합 |
| 미래 확장성 (수백만 청크) | △ Tantivy 한계 가능 | ◎ 수평 확장 용이 | △ 분산 어려움 |

---

## Decision

**옵션 A — ParadeDB (`pg_search` 확장) 을 선택한다.**

### 결정 근거

1. **Phase 1 규모에 맞는 단순성**: 수만 청크 + 수십 QPS 규모에서 ParadeDB의 Tantivy BM25는 충분한 성능과 정확도를 제공한다. OpenSearch는 오버엔지니어링.
2. **단일 저장소 = 단일 진실 원천**: 규정 청크 본문·메타데이터·임베딩(pgvector)·BM25 인덱스가 같은 PostgreSQL 안에 있다 → OPERATIONS.md의 "갱신 파이프라인"이 단일 트랜잭션으로 정합성 보장 가능. 이중 저장소 동기화 운영 부담이 없다.
3. **ADR-001 모듈 구조와 정합**: `:rag` 모듈이 `:infra`의 PostgreSQL DataSource를 그대로 재사용 → 추가 클라이언트·설정 모듈 분기 불필요.
4. **소수 운영 인력에 적합**: OpenSearch는 인덱스 매핑 관리·JVM 튜닝·백업·복구·버전 업그레이드가 별도 운영 영역. ParadeDB는 PostgreSQL과 라이프사이클을 공유한다.
5. **CLAUDE.md 단순성 원칙**: "200줄을 50줄로 줄일 수 있다면 다시 작성한다" — OpenSearch 채택 시 동기화 잡·매핑 관리 코드가 추가되는 반면, ParadeDB는 SQL 한 줄로 끝난다.

### 한국어 처리 한계 보완 전략

ParadeDB의 한국어 형태소 분석 약점은 다음 3단계 방어선으로 보완한다.

1. **하이브리드 1: BM25 + Vector**: 형태소 분석 한계로 인한 누락은 의미 검색(pgvector)이 보완 → Phase 1-7의 RRF(Reciprocal Rank Fusion)로 결합.
2. **하이브리드 2: 메타데이터 필터 + 정확 매칭 부스팅**: 조항 번호(`article_no`)·법령명(`law_name`)을 별도 컬럼으로 추출해두고, 질의에서 `"제8조"`·`"식품표시광고법"` 패턴이 감지되면 메타데이터 필터로 부스팅. 형태소 분석 의존도를 줄임.
3. **하이브리드 3: Re-ranking (D4)**: 최종 단계의 Re-ranker(예: bge-reranker, Cohere Rerank multilingual)가 한국어 의미 정렬을 다시 한 번 보정.

이 3단계로 nori 부재로 인한 손실을 운영 가능한 수준으로 흡수한다.

### 임계점에서의 마이그레이션 경로 (Exit Strategy)

다음 조건 중 하나가 발생하면 옵션 B(OpenSearch+nori)로 마이그레이션을 재검토한다.

- 청크 규모 100만 건 초과 또는 인덱스 크기 50GB 초과
- BM25 P95 응답시간 > 500ms (Phase 2~3 사용자 트래픽 증가 시)
- 평가 세트(EVAL-001) Recall@10이 80% 미만이며 원인이 형태소 분석 한계로 분석됨
- B2B 엔터프라이즈 검색 SLA 요구사항(예: 99.9% 가용성, 다중 리전) 발생

마이그레이션은 `:rag` 모듈 내부의 `KeywordSearchPort` 인터페이스 뒤에 구현체를 교체하는 형태로 진행한다(아래 "다음 액션" 참고).

---

## Consequences

### 긍정

- Phase 1을 빠르게 진행 가능: 별도 검색엔진 부트스트랩·튜닝 시간 절약
- 단일 저장소 운영 → 백업·복구·트랜잭션·규정 버전 정합성이 PostgreSQL의 ACID로 보장됨
- ADR-001 모듈 구조 변경 없음 (`:rag` → `:infra` 의존성만으로 충분)
- 인프라 비용 추가 $0
- Phase 0의 Docker Compose에 ParadeDB 이미지로 교체만 하면 됨 (pgvector 포함 이미지 제공)

### 부정 / 대응

- (-) 한국어 형태소 분석이 nori 대비 약함 → 위 "3단계 방어선"으로 보완, 평가 세트로 정량 검증
- (-) Tantivy는 수평 확장이 어려움 → Phase 1~2 규모에서는 문제 없음. 임계점 도달 시 Exit Strategy 가동
- (-) ParadeDB는 비교적 신생 프로젝트(Tantivy는 안정적, `pg_search` 확장은 발전 중) → Lock-in 회피를 위해 `KeywordSearchPort` 인터페이스로 격리
- (-) PostgreSQL 메이저 버전 업그레이드 시 ParadeDB 호환 버전 확인 필요 → 운영 체크리스트에 추가

### 향후 결정 영향

- **D3 (임베딩 모델)**: 영향 없음. ParadeDB와 pgvector는 같은 PostgreSQL에서 독립적으로 동작.
- **D4 (Re-ranking)**: ParadeDB BM25 + pgvector 결과를 결합한 뒤 Re-ranker에 전달하는 구조 유지.
- **Phase 3 멀티 인스턴스 배포**: PostgreSQL 자체가 단일 인스턴스(or 매니지드 RDS)이므로 앱 인스턴스 수와 무관. 옵션 C 대비 큰 장점.

---

## 팀 리뷰 요약

| 팀원 | 피드백 | 반영 |
|------|--------|------|
| ai-rag-specialist | 형태소 분석 약점을 3단계 방어선(BM25+Vector+메타데이터 부스팅+Reranker)으로 흡수 가능. 동의. | 반영 (본문 "한국어 처리 한계 보완 전략") |
| database-infra-engineer | ParadeDB Docker 이미지에 pgvector 동봉됨 확인. 기존 `docker-compose.yml`의 PostgreSQL 이미지를 교체하면 됨. Flyway는 그대로 사용 가능. | 반영 (아래 "다음 액션") |
| backend-specialist | `:rag` 모듈에 `KeywordSearchPort` 인터페이스를 두고 `ParadeDbKeywordSearchAdapter` 구현체를 두는 헥사고날 구조 → 미래 교체 비용 최소화. | 반영 |
| data-engineer | 규정 청크 적재 시 `article_no`·`law_name` 메타데이터 컬럼을 별도로 추출해두는 청킹 전략을 P1-2에서 적용. | DATA-002에서 처리 |
| qa-validator | EVAL-001 평가 세트에 "조항 번호 정확 매칭", "법령명 정확 매칭" 카테고리를 별도로 두어 BM25 성능을 단독 측정 가능하게. | EVAL-001 작업에 반영 |

---

## 다음 액션

1. **RAG-001 (DB 스키마)**: `regulation_chunks` 테이블에 다음 컬럼을 정의
   - `content TEXT` (BM25 인덱싱 대상)
   - `law_name VARCHAR`, `article_no VARCHAR`, `paragraph_no VARCHAR`, `item_no VARCHAR` (메타데이터 부스팅용)
   - `effective_date DATE`, `version VARCHAR` (OPERATIONS.md 규정 버전 추적용)
   - `embedding VECTOR(N)` (pgvector, N은 D3 결정 후 확정)
   - ParadeDB BM25 인덱스: `CREATE INDEX ... USING bm25 (content)`
2. **인프라**: `docker-compose.yml`의 PostgreSQL 이미지를 `paradedb/paradedb:latest`로 교체 (pgvector 동봉). Flyway 마이그레이션은 기존 그대로 사용.
3. **`:rag` 모듈 인터페이스 설계**:
   ```java
   public interface KeywordSearchPort {
       List<ChunkHit> search(String query, SearchOptions options);
   }
   ```
   구현체: `ParadeDbKeywordSearchAdapter` (JDBC). 향후 OpenSearch 마이그레이션 시 `OpenSearchKeywordAdapter` 추가만으로 교체 가능.
4. **메타데이터 부스팅 룰**: 질의에서 `제\d+조`, `제\d+항`, `제\d+호`, `[가-힣]+법(률)?` 패턴이 감지되면 메타데이터 컬럼 정확 매칭을 BM25 점수에 가중.
5. **평가 카테고리 (EVAL-001)**:
   - C1. 조항 번호 정확 매칭 (예: `"식품표시광고법 제8조"`)
   - C2. 법령명 정확 매칭 (예: `"건강기능식품에 관한 법률"`)
   - C3. 일반 자연어 질의 (예: `"질병 치료 효능을 표방하면 안 되는 이유"`)
   각 카테고리별 Recall@10, MRR 측정.
6. **ParadeDB 버전 핀**: 운영 안정성을 위해 이미지 태그를 메이저.마이너로 고정 (예: `paradedb/paradedb:0.x.y`).

---

## 참고

- 본 결정은 Phase 1 규모를 전제로 한다. 임계점 도달 시 Exit Strategy에 따라 OpenSearch로 재검토.
- ADR-001과 마찬가지로 단순성·격리·미래 교체 가능성을 우선했다.
- 한국어 형태소 분석 품질이 BM25 단독 정확도에 결정적이라면 옵션 B가 더 우월하나, Hybrid RAG + Re-ranker 조합에서는 ParadeDB의 한계를 시스템 레벨에서 흡수 가능하다는 판단이다.

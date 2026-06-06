# RAG-005: 검색 API 통합 테스트 결과

- 작업: Task #11 / P1-8 / RAG-005
- 담당: Backend Specialist
- 일자: 2026-05-28
- 엔드포인트: `POST /api/v1/search`  (Spring `server.servlet.context-path=/api/v1` + 컨트롤러 매핑 `/search`)

## 1. 구현 요약

| 항목 | 파일 |
|------|------|
| 컨트롤러 | `api/src/main/java/com/achul/compliance/api/search/SearchController.java` |
| 요청 DTO | `api/src/main/java/com/achul/compliance/api/search/SearchRequest.java` |
| 응답 DTO | `api/src/main/java/com/achul/compliance/api/search/SearchResponse.java` |
| 결과 항목 DTO | `api/src/main/java/com/achul/compliance/api/search/SearchResultDto.java` |
| 통합 테스트 | `api/src/test/java/com/achul/compliance/api/search/SearchControllerTest.java` |

### 처리 흐름
1. `@Valid @RequestBody SearchRequest`로 요청 파싱·검증 (`query` 필수, `top_k` 1~20)
2. `HybridSearchService.search(query, topK)` 호출
   - 내부적으로 쿼리 임베딩 (Upstage Solar) → 벡터 검색 (pgvector) + BM25 (ParadeDB) → 가중합 정규화 (벡터 0.6 / 키워드 0.4) → Re-ranking (Upstage Solar Reranker)
3. `RerankedResult` → `SearchResultDto` 매핑
   - `law`: 벡터 결과가 있으면 그쪽 lawName, 없으면 키워드 결과
   - `article`: 조·항·호를 공백 결합 (null 항목은 생략)
   - `text`: 청크 본문
   - `relevance_score`: Reranker 점수 (폴백 시 하이브리드 점수)
4. `execution_time_ms` 측정·반환

### 요청/응답 예시

요청
```json
POST /api/v1/search
{
  "query": "이 상품은 당뇨병을 예방합니다",
  "top_k": 10
}
```

응답
```json
{
  "query": "이 상품은 당뇨병을 예방합니다",
  "results": [
    {
      "law": "식품 등의 표시·광고에 관한 법률",
      "article": "제8조 제1항 제1호",
      "text": "질병의 예방·치료에 효능이 있는 것으로 인식할 우려가 있는 표시·광고",
      "relevance_score": 0.93
    },
    {
      "law": "건강기능식품에 관한 법률",
      "article": "제18조 제1항",
      "text": "허위·과대광고 금지 조항",
      "relevance_score": 0.81
    }
  ],
  "execution_time_ms": 247
}
```

## 2. 테스트 결과

명령: `./gradlew :api:test --tests "com.achul.compliance.api.search.SearchControllerTest"`

| # | 케이스 | 상태 | 시간(s) |
|---|--------|------|---------|
| 1 | 샘플 쿼리 1: 식품 광고 컴플라이언스 — JSON 형식 검증 & 500ms 미만 | PASS | 0.009 |
| 2 | 샘플 쿼리 2: 화장품 광고 — 키워드 결과만 있는 경우 메타데이터 매핑 | PASS | 0.084 |
| 3 | 샘플 쿼리 3: 의료기기 광고 — top_k 미지정 시 기본값 10 사용 | PASS | 0.010 |
| 4 | 빈 쿼리는 400 Bad Request | PASS | 0.006 |
| 5 | top_k 범위 초과(>20)는 400 Bad Request | PASS | 0.029 |
| 6 | 결과 0건도 정상 응답(빈 배열) | PASS | 0.327 |

**총 6/6 통과, 전체 0.476s.**

테스트 슬라이스: `@WebMvcTest(SearchController.class)` + `@MockBean HybridSearchService` — 외부 API/DB 없이 컨트롤러 계약(요청 파싱, 응답 매핑, 검증)을 검증한다. 임베딩·벡터 검색·BM25·리랭킹 단위 검증은 `rag` 모듈의 `HybridSearchServiceTest`에서 별도로 수행한다.

## 3. 성능 지표

mock 환경(외부 호출 없음) 기준 컨트롤러 내부 처리 시간(서버 측 측정 `execution_time_ms`):

| 케이스 | 결과 건수 | execution_time_ms |
|--------|-----------|-------------------|
| 샘플 1 (식품) | 2 | 1 |
| 샘플 2 (화장품) | 1 | 1 |
| 샘플 3 (의료기기) | 3 | 1 |
| 결과 없음 | 0 | 4 |

목표 P95 < 500ms 대비 매우 여유. 실제 환경(임베딩 API, pgvector, BM25, 리랭커 포함) 부하 테스트는 EVAL-002 (Recall@K 및 latency 측정)에서 수행한다.

## 4. 검증된 응답 계약

- 최상위 필드: `query` (string), `results` (array), `execution_time_ms` (number)
- 결과 항목 필드: `law`, `article`, `text`, `relevance_score`
- 요청 필드명 별칭: `top_k` (snake_case) / `topK` (camelCase) 모두 수용 (`@JsonAlias`)
- 422 대신 400: Bean Validation 실패는 `MethodArgumentNotValidException`으로 400 반환

## 5. 빌드 검증

| 명령 | 결과 |
|------|------|
| `./gradlew :api:compileJava :api:compileTestJava` | BUILD SUCCESSFUL |
| `./gradlew :api:test --tests "...SearchControllerTest"` | BUILD SUCCESSFUL (6/6 통과) |
| `./gradlew build` | **FAILED — RAG-005 무관 기존 회귀** |

### `./gradlew build` 실패 원인 (RAG-005 범위 밖)

`ApplicationTests#contextLoads`가 실패한다. 사유는 RAG-002 작업의 `EmbeddingAdminController`가 `RegulationRepository`(infra JPA) 빈을 요구하지만, `application.yml`이 기본 프로파일에서 DataSource/JPA/Flyway autoconfigure를 제외하기 때문이다.

```
Caused by: NoSuchBeanDefinitionException:
  No qualifying bean of type 'com.achul.compliance.infra.persistence.repository.RegulationRepository'
```

검증: `git stash`로 RAG-005 변경을 잠시 빼고 `:api:test --tests ApplicationTests`를 실행해도 동일 실패. 즉 본 작업이 도입한 회귀가 아니다.

권장 후속 조치 (별도 태스크): `EmbeddingAdminController`에 `@Profile("local")` 등을 부여하거나 테스트 전용 프로파일(`local`)을 명시적으로 활성화하도록 `ApplicationTests`를 수정.

## 6. 산출물 체크리스트

- [x] `SearchController.java` — `POST /api/v1/search`
- [x] `SearchRequest.java` — `query` + `top_k` (Bean Validation: `@NotBlank`, `@Min(1)`, `@Max(20)`)
- [x] `SearchResponse.java` — `query`, `results[]`, `execution_time_ms`
- [x] `SearchResultDto.java` — `law`, `article`, `text`, `relevance_score`
- [x] `SearchControllerTest.java` — 6개 케이스 (샘플 쿼리 3개 + 검증 + 빈 결과)
- [x] 응답 시간 < 500ms 검증 어서션 포함
- [x] JSON 응답 형식 검증 (`jsonPath`)
- [x] `_workspace/api_integration_test.md`

# AI-001: LangChain4j vs LangGraph 사전 검토 메모

- 작성자: ai-rag-specialist
- 작성일: 2026-05-26
- 상태: **사전 검토 (Pre-review)** — 최종 결정(D6)은 Phase 2에서 확정
- 목적: Phase 0 시점에 Phase 2 D6 결정의 입력을 마련하고, `:agent` 모듈 의존성 경계를 검증

---

## 1. 후보 요약

| 항목 | LangChain4j | LangGraph |
|------|-------------|-----------|
| 언어 | Java/Kotlin 네이티브 | Python 네이티브 (Java 포팅 없음) |
| Spring Boot 통합 | 공식 starter 제공 (`langchain4j-spring-boot-starter`) | 별도 프로세스 분리 필요 (REST/gRPC/Sidecar) |
| Stateful 워크플로우 | AiServices + Tools, 최근 Workflow API 추가 중 | 그래프 기반 상태 머신이 1급 시민 |
| 멀티에이전트 루프 제어 | 가능하나 그래프 추상화는 약함 | 핵심 강점 (조건부 엣지, 체크포인트, 인터럽트) |
| 한국어 LLM 어댑터 | OpenAI/Anthropic/Azure 등 메이저는 모두 지원. Upstage Solar는 OpenAI 호환 모드로 통합 가능 | 동일 (Python LangChain 통해) |
| 운영 단순성 | 단일 JVM 프로세스 | Java ↔ Python 양 프로세스 운영·배포·관측 |
| 커뮤니티 (2026 기준) | 활발히 성장, Java 진영의 사실상 표준 | LangChain 생태 전체 모멘텀 |

---

## 2. 우리 프로젝트 관점 평가

### 강점/약점 매핑

**LangChain4j 선택 시**
- (+) `:agent` 모듈에 단일 의존성 추가만으로 끝남
- (+) Spring Boot, JPA, Flyway와 같은 JVM 인프라 그대로 활용
- (+) 운영 단순 (단일 컨테이너)
- (-) Auditor → Critique → Remediator의 피드백 루프를 직접 코드로 구현해야 함 (조건부 재호출, 상태 저장, 최대 반복 횟수 등)
- (-) 그래프 표현이 코드 수준 (시각화는 별도)

**LangGraph 선택 시**
- (+) 피드백 루프, 체크포인트, 인터럽트가 프레임워크 기본 제공
- (+) 그래프 정의가 명시적이라 디버깅·시각화에 유리
- (-) Python 사이드카가 필요 → 배포 토폴로지 복잡 (FastAPI 컨테이너 + Spring Boot 컨테이너)
- (-) Java ↔ Python 직렬화 비용 + 두 언어 동시 유지
- (-) Phase 3 보안(데이터 마스킹, 학습 거부 옵션)을 두 곳에서 관리

### 우리의 특수 요구
- **Hybrid RAG는 `:rag` 모듈 (Java)**에서 처리. 에이전트가 RAG 결과를 받아 LLM 호출
- **피드백 루프**: Critique가 환각 감지 시 Auditor로 되돌리는 순환 (최대 N회)
- **비용 의식**: 루프 횟수 폭주 방지가 핵심

---

## 3. 잠정 권고 (Phase 2에서 재검토)

**1순위: LangChain4j 단독**

근거:
1. Java/Spring Boot 단일 스택 유지가 운영·보안·관측에서 결정적 우위
2. 피드백 루프는 상대적으로 작은 상태 머신(노드 3개) 수준이라 직접 구현 가능
3. Phase 3 보안(LLM 호출 마스킹, `data_collection: false`)을 한 곳에서 관리
4. LangChain4j의 Workflow/Agent API가 Phase 2 착수 시점에 충분히 성숙할 것

**2순위 (보류 옵션): LangChain4j + Java 자체 그래프 라이브러리**

- LangChain4j로 LLM 호출만 추상화하고, 상태 머신은 직접 작성 (state pattern 또는 Spring StateMachine)
- 그래프 의존성을 외부에 두지 않음

**미권고: LangGraph 사이드카**

- Phase 1~2 규모에서 두 언어 운영 비용이 가치보다 큼
- 단, Phase 3 이후 에이전트 그래프가 10+ 노드로 커지고 시각화·체크포인트가 결정적이라면 재고

---

## 4. Phase 0 모듈 경계 검증

D6가 어떤 방향으로 결정되더라도 `:agent` 모듈 경계 안에서 흡수되어야 한다. 현재 ADR-001 의존성 규칙:

```
:api → :agent → :rag → :infra → :common
```

- LangChain4j 선택: `:agent/build.gradle.kts`에 의존성 추가 → 영향 격리됨
- LangGraph 선택: `:agent`가 외부 Python 서비스 REST 클라이언트를 보유 → 영향 격리됨

**결론**: 두 옵션 모두 ADR-001 구조와 양립 가능. Phase 0 모듈 결정에는 영향 없음.

---

## 5. Phase 2 진입 시 결정 입력

- 본 메모를 D6 ADR(예정) 입력으로 사용
- 결정 시점에 추가 평가 필요 항목:
  - LangChain4j Workflow API 성숙도 (당시 최신 버전 기준)
  - Auditor/Critique/Remediator 상태 머신 복잡도 추정
  - 실측 LLM 비용 (루프 평균 반복 횟수)

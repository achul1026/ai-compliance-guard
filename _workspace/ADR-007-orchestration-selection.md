# ADR-007: 에이전트 오케스트레이션 선택 (D6)

## 상태
채택 (2026-06-13) — 사용자 승인 완료

## 문제

검사관(Auditor) → 비판관(Critique) → 교정관(Remediator) 3-에이전트 파이프라인 + "비판관이 오류 감지 시 검사관으로 되돌아가는" 순환 루프(최대 반복 제한)를 구현해야 한다. ROADMAP 원안 후보: LangChain4j vs LangGraph.

## 후보 비교

| 축 | LangChain4j + 자체 상태머신 | langgraph4j (커뮤니티) | LangGraph (Python 사이드카) |
|---|---|---|---|
| Java 정합 | **네이티브** | 네이티브 (단, 커뮤니티 프로젝트) | 별도 Python 서비스 + HTTP — 폴리글랏 운영 부담 |
| 순환 루프 구현 | while 루프 + 상태 객체 (~50줄) | 그래프 DSL 제공 | 그래프 DSL 제공 (가장 성숙) |
| 의존성 리스크 | 낮음 (공식 프로젝트) | 중간 (메인테이너 소수) | 낮음 (프레임워크 자체는 성숙) |
| 운영 복잡도 | **최소** (단일 JVM) | 최소 | +1 컨테이너, +1 언어, 배포 2배 |
| 학습 가치 | 상태머신 직접 설계 = 면접 소재 | 프레임워크 사용법 | LangGraph 자체는 업계 표준이나 본 스택과 이질 |
| 속도 (개발) | **가장 빠름** | 학습 곡선 있음 | 가장 느림 |

## 핵심 판단

우리의 워크플로우는 **고정된 3단계 + 단일 피드백 엣지 + 반복 상한**이다. 동적 분기, 병렬 노드, 체크포인트 재개 같은 그래프 프레임워크의 본령이 필요한 복잡도가 아니다. "일회성 구조를 위해 추상화 계층을 만들지 않는다"(단순성 우선 원칙)에 따라 프레임워크 도입은 과잉이다.

```
AuditState { adCopy, searchContext, auditReport, critiqueResult, iteration }

while (iteration < MAX_ITERATIONS) {
    auditReport = auditor.run(state);
    critique = critic.run(state);
    if (critique.passed()) break;
    state.feedback = critique.issues();   // 피드백 루프
}
remediation = remediator.run(state);
```

이게 전부다. LangChain4j는 **LLM 호출 계층**(ChatModel 추상화, 구조화 출력, 재시도, Gemini 모듈)만 담당하고, 오케스트레이션은 위 평이한 Java로 직접 작성한다.

## 결정 (제안)

**LangChain4j (LLM 클라이언트 계층) + 자체 상태머신 (오케스트레이션, :agent 모듈).**

- `langchain4j-google-ai-gemini` 모듈로 ADR-006과 직결
- 에이전트별 프롬프트는 리소스 파일로 분리, 각 에이전트는 `AgentStep` 인터페이스 구현체
- 순환 루프·반복 상한·상태 전이는 직접 작성한 `AuditWorkflow` 클래스 (P2-5)
- langgraph4j는 워크플로우가 실제로 복잡해지는 시점(병렬 노드, 휴먼인더루프 등)에 재평가 — 그 전까지 도입하지 않음

## 학습 정리본 연결

이 결정은 위키 정리 소재가 풍부함: "프레임워크 없이 상태머신 직접 설계", "LangGraph가 해결하는 문제와 필요 없는 경우", "구조화 출력으로 에이전트 간 계약 설계". Phase 2 회고에서 [[AI 에이전트와 RAG 학습 지도]]에 연결 예정.

## 검증 기준
- P2-5에서 의도적으로 잘못된 근거를 심으면 Critique가 감지 → Auditor 재실행 → MAX_ITERATIONS에서 안전 종료

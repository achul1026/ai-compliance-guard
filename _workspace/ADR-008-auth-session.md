# ADR-008: 인증/세션 아키텍처 (P3-1)

## 상태
채택 (2026-06-13) — 사용자 승인 완료. Refresh token 도입(이중 토큰) 결정 반영.

## 전제
- 자체 이메일+비밀번호 인증 (사용자 확정). 외부 OAuth는 앱인토스 WebView 호환성 때문에 배제.
- 수익 전 비용 0. 속도 우선. 단, **인증은 보안 영역이라 "바퀴 재발명 금지"가 단순성 원칙보다 우선** — 검증된 프레임워크를 쓴다.

## 결정

### 1. Spring Security + BCrypt
직접 필터를 짜지 않고 Spring Security를 도입한다.
- **이유**: 비밀번호 해싱(BCrypt), 타이밍 공격 방어, 필터 체인, CSRF 처리가 검증돼 있음. 인증 자작은 보안 구멍의 주원인.
- **학습 가치**: Spring Security는 백엔드 필수 역량 ([[백엔드 학습 지도]]에 이미 인증/JWT 항목 있음).
- `BCryptPasswordEncoder`로 비밀번호 저장. 평문·단순 해시 금지.

### 2. JWT (jjwt) + HttpOnly Cookie
- 라이브러리: `io.jsonwebtoken:jjwt` (가장 널리 쓰임, 활발).
- 토큰 전달: **HttpOnly + Secure + SameSite=Lax 쿠키** (ROADMAP P3-1 명시). localStorage 금지 — XSS로 토큰 탈취 방어.
- 서명: HS256 + 환경변수 시크릿(`AGENT_JWT_SECRET`, 최소 256bit). 로컬 기본값은 placeholder, 운영은 주입.

### 3. Access + Refresh 이중 토큰
- **Access token**: JWT, 만료 **15분**, HttpOnly 쿠키(`path=/`). 매 요청 서명 검증(stateless).
- **Refresh token**: JWT, 만료 **14일**, HttpOnly 쿠키(`path=/api/v1/auth`). 해시를 `refresh_tokens` 테이블에 저장 → **서버측 무효화 가능**(로그아웃·탈취 대응). stateless JWT 로그아웃 약점 해소.
- **회전(rotation)**: `/auth/refresh` 호출 시 새 Access + 새 Refresh 발급, 기존 Refresh는 DB에서 무효화. 탈취 토큰 재사용 탐지 기반.
- 토큰 발급·검증은 `JwtTokenProvider`로 격리.

### 4. CSRF 대응
- 쿠키 기반 인증은 CSRF 노출 → **SameSite=Lax**로 1차 방어 + 상태변경 API는 커스텀 헤더(`X-Requested-With`) 요구 또는 Spring CSRF 토큰.
- 앱인토스 WebView는 우리 도메인을 직접 로드(1st-party 쿠키)라 SameSite=Lax로 동작. 토스 도메인 내 iframe 형태면 재검토 필요(입점 시 확인).

### 5. 데이터 모델 (V6 마이그레이션 — 인증 코어만)
```
users          (id, email UNIQUE, password_hash, role, created_at, updated_at)
refresh_tokens (id, user_id FK, token_hash UNIQUE, expires_at, created_at)
```
- `role`은 USER/ADMIN — 기존 `/admin/**` 엔드포인트 보호에 사용.
- **`audit_history`(분석 이력)는 P3-3로 이연(V7)** — `/audit`의 로그인 강제 정책이 P3-3에서 정해지므로, 그때 사용량 카운팅 테이블과 함께 만든다. P3-1 범위를 인증 코어로 좁혀 surgical하게 간다.

### 6. 공개/보호 경로 정책 (P3-1 범위 한정)
- **기존 데모 엔드포인트(`/health`, `/api/v1/search`, `/api/v1/audit`, 정적 UI)는 일단 permitAll 유지** — 공개 체험을 깨지 않는다.
- 신규 인증 엔드포인트만 추가: `POST /api/v1/auth/signup`, `/auth/login`, `/auth/logout`, `/auth/refresh`(공개), `GET /api/v1/auth/me`(인증 필요).
- `logout`은 공개 — 만료·익명 세션도 쿠키를 정리할 수 있어야 하며, refresh 쿠키가 없으면 no-op.
- `/admin/**`는 ADMIN role 요구로 보호.
- **/audit을 로그인 뒤로 옮길지 + 비로그인 무료 체험 횟수는 P3-3(사용량 제한)에서 제품 결정** — 인증 토대를 먼저 깔고 정책은 그때.

## 트레이드오프 / 리스크
- Spring Security 도입 시 **모든 요청이 기본 차단**되므로 SecurityFilterChain에서 공개 경로를 명시적으로 열어야 함 — 누락 시 데모가 막힘. 통합 테스트로 공개 경로 200 확인.
- Access(15분)는 stateless라 탈취 시 최대 15분 노출. Refresh는 DB 화이트리스트라 로그아웃·탈취 시 즉시 무효화 가능 — 이중 토큰의 핵심 이점.
- Refresh 회전(rotation)으로 탈취 토큰 재사용을 탐지하지만, 완전한 재사용 탐지(도난 감지 후 전체 세션 무효화)는 후속 보안 트랙으로.

## 검증 기준
- 회원가입→로그인→`/auth/me`→로그아웃 플로우 통합 테스트 통과 (MockMvc).
- 잘못된 비밀번호/중복 이메일/만료 토큰 케이스 처리.
- 기존 공개 엔드포인트가 인증 없이 200 유지.
- BCrypt 해시 저장 확인(평문 아님).

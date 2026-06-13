-- V7: 분석 이력 + 사용량 카운터 (P3-3)
-- audit_history는 두 역할을 겸한다:
--   1) 사용자별 분석 이력 (ROADMAP P3-1 "분석 이력 관리")
--   2) Free Tier 월 사용량 카운터 (이번 달 행 수 = 사용 횟수)
-- ad_copy, report_json은 P3-2에서 AES-256 암호화 예정 (현재 평문, 무료 티어 기간엔 실고객 데이터 금지 원칙 유지).

CREATE TABLE IF NOT EXISTS audit_history (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ad_copy      TEXT         NOT NULL,
    report_json  TEXT         NOT NULL,        -- ComplianceReport 직렬화 JSON
    overall_risk VARCHAR(20),                  -- none | low | medium | high
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 월 사용량 카운팅 쿼리(user_id + created_at 범위) 최적화
CREATE INDEX IF NOT EXISTS idx_audit_history_user_created
    ON audit_history(user_id, created_at DESC);

-- V6: 인증 코어 스키마 (ADR-008, P3-1)
-- 자체 이메일+비밀번호 인증 + Access/Refresh 이중 토큰.
-- audit_history(분석 이력)는 P3-3에서 사용량 제한과 함께 V7로 도입.

-- 1) 사용자
CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,           -- BCrypt 해시 (평문·단순해시 금지)
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',  -- USER | ADMIN
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 2) Refresh token 화이트리스트 (서버측 무효화·회전용)
--    token_hash = Refresh JWT의 SHA-256 해시. 원문은 HttpOnly 쿠키에만 존재.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);

-- V4: Metadata 컬럼 타입 변경 (JSONB → TEXT)
-- 사유: JPA/Hibernate에서 String 필드를 JSONB로 직접 바인딩할 수 없음
--      metadata를 JSON 문자열로 저장하되, 데이터베이스 타입은 TEXT로 통일

ALTER TABLE regulations
ALTER COLUMN metadata TYPE TEXT;

-- 인덱스는 유지 (text 검색도 지원)

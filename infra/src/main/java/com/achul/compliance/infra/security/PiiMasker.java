package com.achul.compliance.infra.security;

import java.util.regex.Pattern;

/**
 * P3-2: 로그·디스플레이용 PII 마스킹 (ADR-009).
 *
 * <p>저장은 암호화로 원문을 보존하고, 로그·에러 메시지 등 평문 노출 지점에서만 사용한다.
 * 광고 카피를 LLM에 전송할 때는 분석 품질을 위해 원문을 유지한다.</p>
 */
public final class PiiMasker {

    // 주민등록번호: 6자리-7자리
    private static final Pattern RRN = Pattern.compile("\\b(\\d{6})[-\\s]?(\\d{7})\\b");
    // 휴대폰: 010-1234-5678 등
    private static final Pattern PHONE = Pattern.compile("\\b(01[016789])[-\\s]?(\\d{3,4})[-\\s]?(\\d{4})\\b");
    // 이메일
    private static final Pattern EMAIL = Pattern.compile("\\b([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = RRN.matcher(text).replaceAll("$1-*******");
        masked = PHONE.matcher(masked).replaceAll("$1-****-$3");
        masked = EMAIL.matcher(masked).replaceAll(m -> maskEmail(m.group(1)) + "@" + m.group(2));
        return masked;
    }

    private static String maskEmail(String local) {
        if (local.length() <= 2) {
            return local.charAt(0) + "*";
        }
        return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1);
    }
}

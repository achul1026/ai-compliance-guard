package com.achul.compliance.infra.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-2: PII 마스킹 단위 테스트 (ADR-009).
 */
class PiiMaskerTest {

    @Test
    void masks_residentRegistrationNumber() {
        String masked = PiiMasker.mask("주민번호 900101-1234567 입니다");
        assertTrue(masked.contains("900101-*******"));
        assertFalse(masked.contains("1234567"));
    }

    @Test
    void masks_phoneNumber() {
        assertTrue(PiiMasker.mask("연락처 010-1234-5678").contains("010-****-5678"));
        assertTrue(PiiMasker.mask("01012345678").contains("****"));
    }

    @Test
    void masks_email() {
        String masked = PiiMasker.mask("문의 hong@example.com 으로");
        assertTrue(masked.contains("@example.com"));
        assertFalse(masked.contains("hong@"));
        assertTrue(masked.startsWith("문의 h"));
    }

    @Test
    void leavesNonPiiUntouched() {
        String text = "이 영양제는 면역력에 좋습니다";
        assertEquals(text, PiiMasker.mask(text));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(PiiMasker.mask(null));
        assertEquals("", PiiMasker.mask(""));
    }
}

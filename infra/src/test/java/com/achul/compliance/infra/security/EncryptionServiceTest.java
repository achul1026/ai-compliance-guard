package com.achul.compliance.infra.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-2: AES-256-GCM 암호화 단위 테스트 (ADR-009).
 */
class EncryptionServiceTest {

    private static final String KEY = Base64.getEncoder()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes()); // 32 bytes
    private final EncryptionService service = new EncryptionService(KEY);

    @Test
    void encrypt_thenDecrypt_roundTrips() {
        String plain = "이 영양제로 당뇨병이 완치됩니다";
        String enc = service.encrypt(plain);

        assertTrue(enc.startsWith(EncryptionService.PREFIX));
        assertNotEquals(plain, enc);
        assertFalse(enc.contains("당뇨병"), "암호문에 평문이 노출되면 안 됨");
        assertEquals(plain, service.decrypt(enc));
    }

    @Test
    void encrypt_usesRandomIv_soCiphertextDiffersEachTime() {
        String plain = "동일 평문";
        assertNotEquals(service.encrypt(plain), service.encrypt(plain),
            "IV 랜덤이므로 같은 평문도 매번 다른 암호문");
    }

    @Test
    void decrypt_passesThroughLegacyPlaintext() {
        // enc1: prefix 없는 레거시 평문은 그대로 반환
        assertEquals("평문 그대로", service.decrypt("평문 그대로"));
    }

    @Test
    void decrypt_failsWithWrongKey() {
        String enc = service.encrypt("비밀");
        String otherKey = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes());
        EncryptionService other = new EncryptionService(otherKey);

        assertThrows(IllegalStateException.class, () -> other.decrypt(enc));
    }

    @Test
    void decrypt_detectsTampering_viaGcmTag() {
        String enc = service.encrypt("무결성 보호 대상");
        // 암호문 본문 일부 변조
        String body = enc.substring(EncryptionService.PREFIX.length());
        String tampered = EncryptionService.PREFIX + flipLastChar(body);

        assertThrows(IllegalStateException.class, () -> service.decrypt(tampered));
    }

    @Test
    void nullAndEmpty_passThrough() {
        assertNull(service.encrypt(null));
        assertEquals("", service.encrypt(""));
        assertNull(service.decrypt(null));
    }

    @Test
    void constructor_rejectsNon256BitKey() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThrows(IllegalStateException.class, () -> new EncryptionService(shortKey));
    }

    private static String flipLastChar(String s) {
        char last = s.charAt(s.length() - 1);
        char replacement = (last == 'A') ? 'B' : 'A';
        return s.substring(0, s.length() - 1) + replacement;
    }
}

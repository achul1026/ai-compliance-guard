package com.achul.compliance.infra.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * P3-2: AES-256-GCM 암호화 서비스 (ADR-009).
 *
 * <p>기밀성 + 무결성(GCM 인증 태그). 저장 포맷은 {@code enc1:base64(IV‖ciphertext‖tag)}.
 * IV는 매 암호화마다 12바이트 랜덤(GCM nonce 재사용 금지).</p>
 */
@Component
public class EncryptionService {

    public static final String PREFIX = "enc1:";
    private static final int IV_LENGTH = 12;       // GCM 권장 nonce 길이
    private static final int TAG_LENGTH_BITS = 128; // GCM 인증 태그
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${app.aes.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                "app.aes.key 는 base64 디코딩 시 32바이트(AES-256)여야 합니다. 현재: " + keyBytes.length + "바이트");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** 평문 → {@code enc1:...} 암호문. null/빈 문자열은 그대로 반환. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    /**
     * 암호문 → 평문. {@code enc1:} prefix가 없으면 레거시 평문으로 간주해 그대로 반환(ADR-009 §1).
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext); // 태그 불일치 시 AEADBadTagException

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("복호화 실패 (키 불일치 또는 데이터 변조)", e);
        }
    }
}

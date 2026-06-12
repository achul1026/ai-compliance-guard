package com.achul.compliance.agent.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * P2-2: 클래스패스 프롬프트 리소스 로더.
 */
public final class PromptLoader {

    private PromptLoader() {
    }

    public static String load(String classpathLocation) {
        try (InputStream is = PromptLoader.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (is == null) {
                throw new IllegalStateException("프롬프트 리소스를 찾을 수 없음: " + classpathLocation);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 리소스 읽기 실패: " + classpathLocation, e);
        }
    }
}

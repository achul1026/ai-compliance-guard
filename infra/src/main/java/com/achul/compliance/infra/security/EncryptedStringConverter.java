package com.achul.compliance.infra.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * P3-2: 문자열 컬럼 투명 암호화 컨버터 (ADR-009).
 *
 * <p>엔티티 필드에 {@code @Convert(converter = EncryptedStringConverter.class)}로 부착하면
 * 저장 시 AES-256-GCM 암호화, 조회 시 복호화가 자동 적용된다. 도메인은 평문만 본다.</p>
 *
 * <p>Spring Boot가 Hibernate의 BeanContainer를 통해 이 {@code @Component}를 주입하므로
 * {@link EncryptionService} 의존성이 해결된다.</p>
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final EncryptionService encryptionService;

    public EncryptedStringConverter(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptionService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptionService.decrypt(dbData);
    }
}

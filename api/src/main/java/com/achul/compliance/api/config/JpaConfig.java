package com.achul.compliance.api.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 엔티티·리포지토리 스캔 설정.
 *
 * <p>{@code Application}에 직접 붙이면 {@code @WebMvcTest} 슬라이스 테스트까지
 * entityManagerFactory를 요구하게 되므로 별도 설정으로 분리한다
 * (슬라이스 테스트는 일반 {@code @Configuration}을 로드하지 않는다).</p>
 */
@Configuration
@EntityScan(basePackages = "com.achul.compliance.infra.persistence.entity")
@EnableJpaRepositories(basePackages = "com.achul.compliance.infra.persistence.repository")
public class JpaConfig {
}

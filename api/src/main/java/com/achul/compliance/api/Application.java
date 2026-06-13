package com.achul.compliance.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// JPA 스캔 설정은 JpaConfig로 분리 — @WebMvcTest 슬라이스가 entityManagerFactory를 요구하지 않도록.
@SpringBootApplication(scanBasePackages = {"com.achul.compliance"})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

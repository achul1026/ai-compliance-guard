package com.achul.compliance.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.achul.compliance"})
@EntityScan(basePackages = {"com.achul.compliance.infra.persistence.entity"})
@EnableJpaRepositories(basePackages = {"com.achul.compliance.infra.persistence.repository"})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

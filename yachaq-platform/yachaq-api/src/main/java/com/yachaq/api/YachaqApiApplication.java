package com.yachaq.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * YACHAQ Platform API Application
 * 
 * Consent-first personal data sovereignty platform.
 * Java 21 LTS + Spring Boot 3.4.x
 */
@SpringBootApplication(scanBasePackages = "com.yachaq")
@EntityScan(basePackages = {"com.yachaq.core.domain", "com.yachaq.api"})
@EnableJpaRepositories(basePackages = "com.yachaq")
public class YachaqApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(YachaqApiApplication.class, args);
    }
}

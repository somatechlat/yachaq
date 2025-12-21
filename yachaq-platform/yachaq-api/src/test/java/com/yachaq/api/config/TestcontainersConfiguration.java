package com.yachaq.api.config;

import org.springframework.boot.test.context.TestConfiguration;

/**
 * Test configuration for integration tests using REAL Docker infrastructure.
 * 
 * VIBE CODING RULES: Rule #7 - REAL DATA & SERVERS ONLY
 * NO Testcontainers, NO H2, NO mocks, NO fake databases.
 * 
 * All tests run against real Docker infrastructure:
 *   PostgreSQL: localhost:55432
 *   Redis: localhost:55379
 *   Kafka: localhost:55092
 *   Neo4j: localhost:55474/55687
 * 
 * Prerequisites:
 *   docker compose -f yachaq-platform/docker-compose.yml up -d
 * 
 * Usage:
 * - Import this configuration in @SpringBootTest classes
 * - Tests will use the real Docker PostgreSQL at localhost:55432
 * - Flyway migrations run automatically on application startup
 * 
 * This configuration is intentionally empty because the datasource
 * is configured in application-test.yml to use real Docker infrastructure.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    // No beans defined - datasource configured via application-test.yml
    // pointing to real Docker infrastructure at localhost:55432
}

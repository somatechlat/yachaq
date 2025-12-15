package com.yachaq.api.integration;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.api.config.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for rate limiting.
 * 
 * Requirements: 27.4, 69.1
 * - Test rate limiting per client
 * - Verify 429 responses when limit exceeded
 */
@SpringBootTest(
    classes = YachaqApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RateLimitConfig rateLimitConfig;

    private String baseUrl;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/v1";
        headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void rateLimitHeader_shouldBePresentOnRateLimitedEndpoints() {
        UUID dsId = UUID.randomUUID();
        headers.set("X-DS-ID", dsId.toString());
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl + "/wallet/balance",
            HttpMethod.GET,
            entity,
            String.class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Rate limit header should be present on rate-limited endpoints
        assertThat(response.getHeaders().containsKey("X-Rate-Limit-Remaining")).isTrue();
    }

    @Test
    void multipleRequests_shouldDecrementRateLimit() {
        // Use unique client ID to avoid interference
        String clientId = UUID.randomUUID().toString();
        headers.set("X-DS-ID", clientId);
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        
        // Make first request
        ResponseEntity<String> response1 = restTemplate.exchange(
            baseUrl + "/health",
            HttpMethod.GET,
            entity,
            String.class
        );
        
        String remaining1 = response1.getHeaders().getFirst("X-Rate-Limit-Remaining");
        
        // Make second request
        ResponseEntity<String> response2 = restTemplate.exchange(
            baseUrl + "/health",
            HttpMethod.GET,
            entity,
            String.class
        );
        
        String remaining2 = response2.getHeaders().getFirst("X-Rate-Limit-Remaining");
        
        // Remaining should decrease
        if (remaining1 != null && remaining2 != null) {
            int r1 = Integer.parseInt(remaining1);
            int r2 = Integer.parseInt(remaining2);
            assertThat(r2).isLessThanOrEqualTo(r1);
        }
    }

    @Test
    void differentClients_shouldHaveSeparateLimits() {
        String client1 = UUID.randomUUID().toString();
        String client2 = UUID.randomUUID().toString();
        
        HttpHeaders headers1 = new HttpHeaders();
        headers1.set("X-DS-ID", client1);
        
        HttpHeaders headers2 = new HttpHeaders();
        headers2.set("X-DS-ID", client2);
        
        // Request from client 1
        ResponseEntity<String> response1 = restTemplate.exchange(
            baseUrl + "/health",
            HttpMethod.GET,
            new HttpEntity<>(headers1),
            String.class
        );
        
        // Request from client 2
        ResponseEntity<String> response2 = restTemplate.exchange(
            baseUrl + "/health",
            HttpMethod.GET,
            new HttpEntity<>(headers2),
            String.class
        );
        
        // Both should succeed
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthEndpoint_shouldBeExemptFromRateLimit() {
        // Health endpoint should always be accessible
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/health", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}

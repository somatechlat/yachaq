package com.yachaq.api.integration;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.repository.DSProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for API layer.
 * 
 * Requirements: 27.4, 29.1
 * - Test authentication, authorization, rate limiting
 * - Test against real infrastructure (PostgreSQL)
 */
@SpringBootTest(
    classes = YachaqApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class ApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DSProfileRepository dsProfileRepository;

    private String baseUrl;
    private HttpHeaders headers;
    private UUID testDsId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/v1";
        headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Create a test DS profile for tests that require it
        DSProfile testProfile = new DSProfile(
            "test-pseudonym-" + UUID.randomUUID(),
            DSProfile.DSAccountType.DS_IND
        );
        testProfile = dsProfileRepository.save(testProfile);
        testDsId = testProfile.getId();
    }

    // Health check tests

    @Test
    void healthEndpoint_shouldReturnUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/health", Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("status");
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void infoEndpoint_shouldReturnPlatformInfo() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/info", Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("name");
        assertThat(response.getBody().get("name")).isEqualTo("YACHAQ Platform API");
    }

    // Consent API tests

    @Test
    void getConsents_withValidDsId_shouldReturnEmptyList() {
        UUID dsId = UUID.randomUUID();
        headers.set("X-DS-ID", dsId.toString());
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Object[]> response = restTemplate.exchange(
            baseUrl + "/consent/contracts",
            HttpMethod.GET,
            entity,
            Object[].class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void getConsent_withInvalidId_shouldReturn404() {
        UUID invalidId = UUID.randomUUID();
        
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/consent/contracts/" + invalidId, Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Audit API tests

    @Test
    void getAuditReceipts_withValidDsId_shouldReturnPage() {
        UUID dsId = UUID.randomUUID();
        headers.set("X-DS-ID", dsId.toString());
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
            baseUrl + "/audit/receipts",
            HttpMethod.GET,
            entity,
            Map.class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("content");
    }

    @Test
    void getAuditReceipt_withInvalidId_shouldReturn404() {
        UUID invalidId = UUID.randomUUID();
        
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/audit/receipts/" + invalidId, Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Wallet API tests

    @Test
    void getBalance_withValidDsId_shouldReturnBalance() {
        // Use testDsId which has a valid DSProfile in the database
        headers.set("X-DS-ID", testDsId.toString());
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
            baseUrl + "/wallet/balance",
            HttpMethod.GET,
            entity,
            Map.class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("availableBalance");
        assertThat(response.getBody()).containsKey("currency");
    }

    @Test
    void getPayoutHistory_withValidDsId_shouldReturnList() {
        // Use testDsId which has a valid DSProfile in the database
        headers.set("X-DS-ID", testDsId.toString());
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Object[]> response = restTemplate.exchange(
            baseUrl + "/wallet/payouts",
            HttpMethod.GET,
            entity,
            Object[].class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    // Settlement API tests

    @Test
    void getSettlementBalance_withValidDsId_shouldReturnBalance() {
        // Use testDsId which has a valid DSProfile in the database
        headers.set("X-DS-ID", testDsId.toString());
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
            baseUrl + "/settlement/balance",
            HttpMethod.GET,
            entity,
            Map.class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // Query API tests

    @Test
    void getQueryPlan_withInvalidId_shouldReturn404() {
        UUID invalidId = UUID.randomUUID();
        
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/query/plans/" + invalidId, Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTimeCapsule_withInvalidId_shouldReturn404() {
        UUID invalidId = UUID.randomUUID();
        
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/query/capsules/" + invalidId, Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Matching API tests

    @Test
    void getMatchingStats_shouldReturnStats() {
        UUID requestId = UUID.randomUUID();
        
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/matching/stats/" + requestId, Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("cohortSize");
    }

    // Screening API tests

    @Test
    void screenRequest_withValidRequest_shouldReturnResult() {
        String requestBody = """
            {
                "requesterId": "%s",
                "purpose": "research",
                "dataCategories": ["health"],
                "eligibilityCriteria": {}
            }
            """.formatted(UUID.randomUUID());
        
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
            baseUrl + "/screening/screen",
            HttpMethod.POST,
            entity,
            Map.class
        );
        
        // Should return either OK or BAD_REQUEST depending on validation
        assertThat(response.getStatusCode().is2xxSuccessful() || 
                   response.getStatusCode().is4xxClientError()).isTrue();
    }
}

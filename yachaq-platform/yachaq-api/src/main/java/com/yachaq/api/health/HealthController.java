package com.yachaq.api.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health check endpoints.
 * 
 * Requirements: 27.1
 * - Basic health check for load balancers
 * - Detailed info endpoint
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
            "name", "YACHAQ Platform API",
            "version", "1.0.0-SNAPSHOT",
            "description", "Consent-first personal data sovereignty platform",
            "timestamp", Instant.now().toString()
        ));
    }
}

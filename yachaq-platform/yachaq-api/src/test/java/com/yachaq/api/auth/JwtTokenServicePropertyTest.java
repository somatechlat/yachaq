package com.yachaq.api.auth;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

/**
 * Property-based tests for JwtTokenService.
 * 
 * **Feature: yachaq-platform, Property 14: Token Issuance Round-Trip**
 * **Validates: Requirements 1.2**
 * 
 * For any successful authentication, the issued access token when decoded 
 * must contain the correct user ID, expiration time, and scopes that were requested.
 */
class JwtTokenServicePropertyTest {

    private static final String TEST_SECRET = "test-secret-key-that-is-at-least-32-characters-long";
    
    @Property(tries = 100)
    void tokenRoundTrip_decodedTokenContainsCorrectClaims(
            @ForAll("validUUID") UUID dsId,
            @ForAll("validUUID") UUID deviceId,
            @ForAll("validScopes") Set<String> scopes) {
        
        JwtTokenService service = new JwtTokenService(TEST_SECRET, 900, 604800);
        
        JwtTokenService.TokenPair tokens = service.issueTokens(dsId, deviceId, scopes);
        
        JwtTokenService.TokenClaims claims = service.validateAccessToken(tokens.accessToken());
        
        assert claims.dsId().equals(dsId) 
            : "DS ID mismatch: expected " + dsId + ", got " + claims.dsId();
        assert claims.deviceId().equals(deviceId) 
            : "Device ID mismatch: expected " + deviceId + ", got " + claims.deviceId();
        assert claims.scopes().equals(scopes) 
            : "Scopes mismatch: expected " + scopes + ", got " + claims.scopes();
        assert claims.expiry().isAfter(java.time.Instant.now()) 
            : "Token should not be expired immediately after issuance";
    }

    @Property(tries = 100)
    void sha256_isConsistent(
            @ForAll @StringLength(min = 1, max = 100) String input) {
        
        JwtTokenService service = new JwtTokenService(TEST_SECRET, 900, 604800);
        
        String hash1 = service.sha256(input);
        String hash2 = service.sha256(input);
        
        assert hash1.equals(hash2) : "SHA-256 should be deterministic";
        assert hash1.length() == 64 : "SHA-256 hex should be 64 characters";
        assert hash1.matches("[0-9a-f]+") : "SHA-256 should be lowercase hex";
    }

    @Property(tries = 100)
    void sha256_differentInputsProduceDifferentHashes(
            @ForAll @StringLength(min = 1, max = 50) String input1,
            @ForAll @StringLength(min = 1, max = 50) String input2) {
        
        Assume.that(!input1.equals(input2));
        
        JwtTokenService service = new JwtTokenService(TEST_SECRET, 900, 604800);
        
        String hash1 = service.sha256(input1);
        String hash2 = service.sha256(input2);
        
        assert !hash1.equals(hash2) : "Different inputs should produce different hashes";
    }

    @Property(tries = 50)
    void refreshTokenHash_isStoredCorrectly(
            @ForAll("validUUID") UUID dsId,
            @ForAll("validUUID") UUID deviceId,
            @ForAll("validScopes") Set<String> scopes) {
        
        JwtTokenService service = new JwtTokenService(TEST_SECRET, 900, 604800);
        
        JwtTokenService.TokenPair tokens = service.issueTokens(dsId, deviceId, scopes);
        
        String computedHash = service.sha256(tokens.refreshToken());
        
        assert computedHash.equals(tokens.refreshTokenHash()) 
            : "Refresh token hash should match computed hash";
    }

    @Provide
    Arbitrary<UUID> validUUID() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<Set<String>> validScopes() {
        return Arbitraries.of(
            Set.of("consent:read"),
            Set.of("consent:read", "consent:write"),
            Set.of("consent:read", "consent:write", "audit:read"),
            Set.of("consent:read", "consent:write", "audit:read", "wallet:read")
        );
    }
}

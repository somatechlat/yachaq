package com.yachaq.api.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * JWT Token Service - Production implementation.
 * Uses HMAC-SHA256 for signing, with configurable expiration.
 * 
 * Property 14: Token Issuance Round-Trip
 * Validates: Requirements 1.2
 */
@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final long accessTokenValiditySeconds;
    private final long refreshTokenValiditySeconds;
    private final SecureRandom secureRandom;

    public JwtTokenService(
            @Value("${yachaq.jwt.secret}") String jwtSecret,
            @Value("${yachaq.jwt.access-token-validity:900}") long accessTokenValiditySeconds,
            @Value("${yachaq.jwt.refresh-token-validity:604800}") long refreshTokenValiditySeconds) {
        
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters");
        }
        
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValiditySeconds = accessTokenValiditySeconds;
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Issues an access token for authenticated user.
     * Short-lived (default 15 minutes).
     */
    public TokenPair issueTokens(UUID dsId, UUID deviceId, Set<String> scopes) {
        Instant now = Instant.now();
        Instant accessExpiry = now.plusSeconds(accessTokenValiditySeconds);
        Instant refreshExpiry = now.plusSeconds(refreshTokenValiditySeconds);

        String accessToken = Jwts.builder()
                .subject(dsId.toString())
                .claim("device_id", deviceId.toString())
                .claim("scopes", scopes)
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExpiry))
                .signWith(signingKey)
                .compact();

        String refreshTokenValue = generateSecureToken();
        String refreshTokenHash = sha256(refreshTokenValue);

        return new TokenPair(
                accessToken,
                refreshTokenValue,
                refreshTokenHash,
                accessExpiry,
                refreshExpiry
        );
    }

    /**
     * Validates and parses an access token.
     * Returns claims if valid, throws exception if invalid.
     */
    public TokenClaims validateAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String type = claims.get("type", String.class);
            if (!"access".equals(type)) {
                throw new JwtException("Invalid token type");
            }

            return new TokenClaims(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("device_id", String.class)),
                    new HashSet<>((List<String>) claims.get("scopes")),
                    claims.getExpiration().toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Access token expired", e);
        } catch (JwtException e) {
            throw new InvalidTokenException("Invalid access token", e);
        }
    }

    /**
     * Generates a cryptographically secure random token.
     * 256 bits of entropy, Base64URL encoded.
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes SHA-256 hash of input string.
     * Returns lowercase hex string.
     */
    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record TokenPair(
            String accessToken,
            String refreshToken,
            String refreshTokenHash,
            Instant accessTokenExpiry,
            Instant refreshTokenExpiry
    ) {}

    public record TokenClaims(
            UUID dsId,
            UUID deviceId,
            Set<String> scopes,
            Instant expiry
    ) {}

    public static class TokenExpiredException extends RuntimeException {
        public TokenExpiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

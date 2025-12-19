package com.yachaq.api.auth;

import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.domain.Device;
import com.yachaq.core.repository.DSProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AuthenticationService.
 * Uses local PostgreSQL database on port 55432.
 * Each test uses unique identifiers to avoid conflicts.
 * @Transactional ensures rollback after each test.
 * 
 * Validates: Requirements 1.3, 1.4
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthenticationServiceTest {

    @Autowired
    private AuthenticationService authService;

    @Autowired
    private DSProfileRepository dsProfileRepository;

    @Autowired
    private EntityManager entityManager;

    private String uniqueUserId() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void authenticateOAuth_createsNewProfileOnFirstLogin() {
        String userId = uniqueUserId();
        
        AuthenticationService.AuthResult result = authService.authenticateOAuth(
                "google", userId, "test@example.com", 
                "public-key-" + userId, Device.DeviceType.MOBILE_ANDROID);

        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertNotNull(result.dsId());
        assertNotNull(result.deviceId());
        assertTrue(result.accessTokenExpiry().isAfter(Instant.now()));

        DSProfile profile = dsProfileRepository.findById(result.dsId()).orElseThrow();
        assertEquals("google:" + userId, profile.getPseudonym());
        assertEquals(DSProfile.DSStatus.ACTIVE, profile.getStatus());
    }

    @Test
    void authenticateOAuth_reusesExistingProfile() {
        String userId = uniqueUserId();
        
        AuthenticationService.AuthResult first = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key-" + userId, Device.DeviceType.MOBILE_IOS);
        
        AuthenticationService.AuthResult second = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key-" + userId, Device.DeviceType.MOBILE_IOS);

        assertEquals(first.dsId(), second.dsId());
        assertEquals(first.deviceId(), second.deviceId());
    }

    @Test
    void authenticateOAuth_failsForSuspendedAccount() {
        String userId = uniqueUserId();
        
        AuthenticationService.AuthResult result = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key-" + userId, Device.DeviceType.DESKTOP);
        
        DSProfile profile = dsProfileRepository.findById(result.dsId()).orElseThrow();
        profile.suspend();
        dsProfileRepository.saveAndFlush(profile);

        assertThrows(AuthenticationService.AccountSuspendedException.class, () ->
                authService.authenticateOAuth("google", userId, "test@example.com", 
                        "key-" + userId, Device.DeviceType.DESKTOP));
    }

    @Test
    void refreshTokens_issuesNewTokens() {
        String userId = uniqueUserId();
        
        AuthenticationService.AuthResult initial = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key-" + userId, Device.DeviceType.MOBILE_ANDROID);
        
        AuthenticationService.AuthResult refreshed = authService.refreshTokens(initial.refreshToken());

        assertNotEquals(initial.refreshToken(), refreshed.refreshToken());
        assertEquals(initial.dsId(), refreshed.dsId());
        assertEquals(initial.deviceId(), refreshed.deviceId());
    }

    @Test
    void refreshTokens_failsForInvalidToken() {
        assertThrows(AuthenticationService.InvalidRefreshTokenException.class, () ->
                authService.refreshTokens("invalid-" + UUID.randomUUID()));
    }

    @Test
    void refreshTokens_detectsTokenReuse() {
        String userId = uniqueUserId();
        
        AuthenticationService.AuthResult initial = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key-" + userId, Device.DeviceType.MOBILE_ANDROID);

        authService.refreshTokens(initial.refreshToken());

        assertThrows(AuthenticationService.TokenReusedException.class, () ->
                authService.refreshTokens(initial.refreshToken()));
    }

    @Test
    void logout_revokesRefreshToken() {
        String userId = uniqueUserId();
        
        AuthenticationService.AuthResult result = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key-" + userId, Device.DeviceType.MOBILE_ANDROID);

        authService.logout(result.refreshToken());

        assertThrows(RuntimeException.class, () -> authService.refreshTokens(result.refreshToken()));
    }

    @Test
    void revokeAllTokens_invalidatesAllUserTokens() {
        String userId = uniqueUserId();
        
        AuthenticationService.AuthResult result1 = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key1-" + userId, Device.DeviceType.MOBILE_ANDROID);
        AuthenticationService.AuthResult result2 = authService.authenticateOAuth(
                "google", userId, "test@example.com", "key2-" + userId, Device.DeviceType.MOBILE_IOS);

        int revoked = authService.revokeAllTokens(result1.dsId());
        entityManager.flush();
        entityManager.clear();

        assertTrue(revoked >= 2);
        assertThrows(RuntimeException.class, () -> authService.refreshTokens(result1.refreshToken()));
        assertThrows(RuntimeException.class, () -> authService.refreshTokens(result2.refreshToken()));
    }
}

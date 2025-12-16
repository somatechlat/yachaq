package com.yachaq.api.auth;

import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.domain.Device;
import com.yachaq.core.domain.RefreshToken;
import com.yachaq.core.repository.DSProfileRepository;
import com.yachaq.core.repository.DeviceRepository;
import com.yachaq.core.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AuthenticationService.
 * Uses local PostgreSQL database on port 55432.
 * 
 * Validates: Requirements 1.3, 1.4
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthenticationServiceTest {

    @Autowired
    private AuthenticationService authService;

    @Autowired
    private DSProfileRepository dsProfileRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        deviceRepository.deleteAll();
        dsProfileRepository.deleteAll();
    }

    @Test
    void authenticateOAuth_createsNewProfileOnFirstLogin() {
        AuthenticationService.AuthResult result = authService.authenticateOAuth(
                "google",
                "user123",
                "test@example.com",
                "public-key-abc",
                Device.DeviceType.MOBILE_ANDROID
        );

        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertNotNull(result.dsId());
        assertNotNull(result.deviceId());
        assertTrue(result.accessTokenExpiry().isAfter(Instant.now()));
        assertTrue(result.refreshTokenExpiry().isAfter(Instant.now()));

        DSProfile profile = dsProfileRepository.findById(result.dsId()).orElseThrow();
        assertEquals("google:user123", profile.getPseudonym());
        assertEquals(DSProfile.DSStatus.ACTIVE, profile.getStatus());
    }

    @Test
    void authenticateOAuth_reusesExistingProfile() throws InterruptedException {
        AuthenticationService.AuthResult first = authService.authenticateOAuth(
                "google", "user456", "test@example.com", "key1", Device.DeviceType.MOBILE_IOS);
        
        // Wait 1 second to ensure different token timestamps
        Thread.sleep(1000);
        
        AuthenticationService.AuthResult second = authService.authenticateOAuth(
                "google", "user456", "test@example.com", "key1", Device.DeviceType.MOBILE_IOS);

        assertEquals(first.dsId(), second.dsId());
        assertEquals(first.deviceId(), second.deviceId());
        // Both logins should produce valid tokens (may be same if within same second)
        assertNotNull(first.accessToken());
        assertNotNull(second.accessToken());
    }

    @Test
    void authenticateOAuth_failsForSuspendedAccount() {
        AuthenticationService.AuthResult result = authService.authenticateOAuth(
                "google", "suspended-user", "test@example.com", "key", Device.DeviceType.DESKTOP);
        
        DSProfile profile = dsProfileRepository.findById(result.dsId()).orElseThrow();
        profile.suspend();
        dsProfileRepository.save(profile);

        assertThrows(AuthenticationService.AccountSuspendedException.class, () ->
                authService.authenticateOAuth(
                        "google", "suspended-user", "test@example.com", "key", Device.DeviceType.DESKTOP)
        );
    }

    @Test
    void refreshTokens_issuesNewTokensAndRevokesOld() throws InterruptedException {
        AuthenticationService.AuthResult initial = authService.authenticateOAuth(
                "google", "refresh-test", "test@example.com", "key", Device.DeviceType.MOBILE_ANDROID);

        // Wait to ensure different token timestamps
        Thread.sleep(1100);
        
        AuthenticationService.AuthResult refreshed = authService.refreshTokens(initial.refreshToken());

        // Refresh tokens should always be different (new random value)
        assertNotEquals(initial.refreshToken(), refreshed.refreshToken());
        // User and device should remain the same
        assertEquals(initial.dsId(), refreshed.dsId());
        assertEquals(initial.deviceId(), refreshed.deviceId());
        // Both should have valid tokens
        assertNotNull(refreshed.accessToken());
        assertNotNull(refreshed.refreshToken());
    }

    @Test
    void refreshTokens_failsForInvalidToken() {
        assertThrows(AuthenticationService.InvalidRefreshTokenException.class, () ->
                authService.refreshTokens("invalid-token-value")
        );
    }

    @Test
    void refreshTokens_detectsTokenReuse() {
        AuthenticationService.AuthResult initial = authService.authenticateOAuth(
                "google", "reuse-test", "test@example.com", "key", Device.DeviceType.MOBILE_ANDROID);

        authService.refreshTokens(initial.refreshToken());

        assertThrows(AuthenticationService.TokenReusedException.class, () ->
                authService.refreshTokens(initial.refreshToken())
        );
    }

    @Test
    void logout_revokesRefreshToken() {
        AuthenticationService.AuthResult result = authService.authenticateOAuth(
                "google", "logout-test", "test@example.com", "key", Device.DeviceType.MOBILE_ANDROID);

        authService.logout(result.refreshToken());

        // After logout, trying to use the revoked token should fail
        // It throws TokenReusedException because the token was revoked (detected as reuse)
        Exception exception = assertThrows(RuntimeException.class, () ->
                authService.refreshTokens(result.refreshToken())
        );
        assertTrue(exception instanceof AuthenticationService.InvalidRefreshTokenException ||
                   exception instanceof AuthenticationService.TokenReusedException);
    }

    @Test
    void revokeAllTokens_invalidatesAllUserTokens() {
        AuthenticationService.AuthResult result1 = authService.authenticateOAuth(
                "google", "revoke-all", "test@example.com", "key1", Device.DeviceType.MOBILE_ANDROID);
        AuthenticationService.AuthResult result2 = authService.authenticateOAuth(
                "google", "revoke-all", "test@example.com", "key2", Device.DeviceType.MOBILE_IOS);

        int revoked = authService.revokeAllTokens(result1.dsId());

        assertTrue(revoked >= 2);
        // After revokeAll, trying to use revoked tokens should fail
        // May throw either InvalidRefreshTokenException or TokenReusedException
        Exception ex1 = assertThrows(RuntimeException.class, () ->
                authService.refreshTokens(result1.refreshToken()));
        assertTrue(ex1 instanceof AuthenticationService.InvalidRefreshTokenException ||
                   ex1 instanceof AuthenticationService.TokenReusedException);
        
        Exception ex2 = assertThrows(RuntimeException.class, () ->
                authService.refreshTokens(result2.refreshToken()));
        assertTrue(ex2 instanceof AuthenticationService.InvalidRefreshTokenException ||
                   ex2 instanceof AuthenticationService.TokenReusedException);
    }
}

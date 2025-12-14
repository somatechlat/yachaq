package com.yachaq.api.auth;

import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.domain.Device;
import com.yachaq.core.domain.RefreshToken;
import com.yachaq.core.repository.DSProfileRepository;
import com.yachaq.core.repository.DeviceRepository;
import com.yachaq.core.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Authentication service handling login, token refresh, and logout.
 * 
 * Validates: Requirements 1.1, 1.2, 1.4
 */
@Service
public class AuthenticationService {

    private final DSProfileRepository dsProfileRepository;
    private final DeviceRepository deviceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService jwtTokenService;

    public AuthenticationService(
            DSProfileRepository dsProfileRepository,
            DeviceRepository deviceRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenService jwtTokenService) {
        this.dsProfileRepository = dsProfileRepository;
        this.deviceRepository = deviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * Authenticates user via OAuth2 provider and issues tokens.
     * Creates DS profile if first login.
     */
    @Transactional
    public AuthResult authenticateOAuth(
            String provider,
            String providerUserId,
            String email,
            String publicKey,
            Device.DeviceType deviceType) {
        
        String pseudonym = provider + ":" + providerUserId;
        
        DSProfile profile = dsProfileRepository.findByPseudonym(pseudonym)
                .orElseGet(() -> {
                    DSProfile newProfile = new DSProfile(pseudonym, DSProfile.DSAccountType.DS_IND);
                    return dsProfileRepository.save(newProfile);
                });

        if (profile.getStatus() != DSProfile.DSStatus.ACTIVE) {
            throw new AccountSuspendedException("Account is " + profile.getStatus());
        }

        Device device = deviceRepository.findByDsIdAndPublicKey(profile.getId(), publicKey)
                .orElseGet(() -> {
                    Device newDevice = Device.enroll(profile.getId(), publicKey, deviceType);
                    return deviceRepository.save(newDevice);
                });

        device.updateLastSeen();
        deviceRepository.save(device);

        Set<String> scopes = Set.of("consent:read", "consent:write", "audit:read", "wallet:read");
        JwtTokenService.TokenPair tokens = jwtTokenService.issueTokens(
                profile.getId(), device.getId(), scopes);

        RefreshToken refreshToken = RefreshToken.create(
                profile.getId(),
                device.getId(),
                tokens.refreshTokenHash(),
                tokens.refreshTokenExpiry()
        );
        refreshTokenRepository.save(refreshToken);

        return new AuthResult(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiry(),
                tokens.refreshTokenExpiry(),
                profile.getId(),
                device.getId(),
                scopes
        );
    }

    /**
     * Refreshes access token using valid refresh token.
     * Implements token rotation - old refresh token is invalidated.
     */
    @Transactional
    public AuthResult refreshTokens(String refreshTokenValue) {
        String tokenHash = jwtTokenService.sha256(refreshTokenValue);
        
        RefreshToken existingToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not found"));

        if (!existingToken.isValid()) {
            if (existingToken.isRevoked()) {
                refreshTokenRepository.revokeAllByDsId(existingToken.getDsId(), Instant.now());
                throw new TokenReusedException("Refresh token reuse detected - all tokens revoked");
            }
            throw new InvalidRefreshTokenException("Refresh token expired");
        }

        DSProfile profile = dsProfileRepository.findById(existingToken.getDsId())
                .orElseThrow(() -> new InvalidRefreshTokenException("User not found"));

        if (profile.getStatus() != DSProfile.DSStatus.ACTIVE) {
            throw new AccountSuspendedException("Account is " + profile.getStatus());
        }

        Device device = deviceRepository.findById(existingToken.getDeviceId())
                .orElseThrow(() -> new InvalidRefreshTokenException("Device not found"));

        device.updateLastSeen();
        deviceRepository.save(device);

        Set<String> scopes = Set.of("consent:read", "consent:write", "audit:read", "wallet:read");
        JwtTokenService.TokenPair newTokens = jwtTokenService.issueTokens(
                profile.getId(), device.getId(), scopes);

        RefreshToken newRefreshToken = RefreshToken.create(
                profile.getId(),
                device.getId(),
                newTokens.refreshTokenHash(),
                newTokens.refreshTokenExpiry()
        );
        refreshTokenRepository.save(newRefreshToken);

        existingToken.revoke(newRefreshToken.getId());
        refreshTokenRepository.save(existingToken);

        return new AuthResult(
                newTokens.accessToken(),
                newTokens.refreshToken(),
                newTokens.accessTokenExpiry(),
                newTokens.refreshTokenExpiry(),
                profile.getId(),
                device.getId(),
                scopes
        );
    }

    /**
     * Logs out user by revoking refresh token.
     */
    @Transactional
    public void logout(String refreshTokenValue) {
        String tokenHash = jwtTokenService.sha256(refreshTokenValue);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });
    }

    /**
     * Revokes all tokens for a user (security event, password change, etc).
     */
    @Transactional
    public int revokeAllTokens(UUID dsId) {
        return refreshTokenRepository.revokeAllByDsId(dsId, Instant.now());
    }

    public record AuthResult(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiry,
            Instant refreshTokenExpiry,
            UUID dsId,
            UUID deviceId,
            Set<String> scopes
    ) {}

    public static class AccountSuspendedException extends RuntimeException {
        public AccountSuspendedException(String message) { super(message); }
    }

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) { super(message); }
    }

    public static class TokenReusedException extends RuntimeException {
        public TokenReusedException(String message) { super(message); }
    }
}

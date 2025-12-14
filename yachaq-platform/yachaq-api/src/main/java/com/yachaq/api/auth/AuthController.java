package com.yachaq.api.auth;

import com.yachaq.core.domain.Device;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Authentication REST API endpoints.
 * 
 * Validates: Requirements 1.1, 1.2, 1.4
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * OAuth2 callback - exchanges provider token for YACHAQ tokens.
     */
    @PostMapping("/oauth/callback")
    public ResponseEntity<AuthResponse> oauthCallback(@Valid @RequestBody OAuthCallbackRequest request) {
        AuthenticationService.AuthResult result = authenticationService.authenticateOAuth(
                request.provider(),
                request.providerUserId(),
                request.email(),
                request.publicKey(),
                request.deviceType()
        );

        return ResponseEntity.ok(toResponse(result));
    }

    /**
     * Refresh access token using refresh token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshRequest request) {
        AuthenticationService.AuthResult result = authenticationService.refreshTokens(
                request.refreshToken()
        );

        return ResponseEntity.ok(toResponse(result));
    }

    /**
     * Logout - revokes refresh token.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private AuthResponse toResponse(AuthenticationService.AuthResult result) {
        return new AuthResponse(
                result.accessToken(),
                result.refreshToken(),
                result.accessTokenExpiry().getEpochSecond(),
                result.refreshTokenExpiry().getEpochSecond(),
                result.dsId(),
                result.deviceId(),
                result.scopes()
        );
    }

    @ExceptionHandler(AuthenticationService.AccountSuspendedException.class)
    public ResponseEntity<ErrorResponse> handleAccountSuspended(AuthenticationService.AccountSuspendedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("AUTH_001", e.getMessage()));
    }

    @ExceptionHandler(AuthenticationService.InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(AuthenticationService.InvalidRefreshTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("AUTH_002", e.getMessage()));
    }

    @ExceptionHandler(AuthenticationService.TokenReusedException.class)
    public ResponseEntity<ErrorResponse> handleTokenReuse(AuthenticationService.TokenReusedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("AUTH_003", e.getMessage()));
    }

    @ExceptionHandler(JwtTokenService.TokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTokenExpired(JwtTokenService.TokenExpiredException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("AUTH_002", "Token expired"));
    }

    public record OAuthCallbackRequest(
            @NotBlank String provider,
            @NotBlank String providerUserId,
            String email,
            @NotBlank String publicKey,
            @NotNull Device.DeviceType deviceType
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record LogoutRequest(
            @NotBlank String refreshToken
    ) {}

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            long accessTokenExpiresAt,
            long refreshTokenExpiresAt,
            UUID dsId,
            UUID deviceId,
            Set<String> scopes
    ) {}

    public record ErrorResponse(
            String code,
            String message
    ) {}
}

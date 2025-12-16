package com.yachaq.api.attestation;

import com.yachaq.core.domain.DeviceAttestation;
import com.yachaq.core.domain.DeviceAttestation.Platform;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * REST controller for device attestation operations.
 * 
 * Validates: Requirements 217.1, 217.2, 217.3, 217.4, 217.5
 */
@RestController
@RequestMapping("/api/v1/attestation")
public class DeviceAttestationController {

    private final DeviceAttestationService attestationService;

    public DeviceAttestationController(DeviceAttestationService attestationService) {
        this.attestationService = attestationService;
    }

    /**
     * Generate a nonce for attestation challenge.
     * Client uses this nonce when requesting attestation from platform API.
     */
    @PostMapping("/nonce")
    public ResponseEntity<NonceResponse> generateNonce() {
        String nonce = attestationService.generateNonce();
        return ResponseEntity.ok(new NonceResponse(nonce));
    }

    /**
     * Submit attestation proof for collection.
     * Requirements 217.1, 217.2: Collect SafetyNet/DeviceCheck attestation.
     */
    @PostMapping("/collect")
    public ResponseEntity<AttestationResponse> collectAttestation(
            @Valid @RequestBody CollectAttestationRequest request) {
        
        DeviceAttestation attestation = attestationService.collectAttestation(
                request.deviceId(),
                request.platform(),
                request.attestationProof(),
                request.nonce()
        );

        return ResponseEntity.ok(AttestationResponse.from(attestation));
    }

    /**
     * Verify a pending attestation.
     * Requirements 217.4, 217.5: Verify proofs and flag failed devices.
     */
    @PostMapping("/{attestationId}/verify")
    public ResponseEntity<AttestationResponse> verifyAttestation(
            @PathVariable UUID attestationId) {
        
        DeviceAttestation attestation = attestationService.verifyAttestation(attestationId);
        return ResponseEntity.ok(AttestationResponse.from(attestation));
    }

    /**
     * Get current attestation status for a device.
     */
    @GetMapping("/device/{deviceId}/status")
    public ResponseEntity<DeviceAttestationService.AttestationStatusResult> getStatus(
            @PathVariable UUID deviceId) {
        
        var status = attestationService.getAttestationStatus(deviceId);
        return ResponseEntity.ok(status);
    }

    /**
     * Trigger re-attestation for a device.
     * Returns a new nonce for the attestation challenge.
     */
    @PostMapping("/device/{deviceId}/refresh")
    public ResponseEntity<NonceResponse> refreshAttestation(
            @PathVariable UUID deviceId) {
        
        String nonce = attestationService.refreshAttestation(deviceId);
        return ResponseEntity.ok(new NonceResponse(nonce));
    }

    // Request/Response DTOs
    public record NonceResponse(String nonce) {}

    public record CollectAttestationRequest(
            @NotNull UUID deviceId,
            @NotNull Platform platform,
            @NotBlank String attestationProof,
            @NotBlank String nonce
    ) {}

    public record AttestationResponse(
            UUID id,
            UUID deviceId,
            Platform platform,
            DeviceAttestation.AttestationType attestationType,
            DeviceAttestation.TrustLevel trustLevel,
            DeviceAttestation.AttestationStatus status,
            String failureReason
    ) {
        public static AttestationResponse from(DeviceAttestation attestation) {
            return new AttestationResponse(
                    attestation.getId(),
                    attestation.getDeviceId(),
                    attestation.getPlatform(),
                    attestation.getAttestationType(),
                    attestation.getTrustLevel(),
                    attestation.getStatus(),
                    attestation.getFailureReason()
            );
        }
    }
}

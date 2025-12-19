package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity for Data Use Agreement acceptances.
 * Replaces raw SQL in RequesterGovernanceService.
 */
@Entity
@Table(name = "dua_acceptances", indexes = {
    @Index(name = "idx_dua_requester", columnList = "requester_id"),
    @Index(name = "idx_dua_version", columnList = "dua_version")
})
public class DUAAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @NotNull
    @Column(name = "dua_version", nullable = false)
    private String duaVersion;

    @NotNull
    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "signature_hash")
    private String signatureHash;

    protected DUAAcceptance() {}

    public static DUAAcceptance create(UUID requesterId, String duaVersion, String ipAddress, 
                                        String userAgent, String signatureHash) {
        DUAAcceptance dua = new DUAAcceptance();
        dua.requesterId = requesterId;
        dua.duaVersion = duaVersion;
        dua.acceptedAt = Instant.now();
        dua.ipAddress = ipAddress;
        dua.userAgent = userAgent;
        dua.signatureHash = signatureHash;
        return dua;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public String getDuaVersion() { return duaVersion; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public String getSignatureHash() { return signatureHash; }
}

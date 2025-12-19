package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity for misuse reports against requesters.
 * Replaces raw SQL in RequesterGovernanceService.
 */
@Entity
@Table(name = "misuse_reports", indexes = {
    @Index(name = "idx_mr_requester", columnList = "requester_id"),
    @Index(name = "idx_mr_reporter", columnList = "reporter_id"),
    @Index(name = "idx_mr_status", columnList = "status")
})
public class MisuseReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "evidence_hashes", columnDefinition = "TEXT")
    private String evidenceHashes;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "filed_at", nullable = false)
    private Instant filedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement_action")
    private EnforcementAction enforcementAction;

    @Column(columnDefinition = "TEXT")
    private String resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected MisuseReport() {}

    public static MisuseReport create(UUID reporterId, UUID requesterId, String description, String evidenceHashes) {
        MisuseReport report = new MisuseReport();
        report.reporterId = reporterId;
        report.requesterId = requesterId;
        report.description = description;
        report.evidenceHashes = evidenceHashes;
        report.status = ReportStatus.PENDING;
        report.filedAt = Instant.now();
        return report;
    }

    public void resolve(EnforcementAction action, String resolution) {
        this.enforcementAction = action;
        this.resolution = resolution;
        this.status = action == EnforcementAction.NONE ? ReportStatus.DISMISSED : ReportStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getReporterId() { return reporterId; }
    public UUID getRequesterId() { return requesterId; }
    public String getDescription() { return description; }
    public String getEvidenceHashes() { return evidenceHashes; }
    public ReportStatus getStatus() { return status; }
    public Instant getFiledAt() { return filedAt; }
    public EnforcementAction getEnforcementAction() { return enforcementAction; }
    public String getResolution() { return resolution; }
    public Instant getResolvedAt() { return resolvedAt; }

    public enum ReportStatus {
        PENDING, RESOLVED, DISMISSED
    }

    public enum EnforcementAction {
        NONE, WARNING, SUSPENSION, BAN
    }
}

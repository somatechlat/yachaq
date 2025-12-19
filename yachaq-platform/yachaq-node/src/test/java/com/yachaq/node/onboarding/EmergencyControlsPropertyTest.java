package com.yachaq.node.onboarding;

import com.yachaq.node.onboarding.EmergencyControls.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for EmergencyControls.
 * 
 * Validates: Requirements 347.1, 347.2, 347.3, 347.4, 347.5
 */
class EmergencyControlsPropertyTest {

    // ==================== Test Fixtures ====================

    private EmergencyControls createEmergencyControls() {
        return new EmergencyControls(
                new InMemorySharingController(),
                new InMemoryRelationshipManager(),
                new InMemoryVaultManager(),
                new InMemoryAuditLogger()
        );
    }

    private EmergencyControls createEmergencyControlsWithData() {
        InMemorySharingController sharing = new InMemorySharingController();
        sharing.setActiveTransfers(5);
        sharing.setActiveContracts(10);

        InMemoryRelationshipManager relationships = new InMemoryRelationshipManager();
        relationships.addContract("requester-1", "contract-1");
        relationships.addContract("requester-1", "contract-2");
        relationships.addContract("requester-2", "contract-3");

        InMemoryVaultManager vault = new InMemoryVaultManager();
        vault.addCategory("health", 100, 1024 * 1024, 2);
        vault.addCategory("media", 500, 10 * 1024 * 1024, 0);
        vault.setOdxEntries(50);

        return new EmergencyControls(sharing, relationships, vault, new InMemoryAuditLogger());
    }

    // ==================== Task 91.1: Emergency Stop Tests ====================

    @Test
    void emergencyStop_stopsAllSharingInstantly() {
        // Requirement 347.1: Stop all sharing instantly
        InMemorySharingController sharing = new InMemorySharingController();
        sharing.setActiveTransfers(5);
        sharing.setActiveContracts(10);
        EmergencyControls controls = new EmergencyControls(
                sharing, new InMemoryRelationshipManager(), 
                new InMemoryVaultManager(), new InMemoryAuditLogger());

        EmergencyStopResult result = controls.emergencyStop("User requested emergency stop");

        assertThat(result.success()).isTrue();
        assertThat(result.transfersStopped()).isEqualTo(5);
        assertThat(result.contractsPaused()).isEqualTo(10);
        assertThat(controls.isEmergencyStopActive()).isTrue();
    }

    @Test
    void emergencyStop_blocksNewRequests() {
        InMemorySharingController sharing = new InMemorySharingController();
        EmergencyControls controls = new EmergencyControls(
                sharing, new InMemoryRelationshipManager(),
                new InMemoryVaultManager(), new InMemoryAuditLogger());

        controls.emergencyStop("Test");

        assertThat(sharing.isBlocked()).isTrue();
    }

    @Test
    void emergencyStop_logsAction() {
        // Requirement 347.4: Audit logging
        InMemoryAuditLogger logger = new InMemoryAuditLogger();
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                new InMemoryVaultManager(), logger);

        controls.emergencyStop("Test reason");

        assertThat(logger.getActions()).hasSize(1);
        assertThat(logger.getActions().get(0).type()).isEqualTo(EmergencyActionType.EMERGENCY_STOP);
        assertThat(logger.getActions().get(0).reason()).isEqualTo("Test reason");
    }

    @Test
    void resumeSharing_resumesAfterEmergencyStop() {
        // Requirement 347.5: Recovery
        EmergencyControls controls = createEmergencyControlsWithData();
        controls.emergencyStop("Test");

        ResumeResult result = controls.resumeSharing("CONFIRM_RESUME");

        assertThat(result.success()).isTrue();
        assertThat(controls.isEmergencyStopActive()).isFalse();
    }

    @Test
    void resumeSharing_requiresCorrectConfirmation() {
        EmergencyControls controls = createEmergencyControls();
        controls.emergencyStop("Test");

        ResumeResult result = controls.resumeSharing("WRONG_CODE");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid confirmation");
        assertThat(controls.isEmergencyStopActive()).isTrue();
    }

    @Test
    void resumeSharing_failsIfNotStopped() {
        EmergencyControls controls = createEmergencyControls();

        ResumeResult result = controls.resumeSharing("CONFIRM_RESUME");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not active");
    }

    // ==================== Task 91.2: Relationship Revocation Tests ====================

    @Test
    void revokeRequesterRelationship_revokesAllContracts() {
        // Requirement 347.2: Allow revoking all requester relationships
        InMemoryRelationshipManager relationships = new InMemoryRelationshipManager();
        relationships.addContract("requester-1", "contract-1");
        relationships.addContract("requester-1", "contract-2");
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), relationships,
                new InMemoryVaultManager(), new InMemoryAuditLogger());

        RevocationResult result = controls.revokeRequesterRelationship("requester-1", "User requested");

        assertThat(result.success()).isTrue();
        assertThat(result.contractsRevoked()).isEqualTo(2);
        assertThat(relationships.isRevoked("contract-1")).isTrue();
        assertThat(relationships.isRevoked("contract-2")).isTrue();
    }

    @Test
    void revokeRequesterRelationship_blocksRequester() {
        InMemoryRelationshipManager relationships = new InMemoryRelationshipManager();
        relationships.addContract("requester-1", "contract-1");
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), relationships,
                new InMemoryVaultManager(), new InMemoryAuditLogger());

        controls.revokeRequesterRelationship("requester-1", "Abuse detected");

        assertThat(relationships.isBlocked("requester-1")).isTrue();
    }

    @Test
    void revokeRequesterRelationship_shredsData() {
        EmergencyControls controls = createEmergencyControlsWithData();

        RevocationResult result = controls.revokeRequesterRelationship("requester-1", "Test");

        assertThat(result.capsulesShredded()).isGreaterThan(0);
    }

    @Test
    void revokeAllRelationships_revokesAllRequesters() {
        // Requirement 347.2: Allow revoking all requester relationships
        InMemoryRelationshipManager relationships = new InMemoryRelationshipManager();
        relationships.addContract("requester-1", "contract-1");
        relationships.addContract("requester-2", "contract-2");
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), relationships,
                new InMemoryVaultManager(), new InMemoryAuditLogger());

        BulkRevocationResult result = controls.revokeAllRelationships("User requested", "CONFIRM_REVOKE_ALL");

        assertThat(result.success()).isTrue();
        assertThat(result.requestersProcessed()).isEqualTo(2);
        assertThat(relationships.isBlocked("requester-1")).isTrue();
        assertThat(relationships.isBlocked("requester-2")).isTrue();
    }

    @Test
    void revokeAllRelationships_requiresConfirmation() {
        EmergencyControls controls = createEmergencyControlsWithData();

        BulkRevocationResult result = controls.revokeAllRelationships("Test", "WRONG_CODE");

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).contains("Invalid confirmation code");
    }

    // ==================== Task 91.3: Vault Purge Tests ====================

    @Test
    void getPurgeWarning_showsStrongWarnings() {
        // Requirement 347.3: Allow category purging with strong warnings
        InMemoryVaultManager vault = new InMemoryVaultManager();
        vault.addCategory("health", 100, 1024 * 1024, 2);
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                vault, new InMemoryAuditLogger());

        PurgeWarning warning = controls.getPurgeWarning("health");

        assertThat(warning.warnings()).isNotEmpty();
        assertThat(warning.warnings().stream().anyMatch(w -> w.contains("IRREVERSIBLE"))).isTrue();
        assertThat(warning.warnings().stream().anyMatch(w -> w.contains("100 items"))).isTrue();
        assertThat(warning.warnings().stream().anyMatch(w -> w.contains("2 active contracts"))).isTrue();
        assertThat(warning.confirmationCode()).isNotNull();
    }

    @Test
    void purgeCategory_requiresCorrectConfirmation() {
        InMemoryVaultManager vault = new InMemoryVaultManager();
        vault.addCategory("health", 100, 1024 * 1024, 0);
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                vault, new InMemoryAuditLogger());

        PurgeResult result = controls.purgeCategory("health", "WRONG_CODE");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid confirmation");
        assertThat(vault.isPurged("health")).isFalse();
    }

    @Test
    void purgeCategory_deletesDataWithCorrectConfirmation() {
        InMemoryVaultManager vault = new InMemoryVaultManager();
        vault.addCategory("health", 100, 1024 * 1024, 0);
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                vault, new InMemoryAuditLogger());

        PurgeWarning warning = controls.getPurgeWarning("health");
        PurgeResult result = controls.purgeCategory("health", warning.confirmationCode());

        assertThat(result.success()).isTrue();
        assertThat(result.itemsShredded()).isEqualTo(100);
        assertThat(vault.isPurged("health")).isTrue();
    }

    @Test
    void purgeCategory_clearsOdxEntries() {
        InMemoryVaultManager vault = new InMemoryVaultManager();
        vault.addCategory("media", 50, 1024, 0);
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                vault, new InMemoryAuditLogger());

        PurgeWarning warning = controls.getPurgeWarning("media");
        PurgeResult result = controls.purgeCategory("media", warning.confirmationCode());

        assertThat(result.odxEntriesCleared()).isGreaterThan(0);
    }

    @Test
    void purgeAllData_purgesEverything() {
        InMemoryVaultManager vault = new InMemoryVaultManager();
        vault.addCategory("health", 100, 1024, 0);
        vault.addCategory("media", 200, 2048, 0);
        vault.setOdxEntries(50);
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                vault, new InMemoryAuditLogger());

        BulkPurgeResult result = controls.purgeAllData("CONFIRM_PURGE_ALL_DATA");

        assertThat(result.success()).isTrue();
        assertThat(result.categoriesPurged()).isEqualTo(2);
        assertThat(result.totalItemsShredded()).isEqualTo(300);
    }

    @Test
    void purgeAllData_requiresConfirmation() {
        EmergencyControls controls = createEmergencyControlsWithData();

        BulkPurgeResult result = controls.purgeAllData("WRONG_CODE");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid confirmation");
    }

    // ==================== Audit Trail Tests ====================

    @Test
    void actionHistory_tracksAllActions() {
        // Requirement 347.4: Audit logging
        EmergencyControls controls = createEmergencyControlsWithData();

        controls.emergencyStop("Test stop");
        controls.revokeRequesterRelationship("requester-1", "Test revoke");

        List<EmergencyAction> history = controls.getActionHistory();

        assertThat(history).hasSizeGreaterThanOrEqualTo(2);
        assertThat(history.stream().map(EmergencyAction::type))
                .contains(EmergencyActionType.EMERGENCY_STOP, EmergencyActionType.REVOKE_RELATIONSHIP);
    }

    @Test
    void actionHistory_orderedByTimestampDescending() {
        EmergencyControls controls = createEmergencyControlsWithData();

        controls.emergencyStop("First");
        controls.revokeRequesterRelationship("requester-1", "Second");

        List<EmergencyAction> history = controls.getActionHistory();

        for (int i = 0; i < history.size() - 1; i++) {
            assertThat(history.get(i).timestamp())
                    .isAfterOrEqualTo(history.get(i + 1).timestamp());
        }
    }

    @Test
    void getAction_retrievesSpecificAction() {
        EmergencyControls controls = createEmergencyControls();

        EmergencyStopResult stopResult = controls.emergencyStop("Test");
        EmergencyAction action = controls.getAction(stopResult.actionId());

        assertThat(action).isNotNull();
        assertThat(action.actionId()).isEqualTo(stopResult.actionId());
        assertThat(action.type()).isEqualTo(EmergencyActionType.EMERGENCY_STOP);
    }

    // ==================== Recovery Tests ====================

    @Test
    void recoveryOptions_showsResumeWhenStopped() {
        // Requirement 347.5: Recovery
        EmergencyControls controls = createEmergencyControls();
        controls.emergencyStop("Test");

        RecoveryOptions options = controls.getRecoveryOptions();

        assertThat(options.emergencyStopActive()).isTrue();
        assertThat(options.options()).extracting(RecoveryOption::id).contains("RESUME_SHARING");
    }

    @Test
    void recoveryOptions_showsRecentActions() {
        EmergencyControls controls = createEmergencyControlsWithData();
        controls.emergencyStop("Test");

        RecoveryOptions options = controls.getRecoveryOptions();

        assertThat(options.recentActions()).isNotEmpty();
    }

    // ==================== Edge Case Tests ====================

    @Test
    void constructor_rejectsNullSharingController() {
        assertThatThrownBy(() -> new EmergencyControls(
                null, new InMemoryRelationshipManager(),
                new InMemoryVaultManager(), new InMemoryAuditLogger()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void constructor_rejectsNullRelationshipManager() {
        assertThatThrownBy(() -> new EmergencyControls(
                new InMemorySharingController(), null,
                new InMemoryVaultManager(), new InMemoryAuditLogger()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void constructor_rejectsNullVaultManager() {
        assertThatThrownBy(() -> new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                null, new InMemoryAuditLogger()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void constructor_rejectsNullAuditLogger() {
        assertThatThrownBy(() -> new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                new InMemoryVaultManager(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void emergencyStop_rejectsNullReason() {
        EmergencyControls controls = createEmergencyControls();

        assertThatThrownBy(() -> controls.emergencyStop(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void revokeRequesterRelationship_rejectsNullRequesterId() {
        EmergencyControls controls = createEmergencyControls();

        assertThatThrownBy(() -> controls.revokeRequesterRelationship(null, "Test"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getPurgeWarning_rejectsNullCategory() {
        EmergencyControls controls = createEmergencyControls();

        assertThatThrownBy(() -> controls.getPurgeWarning(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Property
    void emergencyStop_alwaysLogsAction(@ForAll("reasons") String reason) {
        // Property: Every emergency stop should be logged
        InMemoryAuditLogger logger = new InMemoryAuditLogger();
        EmergencyControls controls = new EmergencyControls(
                new InMemorySharingController(), new InMemoryRelationshipManager(),
                new InMemoryVaultManager(), logger);

        controls.emergencyStop(reason);

        assertThat(logger.getActions()).hasSize(1);
        assertThat(logger.getActions().get(0).reason()).isEqualTo(reason);
    }

    @Property
    void emergencyStop_actionIdIsUnique(@ForAll("reasons") String reason1, @ForAll("reasons") String reason2) {
        // Property: Each action should have a unique ID
        EmergencyControls controls = createEmergencyControls();

        EmergencyStopResult result1 = controls.emergencyStop(reason1);
        controls.resumeSharing("CONFIRM_RESUME");
        EmergencyStopResult result2 = controls.emergencyStop(reason2);

        assertThat(result1.actionId()).isNotEqualTo(result2.actionId());
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<String> reasons() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(100);
    }
}

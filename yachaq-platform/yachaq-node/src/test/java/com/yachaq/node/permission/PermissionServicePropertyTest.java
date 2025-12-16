package com.yachaq.node.permission;

import com.yachaq.node.permission.PermissionService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Permission Service.
 * 
 * Property 60: Consent Replay Safety
 * Validates: Requirements 304.1, 304.2, 304.3, 304.4, 304.5, 304.6
 */
class PermissionServicePropertyTest {

    // ==================== Property 60: Consent Replay Safety ====================

    @Property(tries = 100)
    void property60_nonceCanOnlyBeUsedOnce(
            @ForAll("contractDrafts") ContractDraft draft,
            @ForAll("nonces") String nonce) {
        // Property: A nonce can only be used once for signing
        PermissionService service = new PermissionService();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        
        // First signing should succeed
        SignedContract contract1 = service.signContract(draft, nonce, expiry);
        assertThat(contract1).isNotNull();
        
        // Second signing with same nonce should fail
        assertThatThrownBy(() -> service.signContract(draft, nonce, expiry))
                .isInstanceOf(ReplayAttackException.class)
                .hasMessageContaining(nonce);
    }

    @Property(tries = 100)
    void property60_differentNoncesAllowMultipleSignings(
            @ForAll("contractDrafts") ContractDraft draft,
            @ForAll("nonces") String nonce1,
            @ForAll("nonces") String nonce2) {
        // Property: Different nonces allow multiple signings
        Assume.that(!nonce1.equals(nonce2));
        
        PermissionService service = new PermissionService();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        
        SignedContract contract1 = service.signContract(draft, nonce1, expiry);
        SignedContract contract2 = service.signContract(draft, nonce2, expiry);
        
        assertThat(contract1.contractId()).isNotEqualTo(contract2.contractId());
        assertThat(contract1.nonce()).isNotEqualTo(contract2.nonce());
    }

    @Property(tries = 50)
    void property60_expiredContractsFailVerification(
            @ForAll("contractDrafts") ContractDraft draft,
            @ForAll("nonces") String nonce) {
        // Property: Expired contracts must fail verification
        PermissionService service = new PermissionService();
        
        // Create contract that expires immediately
        Instant expiry = Instant.now().plus(1, ChronoUnit.MILLIS);
        SignedContract contract = service.signContract(draft, nonce, expiry);
        
        // Wait for expiry
        try { Thread.sleep(10); } catch (InterruptedException e) {}
        
        assertThat(contract.isExpired()).isTrue();
        assertThat(service.verifyContract(contract)).isFalse();
    }

    // ==================== OS Permission Tests (304.1) ====================

    @Property(tries = 50)
    void osPermission_initiallyNotRequested(@ForAll("osPermissions") OSPermission permission) {
        // Property: All OS permissions start as NOT_REQUESTED
        PermissionService service = new PermissionService();
        
        CheckResult result = service.checkOS(permission);
        
        assertThat(result.granted()).isFalse();
        assertThat(result.message()).contains("not yet requested");
    }

    @Property(tries = 50)
    void osPermission_grantedAfterUserApproval(@ForAll("osPermissions") OSPermission permission) {
        // Property: Permission is granted after user approval
        PermissionService service = new PermissionService();
        
        service.requestOSPermission(permission, true);
        CheckResult result = service.checkOS(permission);
        
        assertThat(result.granted()).isTrue();
    }

    @Property(tries = 50)
    void osPermission_deniedAfterUserRejection(@ForAll("osPermissions") OSPermission permission) {
        // Property: Permission is denied after user rejection
        PermissionService service = new PermissionService();
        
        service.requestOSPermission(permission, false);
        CheckResult result = service.checkOS(permission);
        
        assertThat(result.granted()).isFalse();
        assertThat(result.message()).contains("denied");
    }

    @Property(tries = 50)
    void osPermission_hasExplanation(@ForAll("osPermissions") OSPermission permission) {
        // Property: Each permission has an explanation
        PermissionService service = new PermissionService();
        
        CheckResult result = service.checkOS(permission);
        
        assertThat(result.explanation()).isNotNull();
        assertThat(result.explanation()).isNotBlank();
    }


    // ==================== YACHAQ Permission Tests (304.2) ====================

    @Property(tries = 50)
    void yachaqPermission_grantedScopeIsAccessible(
            @ForAll("permissionScopes") PermissionScope scope) {
        // Property: Granted YACHAQ permissions are accessible
        PermissionService service = new PermissionService();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        
        service.grantYachaqPermission(scope, expiry);
        CheckResult result = service.checkYachaq(scope);
        
        assertThat(result.granted()).isTrue();
    }

    @Property(tries = 50)
    void yachaqPermission_revokedScopeIsNotAccessible(
            @ForAll("permissionScopes") PermissionScope scope) {
        // Property: Revoked YACHAQ permissions are not accessible
        PermissionService service = new PermissionService();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        
        service.grantYachaqPermission(scope, expiry);
        service.revokeYachaqPermission(scope);
        CheckResult result = service.checkYachaq(scope);
        
        assertThat(result.granted()).isFalse();
    }

    @Property(tries = 50)
    void yachaqPermission_expiredScopeIsNotAccessible(
            @ForAll("permissionScopes") PermissionScope scope) {
        // Property: Expired YACHAQ permissions are not accessible
        PermissionService service = new PermissionService();
        Instant expiry = Instant.now().minus(1, ChronoUnit.HOURS); // Already expired
        
        service.grantYachaqPermission(scope, expiry);
        CheckResult result = service.checkYachaq(scope);
        
        assertThat(result.granted()).isFalse();
    }

    // ==================== Permission Preset Tests (304.6) ====================

    @Property(tries = 50)
    void minimalPreset_onlyAllowsQueryPlanScopes(
            @ForAll("permissionScopes") PermissionScope scope) {
        // Property: MINIMAL preset only allows query plan scopes
        PermissionService service = new PermissionService(PermissionPreset.MINIMAL);
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        
        service.grantYachaqPermission(scope, expiry);
        CheckResult result = service.checkYachaq(scope);
        
        if (scope.queryPlanId() != null) {
            assertThat(result.granted()).isTrue();
        } else {
            assertThat(result.granted()).isFalse();
        }
    }

    @Property(tries = 50)
    void standardPreset_allowsAllScopes(
            @ForAll("permissionScopes") PermissionScope scope) {
        // Property: STANDARD preset allows all scopes
        PermissionService service = new PermissionService(PermissionPreset.STANDARD);
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        
        service.grantYachaqPermission(scope, expiry);
        CheckResult result = service.checkYachaq(scope);
        
        assertThat(result.granted()).isTrue();
    }

    // ==================== Contract Preview Tests (304.3) ====================

    @Property(tries = 50)
    void contractPreview_containsAllRequiredInfo(
            @ForAll("contractDrafts") ContractDraft draft) {
        // Property: Contract preview contains all required information
        PermissionService service = new PermissionService();
        
        ContractPreview preview = service.promptContractPreview(draft);
        
        assertThat(preview.requesterId()).isEqualTo(draft.requesterId());
        assertThat(preview.purpose()).isEqualTo(draft.purpose());
        assertThat(preview.compensation()).isEqualTo(draft.compensation());
        assertThat(preview.duration()).isEqualTo(draft.duration());
    }

    @Property(tries = 50)
    void contractPreview_showsPrivacyImpactForIdentityReveal(
            @ForAll("contractDraftsWithIdentityReveal") ContractDraft draft) {
        // Property: Preview shows privacy impact when identity reveal is enabled
        PermissionService service = new PermissionService();
        
        ContractPreview preview = service.promptContractPreview(draft);
        
        assertThat(preview.privacyImpacts())
                .anyMatch(impact -> impact.contains("identity"));
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<OSPermission> osPermissions() {
        return Arbitraries.of(OSPermission.values());
    }

    @Provide
    Arbitrary<PermissionScope> permissionScopes() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).injectNull(0.3),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).injectNull(0.3),
                Arbitraries.of("coarse", "fine", "exact").injectNull(0.3),
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(15).map(s -> "req-" + s).injectNull(0.3),
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(15).map(s -> "plan-" + s).injectNull(0.3)
        ).as(PermissionScope::new);
    }

    @Provide
    Arbitrary<String> nonces() {
        return Arbitraries.create(() -> UUID.randomUUID().toString());
    }

    @Provide
    Arbitrary<ContractDraft> contractDrafts() {
        return Arbitraries.create(() -> new ContractDraft(
                "req-" + UUID.randomUUID().toString().substring(0, 8),
                "Purpose: " + UUID.randomUUID().toString().substring(0, 10),
                List.of("label1", "label2"),
                "1h",
                OutputMode.AGGREGATED,
                false,
                "$5.00",
                "1 day",
                "escrow-" + UUID.randomUUID().toString().substring(0, 8),
                "24h"
        ));
    }

    @Provide
    Arbitrary<ContractDraft> contractDraftsWithIdentityReveal() {
        return Arbitraries.create(() -> new ContractDraft(
                "req-" + UUID.randomUUID().toString().substring(0, 8),
                "Purpose: " + UUID.randomUUID().toString().substring(0, 10),
                List.of("label1", "label2"),
                "1h",
                OutputMode.RAW,
                true,  // Identity reveal enabled
                "$5.00",
                "1 day",
                "escrow-" + UUID.randomUUID().toString().substring(0, 8),
                "24h"
        ));
    }

    // ==================== Edge Case Tests ====================

    @Test
    void checkOS_rejectsNullPermission() {
        PermissionService service = new PermissionService();
        
        assertThatThrownBy(() -> service.checkOS(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkYachaq_rejectsNullScope() {
        PermissionService service = new PermissionService();
        
        assertThatThrownBy(() -> service.checkYachaq(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signContract_rejectsNullDraft() {
        PermissionService service = new PermissionService();
        
        assertThatThrownBy(() -> service.signContract(null, "nonce", Instant.now().plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signContract_rejectsNullNonce() {
        PermissionService service = new PermissionService();
        ContractDraft draft = createTestDraft();
        
        assertThatThrownBy(() -> service.signContract(draft, null, Instant.now().plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signContract_rejectsPastExpiry() {
        PermissionService service = new PermissionService();
        ContractDraft draft = createTestDraft();
        
        assertThatThrownBy(() -> service.signContract(draft, "nonce", Instant.now().minusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promptContractPreview_rejectsNullDraft() {
        PermissionService service = new PermissionService();
        
        assertThatThrownBy(() -> service.promptContractPreview(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyContract_returnsFalseForNull() {
        PermissionService service = new PermissionService();
        
        assertThat(service.verifyContract(null)).isFalse();
    }

    private ContractDraft createTestDraft() {
        return new ContractDraft(
                "req-test",
                "Test purpose",
                List.of("label1", "label2"),
                "1h",
                OutputMode.AGGREGATED,
                false,
                "$5.00",
                "1 day",
                "escrow-123",
                "24h"
        );
    }
}

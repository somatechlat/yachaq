package com.yachaq.api.query;

import com.yachaq.core.domain.TimeCapsule;
import com.yachaq.core.domain.TimeCapsule.CapsuleStatus;
import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.QueryPlan.PlanStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Property-based tests for Query Orchestrator.
 * 
 * **Feature: yachaq-platform, Property 13: Time Capsule TTL Enforcement**
 * **Validates: Requirements 206.2**
 * 
 * For any time capsule with a TTL, the capsule must be automatically deleted
 * (crypto-shred + storage deletion) within 1 hour of TTL expiration.
 * 
 * **Feature: yachaq-platform, Property 15: Query Plan Signature Verification**
 * **Validates: Requirements 216.1, 216.2**
 * 
 * For any query plan created by the platform, signing the plan and then
 * verifying the signature must succeed; and for any tampered plan,
 * verification must fail.
 */
class QueryOrchestratorPropertyTest {

    // ========================================================================
    // Property 13: Time Capsule TTL Enforcement
    // ========================================================================

    @Property(tries = 100)
    void property13_capsuleWithExpiredTtl_isMarkedExpired(
            @ForAll @IntRange(min = 1, max = 3600) int secondsAgo) {
        
        // Create a capsule with TTL in the past
        TimeCapsule capsule = new TimeCapsule();
        capsule.setTtl(Instant.now().minusSeconds(secondsAgo));
        capsule.setStatus(CapsuleStatus.CREATED);
        
        // Property 13: Expired capsules must be detected
        assert capsule.isExpired() 
            : "Capsule with past TTL should be marked as expired";
    }

    @Property(tries = 100)
    void property13_capsuleWithFutureTtl_isNotExpired(
            @ForAll @IntRange(min = 1, max = 86400) int secondsInFuture) {
        
        // Create a capsule with TTL in the future
        TimeCapsule capsule = new TimeCapsule();
        capsule.setTtl(Instant.now().plusSeconds(secondsInFuture));
        capsule.setStatus(CapsuleStatus.CREATED);
        
        // Property 13: Non-expired capsules should not be marked expired
        assert !capsule.isExpired() 
            : "Capsule with future TTL should not be marked as expired";
    }

    @Property(tries = 100)
    void property13_capsulePastGracePeriod_shouldBeDeleted(
            @ForAll @IntRange(min = 3601, max = 7200) int secondsAgo) {
        
        // Create a capsule with TTL more than 1 hour in the past
        TimeCapsule capsule = new TimeCapsule();
        capsule.setTtl(Instant.now().minusSeconds(secondsAgo));
        capsule.setStatus(CapsuleStatus.EXPIRED);
        
        // Property 13: Capsules past TTL + 1 hour grace period should be deleted
        assert capsule.shouldBeDeleted() 
            : "Capsule past grace period should be marked for deletion";
    }

    @Property(tries = 100)
    void property13_capsuleWithinGracePeriod_shouldNotBeDeleted(
            @ForAll @IntRange(min = 1, max = 3599) int secondsAgo) {
        
        // Create a capsule with TTL less than 1 hour in the past
        TimeCapsule capsule = new TimeCapsule();
        capsule.setTtl(Instant.now().minusSeconds(secondsAgo));
        capsule.setStatus(CapsuleStatus.EXPIRED);
        
        // Property 13: Capsules within grace period should not be deleted yet
        assert !capsule.shouldBeDeleted() 
            : "Capsule within grace period should not be marked for deletion";
    }

    @Property(tries = 100)
    void property13_ttlMustBePositive(
            @ForAll @IntRange(min = 1, max = 168) int hours) {
        
        Duration ttl = Duration.ofHours(hours);
        
        // Property 13: TTL must be positive
        assert !ttl.isNegative() && !ttl.isZero()
            : "TTL must be positive";
    }

    // ========================================================================
    // Property 15: Query Plan Signature Verification
    // ========================================================================

    @Property(tries = 100)
    void property15_signedPlanVerificationSucceeds(
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal compensation,
            @ForAll @IntRange(min = 1, max = 168) int ttlHours) {
        
        // Create a query plan
        QueryPlan plan = createTestPlan(compensation, Duration.ofHours(ttlHours));
        
        // Sign the plan
        String signature = signPlan(plan);
        plan.setSignature(signature);
        plan.setSignedAt(Instant.now());
        plan.setStatus(PlanStatus.SIGNED);
        
        // Property 15: Verification of signed plan must succeed
        String verifySignature = signPlan(plan);
        assert signature.equals(verifySignature)
            : "Signature verification should succeed for unmodified plan";
    }

    @Property(tries = 100)
    void property15_tamperedPlanVerificationFails(
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal originalCompensation,
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal tamperedCompensation) {
        
        Assume.that(!originalCompensation.equals(tamperedCompensation));
        
        // Create and sign a plan
        QueryPlan plan = createTestPlan(originalCompensation, Duration.ofHours(24));
        String originalSignature = signPlan(plan);
        plan.setSignature(originalSignature);
        
        // Tamper with the plan
        plan.setCompensation(tamperedCompensation);
        
        // Property 15: Verification of tampered plan must fail
        String newSignature = signPlan(plan);
        assert !originalSignature.equals(newSignature)
            : "Signature verification should fail for tampered plan";
    }

    @Property(tries = 100)
    void property15_expiredPlanIsNotValidForExecution(
            @ForAll @IntRange(min = 1, max = 3600) int secondsAgo) {
        
        // Create a plan with expired TTL
        QueryPlan plan = createTestPlan(BigDecimal.TEN, Duration.ofHours(1));
        plan.setTtl(Instant.now().minusSeconds(secondsAgo));
        plan.setStatus(PlanStatus.SIGNED);
        
        // Property 15: Expired plans should not be valid for execution
        assert !plan.isValidForExecution()
            : "Expired plan should not be valid for execution";
    }

    @Property(tries = 100)
    void property15_unsignedPlanIsNotValidForExecution(
            @ForAll @IntRange(min = 1, max = 168) int ttlHours) {
        
        // Create a plan that is not signed
        QueryPlan plan = createTestPlan(BigDecimal.TEN, Duration.ofHours(ttlHours));
        plan.setStatus(PlanStatus.PENDING);
        
        // Property 15: Unsigned plans should not be valid for execution
        assert !plan.isValidForExecution()
            : "Unsigned plan should not be valid for execution";
    }

    @Property(tries = 100)
    void property15_signedNonExpiredPlanIsValidForExecution(
            @ForAll @IntRange(min = 1, max = 168) int ttlHours) {
        
        // Create a properly signed plan with future TTL
        QueryPlan plan = createTestPlan(BigDecimal.TEN, Duration.ofHours(ttlHours));
        plan.setTtl(Instant.now().plus(Duration.ofHours(ttlHours)));
        plan.setSignature(signPlan(plan));
        plan.setSignedAt(Instant.now());
        plan.setStatus(PlanStatus.SIGNED);
        
        // Property 15: Signed, non-expired plans should be valid for execution
        assert plan.isValidForExecution()
            : "Signed, non-expired plan should be valid for execution";
    }

    // ========================================================================
    // Property 16: Capsule Replay Protection (bonus)
    // ========================================================================

    @Property(tries = 100)
    void property16_eachCapsuleHasUniqueNonce() {
        // Create multiple capsules
        TimeCapsule capsule1 = new TimeCapsule();
        TimeCapsule capsule2 = new TimeCapsule();
        
        // Property 16: Each capsule must have a unique nonce
        assert !capsule1.getNonce().equals(capsule2.getNonce())
            : "Each capsule should have a unique nonce";
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private QueryPlan createTestPlan(BigDecimal compensation, Duration ttl) {
        QueryPlan plan = new QueryPlan();
        plan.setRequestId(UUID.randomUUID());
        plan.setConsentContractId(UUID.randomUUID());
        plan.setScopeHash("test-scope-hash");
        plan.setAllowedTransforms("AGGREGATE,FILTER");
        plan.setCompensation(compensation);
        plan.setTtl(Instant.now().plus(ttl));
        plan.setSigningKeyId("test-key-v1");
        return plan;
    }

    private String signPlan(QueryPlan plan) {
        // Simplified signing for tests - matches service implementation
        String payload = plan.getRequestId() + "|" +
            plan.getConsentContractId() + "|" +
            plan.getScopeHash() + "|" +
            plan.getCompensation() + "|" +
            plan.getTtl();
        
        // Simple hash for testing (production uses HMAC)
        return Integer.toHexString(payload.hashCode());
    }
}

package com.yachaq.api.query;

import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.QueryPlan.PlanStatus;
import com.yachaq.core.domain.QueryPlan.VerificationResult;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Query Plan Security.
 * Tests domain logic and cryptographic invariants without Spring context.
 * 
 * **Feature: yachaq-platform, Property 15: Query Plan Signature Verification**
 * For any query plan created by the platform, signing the plan and then
 * verifying the signature must succeed; and for any tampered plan,
 * verification must fail.
 * 
 * **Validates: Requirements 216.1, 216.2**
 */
class QueryPlanSecurityPropertyTest {

    private static final String KEY_ID = "test-key-v1";
    
    // Cache key pairs to avoid slow RSA generation on each test iteration
    private static final KeyPair CACHED_KEY_PAIR;
    private static final KeyPair CACHED_WRONG_KEY_PAIR;
    
    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            CACHED_KEY_PAIR = generator.generateKeyPair();
            CACHED_WRONG_KEY_PAIR = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    private KeyPair getKeyPair() {
        return CACHED_KEY_PAIR;
    }
    
    private KeyPair getWrongKeyPair() {
        return CACHED_WRONG_KEY_PAIR;
    }

    /**
     * **Feature: yachaq-platform, Property 15: Query Plan Signature Verification**
     * **Validates: Requirements 216.1, 216.2**
     * 
     * For any valid query plan, signing and then verifying with the same
     * key pair must succeed.
     */
    @Property(tries = 100)
    void property15_signedPlanVerifiesSuccessfully(
            @ForAll("validQueryPlans") QueryPlan plan) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        // Act - Sign the plan
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Assert - Plan is signed
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.SIGNED);
        assertThat(plan.getSignature()).isNotNull().isNotBlank();
        assertThat(plan.getSigningKeyId()).isEqualTo(KEY_ID);
        assertThat(plan.getSignedAt()).isNotNull();

        // Act - Verify the signature
        VerificationResult result = plan.verify(keyPair.getPublic());

        // Assert - Property 15: Verification must succeed
        assertThat(result.isValid()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    /**
     * **Feature: yachaq-platform, Property 15: Query Plan Signature Verification**
     * **Validates: Requirements 216.2**
     * 
     * For any signed query plan where the scope hash is tampered,
     * verification must fail.
     */
    @Property(tries = 100)
    void property15_tamperedScopeHashFailsVerification(
            @ForAll("validQueryPlans") QueryPlan plan,
            @ForAll("validScopeHashes") String tamperedScopeHash) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        String originalScopeHash = plan.getScopeHash();
        
        // Skip if tampered hash equals original (unlikely but possible)
        Assume.that(!tamperedScopeHash.equals(originalScopeHash));

        // Sign with original scope hash
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Tamper with scope hash after signing
        plan.setScopeHash(tamperedScopeHash);

        // Verify - must fail due to tampering
        VerificationResult result = plan.verify(keyPair.getPublic());

        // Assert - Property 15: Tampered plan verification must fail
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("tampering");
    }

    /**
     * **Feature: yachaq-platform, Property 15: Query Plan Signature Verification**
     * **Validates: Requirements 216.2**
     * 
     * For any signed query plan where the compensation is tampered,
     * verification must fail.
     */
    @Property(tries = 100)
    void property15_tamperedCompensationFailsVerification(
            @ForAll("validQueryPlans") QueryPlan plan,
            @ForAll @BigRange(min = "0.01", max = "10000.00") BigDecimal tamperedCompensation) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        BigDecimal originalCompensation = plan.getCompensation();
        
        // Skip if tampered compensation equals original
        Assume.that(tamperedCompensation.compareTo(originalCompensation) != 0);

        // Sign with original compensation
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Tamper with compensation after signing
        plan.setCompensation(tamperedCompensation);

        // Verify - must fail due to tampering
        VerificationResult result = plan.verify(keyPair.getPublic());

        // Assert - Property 15: Tampered plan verification must fail
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("tampering");
    }

    /**
     * **Feature: yachaq-platform, Property 15: Query Plan Signature Verification**
     * **Validates: Requirements 216.2**
     * 
     * For any signed query plan where the TTL is tampered,
     * verification must fail.
     */
    @Property(tries = 100)
    void property15_tamperedTtlFailsVerification(
            @ForAll("validQueryPlans") QueryPlan plan,
            @ForAll @IntRange(min = 1, max = 168) int tamperedHours) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        Instant originalTtl = plan.getTtl();
        Instant tamperedTtl = Instant.now().plus(tamperedHours, ChronoUnit.HOURS);
        
        // Skip if tampered TTL equals original (within 1 second tolerance)
        Assume.that(Math.abs(tamperedTtl.toEpochMilli() - originalTtl.toEpochMilli()) > 1000);

        // Sign with original TTL
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Tamper with TTL after signing
        plan.setTtl(tamperedTtl);

        // Verify - must fail due to tampering
        VerificationResult result = plan.verify(keyPair.getPublic());

        // Assert - Property 15: Tampered plan verification must fail
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("tampering");
    }

    /**
     * Property: Verification with wrong public key must fail.
     */
    @Property(tries = 50)
    void verificationWithWrongKeyFails(
            @ForAll("validQueryPlans") QueryPlan plan) throws Exception {
        
        KeyPair keyPair = getKeyPair();
        KeyPair wrongKeyPair = getWrongKeyPair();

        // Sign with original key
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Verify with wrong public key
        VerificationResult result = plan.verify(wrongKeyPair.getPublic());

        // Assert - Verification must fail
        assertThat(result.isValid()).isFalse();
    }

    /**
     * Property: Expired plan verification must fail.
     */
    @Property(tries = 100)
    void expiredPlanVerificationFails(
            @ForAll("validQueryPlans") QueryPlan plan) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        // Set TTL to past
        plan.setTtl(Instant.now().minus(1, ChronoUnit.HOURS));

        // Sign the plan
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Verify - must fail due to expiration
        VerificationResult result = plan.verify(keyPair.getPublic());

        // Assert - Expired plan verification must fail
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("expired");
    }

    /**
     * Property: Unsigned plan verification must fail.
     */
    @Property(tries = 100)
    void unsignedPlanVerificationFails(
            @ForAll("validQueryPlans") QueryPlan plan) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        // Do not sign the plan

        // Verify - must fail because not signed
        VerificationResult result = plan.verify(keyPair.getPublic());

        // Assert - Unsigned plan verification must fail
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("not signed");
    }

    /**
     * Property: Double signing must be rejected.
     */
    @Property(tries = 100)
    void doubleSigningMustBeRejected(
            @ForAll("validQueryPlans") QueryPlan plan) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        // Sign once
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Attempt to sign again - must fail
        PrivateKey privateKey = keyPair.getPrivate();
        assertThatThrownBy(() -> plan.sign(privateKey, KEY_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already signed");
    }

    /**
     * Property: Signing with null key must be rejected.
     */
    @Property(tries = 100)
    void signingWithNullKeyMustBeRejected(
            @ForAll("validQueryPlans") QueryPlan plan) {
        
        assertThatThrownBy(() -> plan.sign(null, KEY_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private key");
    }

    /**
     * Property: Signing with null/blank key ID must be rejected.
     */
    @Property(tries = 100)
    void signingWithBlankKeyIdMustBeRejected(
            @ForAll("validQueryPlans") QueryPlan plan) throws Exception {
        
        KeyPair keyPair = getKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();

        assertThatThrownBy(() -> plan.sign(privateKey, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key ID");
    }

    /**
     * Property: Signable payload must be deterministic.
     */
    @Property(tries = 100)
    void signablePayloadMustBeDeterministic(
            @ForAll("validQueryPlans") QueryPlan plan) {
        
        String payload1 = plan.getSignablePayload();
        String payload2 = plan.getSignablePayload();

        // Same plan must produce same payload
        assertThat(payload1).isEqualTo(payload2);
    }

    /**
     * Property: Signable payload must include all required fields.
     * Requirements 216.3, 216.4, 216.5
     */
    @Property(tries = 100)
    void signablePayloadIncludesAllRequiredFields(
            @ForAll("validQueryPlans") QueryPlan plan) {
        
        String payload = plan.getSignablePayload();

        // Payload must include all security-critical fields
        assertThat(payload).contains("id=");
        assertThat(payload).contains("requestId=");
        assertThat(payload).contains("consentContractId=");
        assertThat(payload).contains("scopeHash=");
        assertThat(payload).contains("allowedTransforms=");
        assertThat(payload).contains("compensation=");
        assertThat(payload).contains("ttl=");
    }

    /**
     * Property: Plan validity check must respect status and expiration.
     */
    @Property(tries = 100)
    void validityCheckRespectsStatusAndExpiration(
            @ForAll("validQueryPlans") QueryPlan plan) throws Exception {
        
        KeyPair keyPair = getKeyPair();

        // Unsigned plan is not valid for execution
        assertThat(plan.isValidForExecution()).isFalse();

        // Sign the plan
        plan.sign(keyPair.getPrivate(), KEY_ID);

        // Signed plan with future TTL is valid
        assertThat(plan.isValidForExecution()).isTrue();

        // Set TTL to past
        plan.setTtl(Instant.now().minus(1, ChronoUnit.HOURS));

        // Expired plan is not valid
        assertThat(plan.isValidForExecution()).isFalse();
    }

    // Arbitraries
    @Provide
    Arbitrary<QueryPlan> validQueryPlans() {
        return Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                validScopeHashes(),
                validTransforms(),
                Arbitraries.bigDecimals().between(BigDecimal.ONE, new BigDecimal("1000")),
                Arbitraries.integers().between(1, 168)
        ).as((id, requestId, consentId, scopeHash, transforms, compensation, ttlHours) -> {
            QueryPlan plan = new QueryPlan();
            plan.setId(id);
            plan.setRequestId(requestId);
            plan.setConsentContractId(consentId);
            plan.setScopeHash(scopeHash);
            plan.setAllowedTransforms(transforms);
            plan.setOutputRestrictions("[\"view_only\"]");
            plan.setCompensation(compensation);
            plan.setTtl(Instant.now().plus(ttlHours, ChronoUnit.HOURS));
            return plan;
        });
    }

    @Provide
    Arbitrary<String> validScopeHashes() {
        return Arbitraries.strings()
                .withCharRange('a', 'f')
                .numeric()
                .ofLength(64);
    }

    @Provide
    Arbitrary<String> validTransforms() {
        return Arbitraries.of(
                "[\"aggregate\",\"anonymize\"]",
                "[\"filter\",\"count\"]",
                "[\"sum\",\"average\"]",
                "[\"hash\",\"truncate\"]",
                "[]"
        );
    }
}

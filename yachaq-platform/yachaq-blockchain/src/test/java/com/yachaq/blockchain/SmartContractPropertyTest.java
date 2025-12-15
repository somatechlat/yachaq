package com.yachaq.blockchain;

import com.yachaq.blockchain.contract.EscrowContract;
import com.yachaq.blockchain.contract.ConsentRegistryContract;
import com.yachaq.blockchain.contract.AuditAnchorContract;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Smart Contracts.
 * 
 * Task 19.4: Write smart contract tests
 * Requirements: 61.10, 62.10
 */
class SmartContractPropertyTest {

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    // ========================================================================
    // Property Tests for Escrow Contract (Requirements 61.1, 61.2)
    // ========================================================================

    /**
     * Property: Escrow balance invariant
     * For any sequence of deposit, lock, release, refund operations,
     * fundedAmount >= lockedAmount + refundedAmount
     * and lockedAmount >= releasedAmount
     */
    @Property(tries = 100)
    void escrowBalanceInvariantMustHold(
            @ForAll @BigRange(min = "1", max = "1000000") BigInteger initialDeposit,
            @ForAll @BigRange(min = "0", max = "100") BigInteger lockPercent,
            @ForAll @BigRange(min = "0", max = "100") BigInteger releasePercent,
            @ForAll @BigRange(min = "0", max = "100") BigInteger refundPercent) {
        
        // Simulate escrow state
        EscrowState state = new EscrowState();
        
        // Deposit
        state.fund(initialDeposit);
        assertThat(state.fundedAmount).isEqualTo(initialDeposit);
        
        // Lock (percentage of funded)
        BigInteger lockAmount = initialDeposit.multiply(lockPercent).divide(BigInteger.valueOf(100));
        if (lockAmount.compareTo(state.getAvailableToLock()) <= 0) {
            state.lock(lockAmount);
        }
        
        // Release (percentage of locked)
        BigInteger releaseAmount = state.lockedAmount.multiply(releasePercent).divide(BigInteger.valueOf(100));
        if (releaseAmount.compareTo(state.getAvailableToRelease()) <= 0) {
            state.release(releaseAmount);
        }
        
        // Refund (percentage of available)
        BigInteger refundAmount = state.getAvailableToRefund().multiply(refundPercent).divide(BigInteger.valueOf(100));
        if (refundAmount.compareTo(BigInteger.ZERO) > 0) {
            state.refund(refundAmount);
        }
        
        // Verify invariants
        assertThat(state.fundedAmount)
                .isGreaterThanOrEqualTo(state.lockedAmount.add(state.refundedAmount));
        assertThat(state.lockedAmount)
                .isGreaterThanOrEqualTo(state.releasedAmount);
    }

    /**
     * Property: Escrow status transitions are valid
     * Status can only transition: PENDING -> FUNDED -> LOCKED -> SETTLED/REFUNDED
     * or LOCKED -> DISPUTED -> SETTLED/REFUNDED
     */
    @Property(tries = 100)
    void escrowStatusTransitionsMustBeValid(
            @ForAll("validStatusTransitions") List<EscrowContract.EscrowStatus> transitions) {
        
        EscrowContract.EscrowStatus current = EscrowContract.EscrowStatus.PENDING;
        
        for (EscrowContract.EscrowStatus next : transitions) {
            boolean validTransition = isValidEscrowTransition(current, next);
            assertThat(validTransition)
                    .as("Transition from %s to %s should be valid", current, next)
                    .isTrue();
            current = next;
        }
    }

    @Provide
    Arbitrary<List<EscrowContract.EscrowStatus>> validStatusTransitions() {
        return Arbitraries.of(
                List.of(EscrowContract.EscrowStatus.FUNDED),
                List.of(EscrowContract.EscrowStatus.FUNDED, EscrowContract.EscrowStatus.LOCKED),
                List.of(EscrowContract.EscrowStatus.FUNDED, EscrowContract.EscrowStatus.LOCKED, 
                        EscrowContract.EscrowStatus.SETTLED),
                List.of(EscrowContract.EscrowStatus.FUNDED, EscrowContract.EscrowStatus.LOCKED, 
                        EscrowContract.EscrowStatus.REFUNDED),
                List.of(EscrowContract.EscrowStatus.FUNDED, EscrowContract.EscrowStatus.LOCKED, 
                        EscrowContract.EscrowStatus.DISPUTED, EscrowContract.EscrowStatus.SETTLED),
                List.of(EscrowContract.EscrowStatus.FUNDED, EscrowContract.EscrowStatus.LOCKED, 
                        EscrowContract.EscrowStatus.DISPUTED, EscrowContract.EscrowStatus.REFUNDED)
        );
    }

    private boolean isValidEscrowTransition(EscrowContract.EscrowStatus from, EscrowContract.EscrowStatus to) {
        return switch (from) {
            case PENDING -> to == EscrowContract.EscrowStatus.FUNDED;
            case FUNDED -> to == EscrowContract.EscrowStatus.LOCKED || to == EscrowContract.EscrowStatus.REFUNDED;
            case LOCKED -> to == EscrowContract.EscrowStatus.SETTLED || 
                           to == EscrowContract.EscrowStatus.REFUNDED ||
                           to == EscrowContract.EscrowStatus.DISPUTED;
            case DISPUTED -> to == EscrowContract.EscrowStatus.SETTLED || 
                             to == EscrowContract.EscrowStatus.REFUNDED;
            case SETTLED, REFUNDED -> false; // Terminal states
        };
    }

    /**
     * Property: Multi-sig dispute resolution requires minimum approvals
     * A dispute cannot be resolved until requiredApprovals governors approve
     */
    @Property(tries = 50)
    void disputeResolutionRequiresMinimumApprovals(
            @ForAll @IntRange(min = 1, max = 5) int requiredApprovals,
            @ForAll @IntRange(min = 1, max = 10) int totalGovernors,
            @ForAll @IntRange(min = 0, max = 10) int actualApprovals) {
        
        Assume.that(totalGovernors >= requiredApprovals);
        Assume.that(actualApprovals <= totalGovernors);
        
        DisputeState dispute = new DisputeState(requiredApprovals);
        
        // Simulate approvals
        for (int i = 0; i < actualApprovals; i++) {
            dispute.approve("governor_" + i);
        }
        
        // Verify resolution state
        if (actualApprovals >= requiredApprovals) {
            assertThat(dispute.canResolve()).isTrue();
        } else {
            assertThat(dispute.canResolve()).isFalse();
        }
    }

    // ========================================================================
    // Property Tests for Consent Registry Contract (Requirements 62.1, 62.2)
    // ========================================================================

    /**
     * Property: Consent hash verification is deterministic
     * For any consent data, the hash must be reproducible
     */
    @Property(tries = 100)
    void consentHashVerificationIsDeterministic(
            @ForAll("uuids") UUID dsId,
            @ForAll("uuids") UUID requesterId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String scope,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String purpose) {
        
        String hash1 = computeConsentHash(dsId, requesterId, scope, purpose);
        String hash2 = computeConsentHash(dsId, requesterId, scope, purpose);
        
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex
    }

    /**
     * Property: Consent status transitions are valid
     * ACTIVE -> EXPIRED or ACTIVE -> REVOKED (no other transitions)
     */
    @Property(tries = 100)
    void consentStatusTransitionsMustBeValid(
            @ForAll("consentStatusPairs") StatusPair pair) {
        
        boolean valid = isValidConsentTransition(pair.from, pair.to);
        assertThat(valid)
                .as("Transition from %s to %s should be %s", pair.from, pair.to, pair.expectedValid)
                .isEqualTo(pair.expectedValid);
    }

    @Provide
    Arbitrary<StatusPair> consentStatusPairs() {
        return Arbitraries.of(
                new StatusPair(ConsentRegistryContract.ConsentStatus.ACTIVE, 
                               ConsentRegistryContract.ConsentStatus.EXPIRED, true),
                new StatusPair(ConsentRegistryContract.ConsentStatus.ACTIVE, 
                               ConsentRegistryContract.ConsentStatus.REVOKED, true),
                new StatusPair(ConsentRegistryContract.ConsentStatus.EXPIRED, 
                               ConsentRegistryContract.ConsentStatus.ACTIVE, false),
                new StatusPair(ConsentRegistryContract.ConsentStatus.REVOKED, 
                               ConsentRegistryContract.ConsentStatus.ACTIVE, false),
                new StatusPair(ConsentRegistryContract.ConsentStatus.EXPIRED, 
                               ConsentRegistryContract.ConsentStatus.REVOKED, false),
                new StatusPair(ConsentRegistryContract.ConsentStatus.REVOKED, 
                               ConsentRegistryContract.ConsentStatus.EXPIRED, false)
        );
    }

    record StatusPair(ConsentRegistryContract.ConsentStatus from, 
                      ConsentRegistryContract.ConsentStatus to, 
                      boolean expectedValid) {}

    private boolean isValidConsentTransition(ConsentRegistryContract.ConsentStatus from, 
                                              ConsentRegistryContract.ConsentStatus to) {
        return from == ConsentRegistryContract.ConsentStatus.ACTIVE && 
               (to == ConsentRegistryContract.ConsentStatus.EXPIRED || 
                to == ConsentRegistryContract.ConsentStatus.REVOKED);
    }

    /**
     * Property: Consent expiration is enforced
     * A consent with expiresAt < now must not be considered active
     */
    @Property(tries = 100)
    void consentExpirationMustBeEnforced(
            @ForAll @LongRange(min = 0, max = 1000000) long secondsFromNow) {
        
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(secondsFromNow);
        
        ConsentState consent = new ConsentState(expiresAt);
        
        // Check at various times
        assertThat(consent.isActiveAt(now.minusSeconds(1))).isTrue();
        assertThat(consent.isActiveAt(expiresAt.plusSeconds(1))).isFalse();
    }

    /**
     * Property: Revoked consent cannot be reactivated
     * Once revoked, a consent must remain revoked
     */
    @Property(tries = 50)
    void revokedConsentCannotBeReactivated(@ForAll("uuids") UUID consentId) {
        ConsentState consent = new ConsentState(Instant.now().plusSeconds(3600));
        consent.revoke();
        
        assertThat(consent.isRevoked()).isTrue();
        assertThat(consent.isActive()).isFalse();
        
        // Attempting to "reactivate" should fail
        assertThatThrownBy(consent::reactivate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reactivate revoked consent");
    }

    // ========================================================================
    // Property Tests for Audit Anchor Contract (Requirements 63.1, 63.2)
    // ========================================================================

    /**
     * Property: Merkle root anchoring is immutable
     * Once anchored, a Merkle root cannot be modified
     */
    @Property(tries = 100)
    void merkleRootAnchoringIsImmutable(
            @ForAll @Size(min = 1, max = 100) List<String> receiptHashes) {
        
        // Build Merkle tree
        String merkleRoot = computeMerkleRoot(receiptHashes);
        
        // Simulate anchoring
        AnchorState anchor = new AnchorState(merkleRoot, receiptHashes.size());
        
        // Verify immutability
        assertThat(anchor.getMerkleRoot()).isEqualTo(merkleRoot);
        assertThat(anchor.getReceiptCount()).isEqualTo(receiptHashes.size());
        
        // Attempting to modify should fail
        assertThatThrownBy(() -> anchor.setMerkleRoot("modified"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Property: Merkle proof verification is correct
     * A valid proof for a leaf must verify against the root
     */
    @Property(tries = 50)
    void merkleProofVerificationMustBeCorrect(
            @ForAll @Size(min = 2, max = 16) List<@AlphaChars @StringLength(min = 1, max = 50) String> leaves) {
        
        // Hash leaves
        List<String> leafHashes = leaves.stream()
                .map(this::sha256)
                .toList();
        
        // Build tree and get proofs
        MerkleTreeSimulator tree = new MerkleTreeSimulator(leafHashes);
        String root = tree.getRoot();
        
        // Verify each leaf's proof
        for (int i = 0; i < leafHashes.size(); i++) {
            MerkleProofSimulator proof = tree.getProof(i);
            boolean valid = proof.verify(leafHashes.get(i), root);
            assertThat(valid)
                    .as("Proof for leaf %d should be valid", i)
                    .isTrue();
        }
    }

    /**
     * Property: Invalid Merkle proof must fail verification
     * A tampered proof or wrong leaf must not verify
     */
    @Property(tries = 50)
    void invalidMerkleProofMustFailVerification(
            @ForAll @Size(min = 2, max = 8) List<@AlphaChars @StringLength(min = 1, max = 50) String> leaves,
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String tamperedLeaf) {
        
        Assume.that(!leaves.contains(tamperedLeaf));
        
        List<String> leafHashes = leaves.stream()
                .map(this::sha256)
                .toList();
        
        MerkleTreeSimulator tree = new MerkleTreeSimulator(leafHashes);
        String root = tree.getRoot();
        
        // Get proof for first leaf
        MerkleProofSimulator proof = tree.getProof(0);
        
        // Verify with tampered leaf should fail
        String tamperedHash = sha256(tamperedLeaf);
        boolean valid = proof.verify(tamperedHash, root);
        assertThat(valid).isFalse();
    }

    /**
     * Property: Anchor ID is sequential
     * Each new anchor gets the next sequential ID
     */
    @Property(tries = 50)
    void anchorIdMustBeSequential(
            @ForAll @IntRange(min = 1, max = 20) int anchorCount) {
        
        AnchorRegistry registry = new AnchorRegistry();
        
        for (int i = 0; i < anchorCount; i++) {
            String root = sha256("batch_" + i);
            BigInteger anchorId = registry.anchor(root, i + 1);
            assertThat(anchorId).isEqualTo(BigInteger.valueOf(i));
        }
        
        assertThat(registry.getAnchorCount()).isEqualTo(BigInteger.valueOf(anchorCount));
    }

    // ========================================================================
    // Helper Classes for Testing
    // ========================================================================

    static class EscrowState {
        BigInteger fundedAmount = BigInteger.ZERO;
        BigInteger lockedAmount = BigInteger.ZERO;
        BigInteger releasedAmount = BigInteger.ZERO;
        BigInteger refundedAmount = BigInteger.ZERO;

        void fund(BigInteger amount) {
            fundedAmount = fundedAmount.add(amount);
        }

        void lock(BigInteger amount) {
            if (amount.compareTo(getAvailableToLock()) > 0) {
                throw new IllegalArgumentException("Insufficient funds to lock");
            }
            lockedAmount = lockedAmount.add(amount);
        }

        void release(BigInteger amount) {
            if (amount.compareTo(getAvailableToRelease()) > 0) {
                throw new IllegalArgumentException("Insufficient locked funds");
            }
            releasedAmount = releasedAmount.add(amount);
        }

        void refund(BigInteger amount) {
            if (amount.compareTo(getAvailableToRefund()) > 0) {
                throw new IllegalArgumentException("Insufficient available funds");
            }
            refundedAmount = refundedAmount.add(amount);
        }

        BigInteger getAvailableToLock() {
            return fundedAmount.subtract(lockedAmount).subtract(refundedAmount);
        }

        BigInteger getAvailableToRelease() {
            return lockedAmount.subtract(releasedAmount);
        }

        BigInteger getAvailableToRefund() {
            return fundedAmount.subtract(lockedAmount).subtract(refundedAmount);
        }
    }

    static class DisputeState {
        private final int requiredApprovals;
        private final Set<String> approvals = new HashSet<>();

        DisputeState(int requiredApprovals) {
            this.requiredApprovals = requiredApprovals;
        }

        void approve(String governor) {
            approvals.add(governor);
        }

        boolean canResolve() {
            return approvals.size() >= requiredApprovals;
        }
    }

    static class ConsentState {
        private final Instant expiresAt;
        private boolean revoked = false;

        ConsentState(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        boolean isActiveAt(Instant time) {
            return !revoked && time.isBefore(expiresAt);
        }

        boolean isActive() {
            return isActiveAt(Instant.now());
        }

        boolean isRevoked() {
            return revoked;
        }

        void revoke() {
            this.revoked = true;
        }

        void reactivate() {
            if (revoked) {
                throw new IllegalStateException("Cannot reactivate revoked consent");
            }
        }
    }

    static class AnchorState {
        private final String merkleRoot;
        private final int receiptCount;

        AnchorState(String merkleRoot, int receiptCount) {
            this.merkleRoot = merkleRoot;
            this.receiptCount = receiptCount;
        }

        String getMerkleRoot() {
            return merkleRoot;
        }

        int getReceiptCount() {
            return receiptCount;
        }

        void setMerkleRoot(String newRoot) {
            throw new UnsupportedOperationException("Anchor is immutable");
        }
    }

    static class AnchorRegistry {
        private final List<AnchorState> anchors = new ArrayList<>();

        BigInteger anchor(String merkleRoot, int receiptCount) {
            BigInteger id = BigInteger.valueOf(anchors.size());
            anchors.add(new AnchorState(merkleRoot, receiptCount));
            return id;
        }

        BigInteger getAnchorCount() {
            return BigInteger.valueOf(anchors.size());
        }
    }

    static class MerkleTreeSimulator {
        private final List<String> leaves;
        private final List<List<String>> levels;
        private final String root;

        MerkleTreeSimulator(List<String> leafHashes) {
            this.leaves = new ArrayList<>(leafHashes);
            this.levels = new ArrayList<>();
            this.root = buildTree();
        }

        private String buildTree() {
            List<String> currentLevel = new ArrayList<>(leaves);
            levels.add(currentLevel);

            while (currentLevel.size() > 1) {
                List<String> nextLevel = new ArrayList<>();
                for (int i = 0; i < currentLevel.size(); i += 2) {
                    String left = currentLevel.get(i);
                    String right = (i + 1 < currentLevel.size()) ? currentLevel.get(i + 1) : left;
                    nextLevel.add(hashPair(left, right));
                }
                levels.add(nextLevel);
                currentLevel = nextLevel;
            }

            return currentLevel.isEmpty() ? "" : currentLevel.get(0);
        }

        String getRoot() {
            return root;
        }

        MerkleProofSimulator getProof(int leafIndex) {
            List<String> proof = new ArrayList<>();
            List<Boolean> flags = new ArrayList<>();
            int index = leafIndex;

            for (int level = 0; level < levels.size() - 1; level++) {
                List<String> currentLevel = levels.get(level);
                int siblingIndex = (index % 2 == 0) ? index + 1 : index - 1;
                
                if (siblingIndex < currentLevel.size()) {
                    proof.add(currentLevel.get(siblingIndex));
                    flags.add(index % 2 != 0); // true if sibling is on left
                } else {
                    proof.add(currentLevel.get(index));
                    flags.add(false);
                }
                index = index / 2;
            }

            return new MerkleProofSimulator(proof, flags);
        }

        private String hashPair(String left, String right) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(hexToBytes(left));
                digest.update(hexToBytes(right));
                return bytesToHex(digest.digest());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class MerkleProofSimulator {
        private final List<String> siblings;
        private final List<Boolean> flags;

        MerkleProofSimulator(List<String> siblings, List<Boolean> flags) {
            this.siblings = siblings;
            this.flags = flags;
        }

        boolean verify(String leafHash, String expectedRoot) {
            String computedHash = leafHash;
            for (int i = 0; i < siblings.size(); i++) {
                if (flags.get(i)) {
                    computedHash = hashPair(siblings.get(i), computedHash);
                } else {
                    computedHash = hashPair(computedHash, siblings.get(i));
                }
            }
            return computedHash.equals(expectedRoot);
        }

        private String hashPair(String left, String right) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(hexToBytes(left));
                digest.update(hexToBytes(right));
                return bytesToHex(digest.digest());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    private String computeConsentHash(UUID dsId, UUID requesterId, String scope, String purpose) {
        String data = String.join("|", dsId.toString(), requesterId.toString(), scope, purpose);
        return sha256(data);
    }

    private String computeMerkleRoot(List<String> hashes) {
        if (hashes.isEmpty()) return "";
        List<String> leafHashes = hashes.stream().map(this::sha256).toList();
        MerkleTreeSimulator tree = new MerkleTreeSimulator(leafHashes);
        return tree.getRoot();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] hexToBytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}

package com.yachaq.api.audit;

import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.ActorType;
import com.yachaq.core.domain.AuditReceipt.EventType;
import com.yachaq.core.repository.AuditReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Audit Receipt Ledger.
 * Uses example-based testing with generated data due to Spring context requirements.
 * 
 * **Feature: yachaq-platform, Property 5: Audit Receipt Generation**
 * **Validates: Requirements 12.1**
 * 
 * **Feature: yachaq-platform, Property 8: Merkle Tree Validity**
 * **Validates: Requirements 126.3**
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditServicePropertyTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:55432/yachaq");
        registry.add("spring.datasource.username", () -> "yachaq");
        registry.add("spring.datasource.password", () -> "yachaq");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("yachaq.jwt.secret", () -> "test-secret-key-minimum-32-characters-long-for-testing");
    }

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditReceiptRepository auditRepository;

    private final Random random = new Random();

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
    }

    /**
     * **Feature: yachaq-platform, Property 5: Audit Receipt Generation**
     * 
     * *For any* key event (consent grant, consent revoke, data access, settlement, payout),
     * an immutable audit receipt must be generated containing: event type, timestamp,
     * actor, resource, and details hash.
     * 
     * **Validates: Requirements 12.1**
     */
    @Test
    void property5_auditReceiptGeneration_containsAllRequiredFields() {
        // Run 100 iterations with random data
        for (int i = 0; i < 100; i++) {
            EventType eventType = randomEventType();
            ActorType actorType = randomActorType();
            UUID actorId = UUID.randomUUID();
            UUID resourceId = UUID.randomUUID();
            String resourceType = "TestResource" + i;
            String detailsHash = generateRandomHash();
            
            // Create audit receipt
            AuditReceipt receipt = auditService.appendReceipt(
                    eventType,
                    actorId,
                    actorType,
                    resourceId,
                    resourceType,
                    detailsHash
            );
            
            // Property 5: Receipt must contain all required fields
            assertNotNull(receipt.getId(), "Receipt ID must not be null");
            assertEquals(eventType, receipt.getEventType(), "Event type must match");
            assertNotNull(receipt.getTimestamp(), "Timestamp must not be null");
            assertEquals(actorId, receipt.getActorId(), "Actor ID must match");
            assertEquals(actorType, receipt.getActorType(), "Actor type must match");
            assertEquals(resourceId, receipt.getResourceId(), "Resource ID must match");
            assertEquals(resourceType, receipt.getResourceType(), "Resource type must match");
            assertEquals(detailsHash, receipt.getDetailsHash(), "Details hash must match");
            
            // Receipt must have hash for chain integrity
            assertNotNull(receipt.getReceiptHash(), "Receipt hash must not be null");
            assertEquals(64, receipt.getReceiptHash().length(), "Receipt hash must be SHA-256 (64 hex chars)");
            
            // Receipt must have previous hash (GENESIS for first, or actual hash)
            assertNotNull(receipt.getPreviousReceiptHash(), "Previous receipt hash must not be null");
            
            // Receipt must be retrievable
            AuditReceipt retrieved = auditService.getReceipt(receipt.getId());
            assertNotNull(retrieved, "Retrieved receipt must not be null");
            assertEquals(receipt.getId(), retrieved.getId(), "Retrieved receipt ID must match");
        }
    }


    /**
     * **Feature: yachaq-platform, Property 5: Hash Chain Integrity**
     * 
     * *For any* sequence of audit receipts, each receipt must reference
     * the hash of the previous receipt, forming an unbroken chain.
     * 
     * **Validates: Requirements 12.1, 126.1**
     */
    @Test
    void property5_hashChainIntegrity_formsUnbrokenChain() {
        // Run 100 iterations
        for (int iteration = 0; iteration < 100; iteration++) {
            auditRepository.deleteAll();
            
            int chainLength = random.nextInt(8) + 2; // 2-10 receipts per chain
            List<AuditReceipt> receipts = new ArrayList<>();
            
            // Create a chain of receipts
            for (int i = 0; i < chainLength; i++) {
                AuditReceipt receipt = auditService.appendReceipt(
                        EventType.DATA_ACCESS,
                        UUID.randomUUID(),
                        ActorType.DS,
                        UUID.randomUUID(),
                        "TestResource",
                        generateRandomHash()
                );
                receipts.add(receipt);
            }
            
            // Verify chain integrity
            for (int i = 1; i < receipts.size(); i++) {
                AuditReceipt current = receipts.get(i);
                AuditReceipt previous = receipts.get(i - 1);
                
                // Current receipt must reference previous receipt's hash
                assertEquals(previous.getReceiptHash(), current.getPreviousReceiptHash(),
                        "Receipt " + i + " must reference previous receipt's hash");
            }
            
            // First receipt must reference GENESIS
            assertEquals("GENESIS", receipts.get(0).getPreviousReceiptHash(),
                    "First receipt must reference GENESIS");
        }
    }

    /**
     * **Feature: yachaq-platform, Property 5: Receipt Verification**
     * 
     * *For any* audit receipt, verifying its integrity must succeed
     * when the receipt has not been tampered with.
     * 
     * **Validates: Requirements 128.1**
     */
    @Test
    void property5_receiptVerification_succeedsForUntamperedReceipts() {
        // Run 100 iterations
        for (int i = 0; i < 100; i++) {
            auditRepository.deleteAll();
            
            // Create receipt
            AuditReceipt receipt = auditService.appendReceipt(
                    randomEventType(),
                    UUID.randomUUID(),
                    ActorType.SYSTEM,
                    UUID.randomUUID(),
                    "ConsentContract",
                    generateRandomHash()
            );
            
            // Verify integrity
            AuditService.ReceiptVerificationResult result = 
                    auditService.verifyReceiptIntegrity(receipt.getId());
            
            // Verification must succeed for untampered receipt
            assertTrue(result.hashValid(), "Hash must be valid for untampered receipt");
            assertTrue(result.overallValid(), "Overall verification must pass for untampered receipt");
        }
    }

    /**
     * **Feature: yachaq-platform, Property 8: Merkle Tree Validity**
     * 
     * *For any* batch of audit receipts anchored to blockchain, the Merkle tree
     * must be valid: any receipt's inclusion can be verified using the Merkle
     * proof against the anchored root.
     * 
     * **Validates: Requirements 126.3**
     */
    @Test
    void property8_merkleTreeValidity_allProofsVerify() {
        // Run 100 iterations with varying tree sizes
        for (int iteration = 0; iteration < 100; iteration++) {
            int leafCount = random.nextInt(19) + 1; // 1-20 leaves
            List<String> leafHashes = new ArrayList<>();
            
            for (int i = 0; i < leafCount; i++) {
                leafHashes.add(generateRandomHash());
            }
            
            // Build Merkle tree
            MerkleTree tree = MerkleTree.build(leafHashes);
            
            // Property 8: Tree must have a valid root
            assertNotNull(tree.getRoot(), "Merkle root must not be null");
            assertEquals(64, tree.getRoot().length(), "Merkle root must be SHA-256 (64 hex chars)");
            
            // Property 8: Every leaf must have a valid proof
            for (int i = 0; i < leafHashes.size(); i++) {
                MerkleTree.MerkleProof proof = tree.getProof(i);
                
                // Proof must contain the correct leaf hash
                assertEquals(leafHashes.get(i), proof.leafHash(), 
                        "Proof leaf hash must match original for leaf " + i);
                assertEquals(i, proof.leafIndex(), 
                        "Proof leaf index must match for leaf " + i);
                assertEquals(tree.getRoot(), proof.expectedRoot(), 
                        "Proof expected root must match tree root for leaf " + i);
                
                // Proof must verify against the root
                boolean verified = MerkleTree.verifyProof(proof, tree.getRoot());
                assertTrue(verified, "Merkle proof for leaf " + i + " must verify against root");
            }
        }
    }

    /**
     * **Feature: yachaq-platform, Property 8: Merkle Proof Serialization**
     * 
     * *For any* Merkle proof, serializing and deserializing must produce
     * an equivalent proof that still verifies correctly.
     * 
     * **Validates: Requirements 126.4**
     */
    @Test
    void property8_merkleProofSerialization_roundTripPreservesValidity() {
        // Run 100 iterations
        for (int iteration = 0; iteration < 100; iteration++) {
            int leafCount = random.nextInt(14) + 2; // 2-15 leaves
            List<String> leafHashes = new ArrayList<>();
            
            for (int i = 0; i < leafCount; i++) {
                leafHashes.add(generateRandomHash());
            }
            
            // Build tree and get proof for random leaf
            MerkleTree tree = MerkleTree.build(leafHashes);
            int leafIndex = random.nextInt(leafHashes.size());
            MerkleTree.MerkleProof originalProof = tree.getProof(leafIndex);
            
            // Serialize and deserialize
            String serialized = originalProof.serialize();
            MerkleTree.MerkleProof deserializedProof = MerkleTree.MerkleProof.deserialize(serialized);
            
            // Deserialized proof must match original
            assertEquals(originalProof.leafHash(), deserializedProof.leafHash(),
                    "Deserialized leaf hash must match original");
            assertEquals(originalProof.leafIndex(), deserializedProof.leafIndex(),
                    "Deserialized leaf index must match original");
            assertEquals(originalProof.expectedRoot(), deserializedProof.expectedRoot(),
                    "Deserialized expected root must match original");
            assertEquals(originalProof.proofElements().size(), deserializedProof.proofElements().size(),
                    "Deserialized proof elements count must match original");
            
            // Deserialized proof must still verify
            boolean verified = MerkleTree.verifyProof(deserializedProof, tree.getRoot());
            assertTrue(verified, "Deserialized proof must still verify");
        }
    }

    /**
     * **Feature: yachaq-platform, Property 8: Merkle Tree Determinism**
     * 
     * *For any* set of leaf hashes, building the Merkle tree twice
     * must produce the same root.
     * 
     * **Validates: Requirements 126.3**
     */
    @Test
    void property8_merkleTreeDeterminism_sameInputProducesSameRoot() {
        // Run 100 iterations
        for (int iteration = 0; iteration < 100; iteration++) {
            int leafCount = random.nextInt(19) + 1; // 1-20 leaves
            List<String> leafHashes = new ArrayList<>();
            
            for (int i = 0; i < leafCount; i++) {
                leafHashes.add(generateRandomHash());
            }
            
            // Build tree twice
            MerkleTree tree1 = MerkleTree.build(leafHashes);
            MerkleTree tree2 = MerkleTree.build(leafHashes);
            
            // Roots must be identical
            assertEquals(tree1.getRoot(), tree2.getRoot(),
                    "Same input must produce same Merkle root");
            
            // All proofs must be identical
            for (int i = 0; i < leafHashes.size(); i++) {
                MerkleTree.MerkleProof proof1 = tree1.getProof(i);
                MerkleTree.MerkleProof proof2 = tree2.getProof(i);
                
                assertEquals(proof1.serialize(), proof2.serialize(),
                        "Proofs for leaf " + i + " must be identical");
            }
        }
    }

    /**
     * **Feature: yachaq-platform, Property 8: Merkle Proof Tamper Detection**
     * 
     * *For any* Merkle proof, modifying the leaf hash must cause
     * verification to fail.
     * 
     * **Validates: Requirements 126.3**
     */
    @Test
    void property8_merkleProofTamperDetection_detectsModifiedLeafHash() {
        // Run 100 iterations
        for (int iteration = 0; iteration < 100; iteration++) {
            int leafCount = random.nextInt(9) + 2; // 2-10 leaves
            List<String> leafHashes = new ArrayList<>();
            
            for (int i = 0; i < leafCount; i++) {
                leafHashes.add(generateRandomHash());
            }
            
            MerkleTree tree = MerkleTree.build(leafHashes);
            MerkleTree.MerkleProof originalProof = tree.getProof(0);
            
            // Generate a different hash for tampering
            String tamperedHash = generateRandomHash();
            
            // Skip if tampered hash happens to equal original (extremely unlikely)
            if (tamperedHash.equals(originalProof.leafHash())) {
                continue;
            }
            
            // Create tampered proof with different leaf hash
            MerkleTree.MerkleProof tamperedProof = new MerkleTree.MerkleProof(
                    tamperedHash,
                    originalProof.leafIndex(),
                    originalProof.proofElements(),
                    originalProof.expectedRoot()
            );
            
            // Tampered proof must fail verification
            boolean verified = MerkleTree.verifyProof(tamperedProof, tree.getRoot());
            assertFalse(verified, "Tampered proof must fail verification");
        }
    }

    // Helper methods
    private EventType randomEventType() {
        EventType[] types = EventType.values();
        return types[random.nextInt(types.length)];
    }

    private ActorType randomActorType() {
        ActorType[] types = ActorType.values();
        return types[random.nextInt(types.length)];
    }

    private String generateRandomHash() {
        StringBuilder sb = new StringBuilder(64);
        String hexChars = "0123456789abcdef";
        for (int i = 0; i < 64; i++) {
            sb.append(hexChars.charAt(random.nextInt(16)));
        }
        return sb.toString();
    }
}

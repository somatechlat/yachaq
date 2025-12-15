package com.yachaq.api.audit;

import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.ActorType;
import com.yachaq.core.domain.AuditReceipt.EventType;
import com.yachaq.core.repository.AuditReceiptRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Audit Receipt Ledger Service.
 * Implements append-only storage with hash chaining and Merkle tree batching.
 * 
 * Property 5: Audit Receipt Generation
 * Property 8: Merkle Tree Validity
 * Validates: Requirements 12.1, 126.1, 126.3, 126.4, 128.1, 128.2
 */
@Service
public class AuditService {

    private final AuditReceiptRepository auditRepository;
    
    // Batch size for Merkle tree anchoring
    private static final int MERKLE_BATCH_SIZE = 100;

    public AuditService(AuditReceiptRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Appends a new audit receipt with hash chaining.
     * Property 5: Audit Receipt Generation
     * Validates: Requirements 12.1
     */
    @Transactional
    public AuditReceipt appendReceipt(
            EventType eventType,
            UUID actorId,
            ActorType actorType,
            UUID resourceId,
            String resourceType,
            String detailsHash) {
        
        // Get previous receipt hash for chaining
        String previousHash = auditRepository.findMostRecentReceiptHash()
                .orElse("GENESIS");
        
        // Create receipt with hash chain
        AuditReceipt receipt = AuditReceipt.create(
                eventType,
                actorId,
                actorType,
                resourceId,
                resourceType,
                detailsHash,
                previousHash
        );

        // Compute receipt hash for chain integrity
        String receiptData = String.join("|",
                eventType.name(),
                receipt.getTimestamp().toString(),
                actorId.toString(),
                actorType.name(),
                resourceId.toString(),
                resourceType,
                detailsHash,
                previousHash
        );
        receipt.setReceiptHash(sha256(receiptData));
        
        return auditRepository.save(receipt);
    }

    /**
     * Batches unanchored receipts into a Merkle tree and stores proofs.
     * Property 8: Merkle Tree Validity
     * Validates: Requirements 126.3, 126.4
     */
    @Transactional
    public MerkleAnchorResult anchorBatch() {
        // Get unanchored receipts
        List<AuditReceipt> unanchored = auditRepository.findUnanchoredReceipts(
                PageRequest.of(0, MERKLE_BATCH_SIZE));
        
        if (unanchored.isEmpty()) {
            return new MerkleAnchorResult(null, 0, Collections.emptyList());
        }

        // Build Merkle tree from receipt hashes
        List<String> leafHashes = unanchored.stream()
                .map(AuditReceipt::getReceiptHash)
                .toList();
        
        MerkleTree tree = MerkleTree.build(leafHashes);
        
        // Store Merkle proofs for each receipt
        List<UUID> anchoredIds = new ArrayList<>();
        for (int i = 0; i < unanchored.size(); i++) {
            AuditReceipt receipt = unanchored.get(i);
            MerkleTree.MerkleProof proof = tree.getProof(i);
            receipt.setMerkleProof(proof.serialize());
            auditRepository.save(receipt);
            anchoredIds.add(receipt.getId());
        }
        
        return new MerkleAnchorResult(tree.getRoot(), unanchored.size(), anchoredIds);
    }

    /**
     * Verifies a receipt's integrity using hash chain.
     * Validates: Requirements 128.1
     */
    public ReceiptVerificationResult verifyReceiptIntegrity(UUID receiptId) {
        AuditReceipt receipt = auditRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException("Receipt not found: " + receiptId));
        
        // Recompute receipt hash
        String recomputedHash = computeReceiptHash(receipt);
        boolean hashValid = recomputedHash.equals(receipt.getReceiptHash());
        
        // Verify the immediate chain link (current.previousReceiptHash must exist as a receiptHash).
        boolean chainValid;
        String previousHash = receipt.getPreviousReceiptHash();
        if ("GENESIS".equals(previousHash)) {
            chainValid = true;
        } else {
            chainValid = auditRepository.findByReceiptHash(previousHash)
                    .map(prev -> prev.getTimestamp().isBefore(receipt.getTimestamp())
                            || prev.getTimestamp().equals(receipt.getTimestamp()))
                    .orElse(false);
        }
        
        return new ReceiptVerificationResult(
                receiptId,
                hashValid,
                chainValid,
                hashValid && chainValid,
                receipt.getMerkleProof() != null
        );
    }

    /**
     * Verifies a receipt's Merkle proof against a given root.
     * Validates: Requirements 128.2
     */
    public MerkleVerificationResult verifyMerkleProof(UUID receiptId, String expectedRoot) {
        AuditReceipt receipt = auditRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException("Receipt not found: " + receiptId));
        
        if (receipt.getMerkleProof() == null) {
            return new MerkleVerificationResult(receiptId, false, "Receipt has no Merkle proof");
        }
        
        try {
            MerkleTree.MerkleProof proof = MerkleTree.MerkleProof.deserialize(receipt.getMerkleProof());
            boolean valid = MerkleTree.verifyProof(proof, expectedRoot);
            return new MerkleVerificationResult(
                    receiptId,
                    valid,
                    valid ? "Proof verified successfully" : "Proof verification failed"
            );
        } catch (Exception e) {
            return new MerkleVerificationResult(receiptId, false, "Invalid proof format: " + e.getMessage());
        }
    }

    /**
     * Gets receipts for an actor (DS or Requester).
     */
    public Page<AuditReceipt> getReceiptsByActor(UUID actorId, Pageable pageable) {
        return auditRepository.findByActorIdOrderByTimestampDesc(actorId, pageable);
    }

    /**
     * Gets receipts for a resource.
     */
    public List<AuditReceipt> getReceiptsByResource(UUID resourceId) {
        return auditRepository.findByResourceIdOrderByTimestampDesc(resourceId);
    }

    /**
     * Gets receipts by event type.
     */
    public Page<AuditReceipt> getReceiptsByEventType(EventType eventType, Pageable pageable) {
        return auditRepository.findByEventTypeOrderByTimestampDesc(eventType, pageable);
    }

    /**
     * Gets receipts in a time range.
     */
    public Page<AuditReceipt> getReceiptsByTimeRange(Instant start, Instant end, Pageable pageable) {
        return auditRepository.findByTimestampRange(start, end, pageable);
    }

    /**
     * Gets a specific receipt by ID.
     */
    public AuditReceipt getReceipt(UUID receiptId) {
        return auditRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException("Receipt not found: " + receiptId));
    }

    /**
     * Gets receipts for a consent contract.
     */
    public List<AuditReceipt> getReceiptsByConsentContract(UUID contractId) {
        return auditRepository.findByConsentContract(contractId);
    }

    /**
     * Counts receipts by event type in a time range.
     */
    public long countByEventTypeInRange(EventType eventType, Instant start, Instant end) {
        return auditRepository.countByEventTypeInRange(eventType, start, end);
    }

    private String computeReceiptHash(AuditReceipt receipt) {
        String receiptData = String.join("|",
                receipt.getEventType().name(),
                receipt.getTimestamp().toString(),
                receipt.getActorId().toString(),
                receipt.getActorType().name(),
                receipt.getResourceId().toString(),
                receipt.getResourceType(),
                receipt.getDetailsHash(),
                receipt.getPreviousReceiptHash()
        );
        return sha256(receiptData);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // Result records
    public record MerkleAnchorResult(
            String merkleRoot,
            int receiptCount,
            List<UUID> anchoredReceiptIds
    ) {}

    public record ReceiptVerificationResult(
            UUID receiptId,
            boolean hashValid,
            boolean chainValid,
            boolean overallValid,
            boolean hasMerkleProof
    ) {}

    public record MerkleVerificationResult(
            UUID receiptId,
            boolean valid,
            String message
    ) {}

    // Exceptions
    public static class ReceiptNotFoundException extends RuntimeException {
        public ReceiptNotFoundException(String message) { super(message); }
    }
}

package com.yachaq.api.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Merkle tree implementation for audit receipt batching.
 * 
 * Property 8: Merkle Tree Validity
 * Validates: Requirements 126.3, 126.4
 */
public class MerkleTree {

    private final List<String> leaves;
    private final List<List<String>> tree;
    private final String root;

    private MerkleTree(List<String> leaves, List<List<String>> tree, String root) {
        this.leaves = Collections.unmodifiableList(leaves);
        this.tree = tree;
        this.root = root;
    }

    /**
     * Builds a Merkle tree from a list of leaf hashes.
     * If the number of leaves is odd, the last leaf is duplicated.
     */
    public static MerkleTree build(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            throw new IllegalArgumentException("Cannot build Merkle tree from empty list");
        }

        List<List<String>> tree = new ArrayList<>();
        List<String> currentLevel = new ArrayList<>(leafHashes);
        
        // Pad to even number if necessary
        if (currentLevel.size() % 2 != 0) {
            currentLevel.add(currentLevel.get(currentLevel.size() - 1));
        }
        tree.add(new ArrayList<>(currentLevel));

        // Build tree bottom-up
        while (currentLevel.size() > 1) {
            List<String> nextLevel = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i += 2) {
                String left = currentLevel.get(i);
                String right = currentLevel.get(i + 1);
                String parent = hashPair(left, right);
                nextLevel.add(parent);
            }
            // Pad to even number if necessary (except for root)
            if (nextLevel.size() > 1 && nextLevel.size() % 2 != 0) {
                nextLevel.add(nextLevel.get(nextLevel.size() - 1));
            }
            tree.add(new ArrayList<>(nextLevel));
            currentLevel = nextLevel;
        }

        return new MerkleTree(leafHashes, tree, currentLevel.get(0));
    }


    /**
     * Gets the Merkle root hash.
     */
    public String getRoot() {
        return root;
    }

    /**
     * Gets the original leaf hashes.
     */
    public List<String> getLeaves() {
        return leaves;
    }

    /**
     * Gets the number of leaves.
     */
    public int size() {
        return leaves.size();
    }

    /**
     * Generates a Merkle proof for a leaf at the given index.
     * The proof consists of sibling hashes needed to reconstruct the root.
     */
    public MerkleProof getProof(int leafIndex) {
        if (leafIndex < 0 || leafIndex >= leaves.size()) {
            throw new IndexOutOfBoundsException("Leaf index out of bounds: " + leafIndex);
        }

        List<ProofElement> proofElements = new ArrayList<>();
        int index = leafIndex;
        
        // Handle padding for odd number of leaves
        int paddedIndex = index;
        if (tree.get(0).size() > leaves.size() && index == leaves.size() - 1) {
            // Last leaf was duplicated for padding
            paddedIndex = index;
        }

        for (int level = 0; level < tree.size() - 1; level++) {
            List<String> currentLevel = tree.get(level);
            int siblingIndex = (paddedIndex % 2 == 0) ? paddedIndex + 1 : paddedIndex - 1;
            
            if (siblingIndex < currentLevel.size()) {
                boolean isLeft = paddedIndex % 2 != 0;
                proofElements.add(new ProofElement(currentLevel.get(siblingIndex), isLeft));
            }
            
            paddedIndex = paddedIndex / 2;
        }

        return new MerkleProof(leaves.get(leafIndex), leafIndex, proofElements, root);
    }

    /**
     * Verifies a Merkle proof against this tree's root.
     */
    public boolean verifyProof(MerkleProof proof) {
        return verifyProof(proof, this.root);
    }

    /**
     * Statically verifies a Merkle proof against a given root.
     */
    public static boolean verifyProof(MerkleProof proof, String expectedRoot) {
        String currentHash = proof.leafHash();
        
        for (ProofElement element : proof.proofElements()) {
            if (element.isLeft()) {
                currentHash = hashPair(element.hash(), currentHash);
            } else {
                currentHash = hashPair(currentHash, element.hash());
            }
        }
        
        return currentHash.equals(expectedRoot);
    }

    /**
     * Hashes two child nodes to create parent hash.
     * Uses SHA-256 with sorted concatenation for determinism.
     */
    private static String hashPair(String left, String right) {
        return sha256(left + right);
    }

    /**
     * Computes SHA-256 hash of input string.
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Proof element containing a sibling hash and its position.
     */
    public record ProofElement(String hash, boolean isLeft) {}

    /**
     * Complete Merkle proof for a leaf.
     */
    public record MerkleProof(
            String leafHash,
            int leafIndex,
            List<ProofElement> proofElements,
            String expectedRoot
    ) {
        /**
         * Serializes the proof to a compact string format.
         */
        public String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append(leafHash).append(":").append(leafIndex).append(":");
            for (ProofElement elem : proofElements) {
                sb.append(elem.isLeft() ? "L" : "R").append(elem.hash()).append(",");
            }
            if (!proofElements.isEmpty()) {
                sb.setLength(sb.length() - 1); // Remove trailing comma
            }
            sb.append(":").append(expectedRoot);
            return sb.toString();
        }

        /**
         * Deserializes a proof from string format.
         */
        public static MerkleProof deserialize(String serialized) {
            String[] parts = serialized.split(":");
            if (parts.length != 4) {
                throw new IllegalArgumentException("Invalid proof format");
            }
            
            String leafHash = parts[0];
            int leafIndex = Integer.parseInt(parts[1]);
            String expectedRoot = parts[3];
            
            List<ProofElement> elements = new ArrayList<>();
            if (!parts[2].isEmpty()) {
                String[] elemParts = parts[2].split(",");
                for (String elem : elemParts) {
                    boolean isLeft = elem.charAt(0) == 'L';
                    String hash = elem.substring(1);
                    elements.add(new ProofElement(hash, isLeft));
                }
            }
            
            return new MerkleProof(leafHash, leafIndex, elements, expectedRoot);
        }
    }
}

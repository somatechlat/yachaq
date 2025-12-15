package com.yachaq.blockchain.service;

import com.yachaq.blockchain.contract.AuditAnchorContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.StaticGasProvider;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Service for interacting with the Audit Anchor smart contract.
 * Requirements: 63.1, 63.2
 */
@Service
public class BlockchainAuditAnchorService {

    private static final Logger log = LoggerFactory.getLogger(BlockchainAuditAnchorService.class);
    private final BlockchainConfig config;
    private AuditAnchorContract contract;
    private Web3j web3j;

    public BlockchainAuditAnchorService(BlockchainConfig config) {
        this.config = config;
        if (config.isEnabled()) {
            initializeContract();
        }
    }

    private void initializeContract() {
        try {
            this.web3j = Web3j.build(new HttpService(config.getNodeUrl()));
            Credentials credentials = Credentials.create(config.getPrivateKey());
            StaticGasProvider gasProvider = new StaticGasProvider(
                    BigInteger.valueOf(config.getGasPrice()),
                    BigInteger.valueOf(config.getGasLimit()));
            this.contract = AuditAnchorContract.load(
                    config.getAuditAnchorAddress(), web3j, credentials, gasProvider);
            log.info("Audit Anchor contract initialized at {}", config.getAuditAnchorAddress());
        } catch (Exception e) {
            log.error("Failed to initialize audit anchor contract", e);
        }
    }

    /**
     * Anchors a Merkle root to the blockchain.
     * Property 8: Merkle Tree Validity - anchors batch of receipts
     */
    public Optional<AnchorResult> anchorMerkleRoot(String merkleRoot, int receiptCount, String batchMetadataHash) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] rootBytes = hexToBytes32(merkleRoot);
            byte[] metadataBytes = hexToBytes32(batchMetadataHash);
            BigInteger count = BigInteger.valueOf(receiptCount);

            TransactionReceipt receipt = contract.anchorRoot(rootBytes, count, metadataBytes).send();

            if (receipt.isStatusOK()) {
                BigInteger anchorCount = contract.getAnchorCount().send();
                BigInteger anchorId = anchorCount.subtract(BigInteger.ONE);
                
                return Optional.of(new AnchorResult(
                        anchorId,
                        receipt.getTransactionHash(),
                        receipt.getBlockNumber(),
                        true
                ));
            }
            return Optional.of(new AnchorResult(null, receipt.getTransactionHash(), receipt.getBlockNumber(), false));
        } catch (Exception e) {
            log.error("Failed to anchor Merkle root on blockchain", e);
            return Optional.empty();
        }
    }

    /**
     * Verifies a Merkle proof against an anchored root.
     * Property 8: Merkle Tree Validity - verifies receipt inclusion
     */
    public Optional<Boolean> verifyMerkleProof(BigInteger anchorId, String leafHash, List<String> proof, List<Boolean> proofFlags) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] leafBytes = hexToBytes32(leafHash);
            byte[][] proofBytes = proof.stream().map(this::hexToBytes32).toArray(byte[][]::new);
            boolean[] flags = new boolean[proofFlags.size()];
            for (int i = 0; i < proofFlags.size(); i++) {
                flags[i] = proofFlags.get(i);
            }

            Boolean isValid = contract.verifyProof(anchorId, leafBytes, proofBytes, flags).send();
            return Optional.of(isValid);
        } catch (Exception e) {
            log.error("Failed to verify Merkle proof on blockchain for anchor {}", anchorId, e);
            return Optional.empty();
        }
    }

    /**
     * Gets anchor details from blockchain.
     */
    public Optional<AnchorDetails> getAnchor(BigInteger anchorId) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            AuditAnchorContract.AnchorRecord record = contract.getAnchor(anchorId).send();
            return Optional.of(new AnchorDetails(
                    anchorId,
                    HexFormat.of().formatHex(record.merkleRoot),
                    record.receiptCount.intValue(),
                    HexFormat.of().formatHex(record.batchMetadataHash),
                    record.timestamp.longValue(),
                    record.submitter
            ));
        } catch (Exception e) {
            log.error("Failed to get anchor {} from blockchain", anchorId, e);
            return Optional.empty();
        }
    }

    /**
     * Gets total number of anchors on blockchain.
     */
    public Optional<BigInteger> getAnchorCount() {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(contract.getAnchorCount().send());
        } catch (Exception e) {
            log.error("Failed to get anchor count from blockchain", e);
            return Optional.empty();
        }
    }

    public boolean isEnabled() {
        return config.isEnabled() && contract != null;
    }

    private byte[] hexToBytes32(String hex) {
        byte[] bytes = new byte[32];
        String cleanHex = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (cleanHex.length() > 64) {
            cleanHex = cleanHex.substring(0, 64);
        }
        byte[] hexBytes = HexFormat.of().parseHex(cleanHex);
        System.arraycopy(hexBytes, 0, bytes, 32 - hexBytes.length, hexBytes.length);
        return bytes;
    }

    public record AnchorResult(BigInteger anchorId, String txHash, BigInteger blockNumber, boolean success) {}

    public record AnchorDetails(
            BigInteger anchorId,
            String merkleRoot,
            int receiptCount,
            String batchMetadataHash,
            long timestamp,
            String submitter
    ) {}
}

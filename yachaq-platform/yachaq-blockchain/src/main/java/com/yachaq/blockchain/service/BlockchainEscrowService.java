package com.yachaq.blockchain.service;

import com.yachaq.blockchain.contract.EscrowContract;
import com.yachaq.blockchain.contract.EscrowContract.EscrowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.StaticGasProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for interacting with the Escrow smart contract.
 * Requirements: 61.1, 61.2
 */
@Service
public class BlockchainEscrowService {

    private static final Logger log = LoggerFactory.getLogger(BlockchainEscrowService.class);
    private final BlockchainConfig config;
    private EscrowContract contract;
    private Web3j web3j;

    public BlockchainEscrowService(BlockchainConfig config) {
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
            this.contract = EscrowContract.load(
                    config.getEscrowContractAddress(), web3j, credentials, gasProvider);
            log.info("Escrow contract initialized at {}", config.getEscrowContractAddress());
        } catch (Exception e) {
            log.error("Failed to initialize escrow contract", e);
        }
    }

    /**
     * Creates an escrow on the blockchain.
     */
    public Optional<BlockchainTxResult> createEscrow(UUID requestId) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] requestIdBytes = uuidToBytes32(requestId);
            TransactionReceipt receipt = contract.createEscrow(requestIdBytes).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to create escrow on blockchain for request {}", requestId, e);
            return Optional.empty();
        }
    }

    /**
     * Deposits funds into an escrow on the blockchain.
     */
    public Optional<BlockchainTxResult> deposit(UUID escrowId, BigInteger amount) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] escrowIdBytes = uuidToBytes32(escrowId);
            TransactionReceipt receipt = contract.deposit(escrowIdBytes, amount).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to deposit to escrow {} on blockchain", escrowId, e);
            return Optional.empty();
        }
    }

    /**
     * Locks funds in an escrow on the blockchain.
     */
    public Optional<BlockchainTxResult> lock(UUID escrowId, BigInteger amount) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] escrowIdBytes = uuidToBytes32(escrowId);
            TransactionReceipt receipt = contract.lock(escrowIdBytes, amount).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to lock escrow {} on blockchain", escrowId, e);
            return Optional.empty();
        }
    }

    /**
     * Releases funds from escrow to a DS recipient.
     */
    public Optional<BlockchainTxResult> release(UUID escrowId, String recipientAddress, BigInteger amount) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] escrowIdBytes = uuidToBytes32(escrowId);
            TransactionReceipt receipt = contract.release(escrowIdBytes, recipientAddress, amount).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to release escrow {} on blockchain", escrowId, e);
            return Optional.empty();
        }
    }

    /**
     * Refunds remaining funds to the requester.
     */
    public Optional<BlockchainTxResult> refund(UUID escrowId, BigInteger amount) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] escrowIdBytes = uuidToBytes32(escrowId);
            TransactionReceipt receipt = contract.refund(escrowIdBytes, amount).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to refund escrow {} on blockchain", escrowId, e);
            return Optional.empty();
        }
    }

    /**
     * Raises a dispute on an escrow.
     */
    public Optional<BlockchainTxResult> raiseDispute(UUID escrowId, String reason) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] escrowIdBytes = uuidToBytes32(escrowId);
            TransactionReceipt receipt = contract.raiseDispute(escrowIdBytes, reason).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to raise dispute on escrow {} on blockchain", escrowId, e);
            return Optional.empty();
        }
    }

    /**
     * Resolves a dispute (requires multi-sig governance).
     */
    public Optional<BlockchainTxResult> resolveDispute(UUID escrowId, boolean releaseToDS) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] escrowIdBytes = uuidToBytes32(escrowId);
            TransactionReceipt receipt = contract.resolveDispute(escrowIdBytes, releaseToDS).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to resolve dispute on escrow {} on blockchain", escrowId, e);
            return Optional.empty();
        }
    }

    /**
     * Gets escrow status from blockchain.
     */
    public Optional<EscrowStatus> getEscrowStatus(UUID escrowId) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] escrowIdBytes = uuidToBytes32(escrowId);
            EscrowContract.EscrowData data = contract.getEscrow(escrowIdBytes).send();
            return Optional.of(EscrowStatus.fromValue(data.status));
        } catch (Exception e) {
            log.error("Failed to get escrow {} status from blockchain", escrowId, e);
            return Optional.empty();
        }
    }

    public boolean isEnabled() {
        return config.isEnabled() && contract != null;
    }

    private byte[] uuidToBytes32(UUID uuid) {
        String hex = uuid.toString().replace("-", "");
        byte[] bytes = new byte[32];
        byte[] uuidBytes = HexFormat.of().parseHex(hex);
        System.arraycopy(uuidBytes, 0, bytes, 16, 16);
        return bytes;
    }

    public record BlockchainTxResult(String txHash, BigInteger blockNumber, boolean success) {}
}

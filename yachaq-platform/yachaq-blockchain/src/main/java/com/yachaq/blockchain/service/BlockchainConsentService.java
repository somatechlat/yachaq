package com.yachaq.blockchain.service;

import com.yachaq.blockchain.contract.ConsentRegistryContract;
import com.yachaq.blockchain.contract.ConsentRegistryContract.ConsentStatus;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for interacting with the Consent Registry smart contract.
 * Requirements: 62.1, 62.2
 */
@Service
public class BlockchainConsentService {

    private static final Logger log = LoggerFactory.getLogger(BlockchainConsentService.class);
    private final BlockchainConfig config;
    private ConsentRegistryContract contract;
    private Web3j web3j;

    public BlockchainConsentService(BlockchainConfig config) {
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
            this.contract = ConsentRegistryContract.load(
                    config.getConsentRegistryAddress(), web3j, credentials, gasProvider);
            log.info("Consent Registry contract initialized at {}", config.getConsentRegistryAddress());
        } catch (Exception e) {
            log.error("Failed to initialize consent registry contract", e);
        }
    }

    /**
     * Registers a consent on the blockchain.
     */
    public Optional<BlockchainTxResult> registerConsent(
            UUID consentId, String consentHash, UUID dsId, UUID requesterId, Instant expiresAt) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] consentIdBytes = uuidToBytes32(consentId);
            byte[] consentHashBytes = hexToBytes32(consentHash);
            byte[] dsIdBytes = uuidToBytes32(dsId);
            byte[] requesterIdBytes = uuidToBytes32(requesterId);
            BigInteger expiresAtTimestamp = BigInteger.valueOf(expiresAt.getEpochSecond());

            TransactionReceipt receipt = contract.registerConsent(
                    consentIdBytes, consentHashBytes, dsIdBytes, requesterIdBytes, expiresAtTimestamp
            ).send();

            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to register consent {} on blockchain", consentId, e);
            return Optional.empty();
        }
    }

    /**
     * Revokes a consent on the blockchain.
     */
    public Optional<BlockchainTxResult> revokeConsent(UUID consentId, UUID dsId) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] consentIdBytes = uuidToBytes32(consentId);
            byte[] dsIdBytes = uuidToBytes32(dsId);
            TransactionReceipt receipt = contract.revokeConsent(consentIdBytes, dsIdBytes).send();
            return Optional.of(new BlockchainTxResult(
                    receipt.getTransactionHash(),
                    receipt.getBlockNumber(),
                    receipt.isStatusOK()
            ));
        } catch (Exception e) {
            log.error("Failed to revoke consent {} on blockchain", consentId, e);
            return Optional.empty();
        }
    }

    /**
     * Verifies a consent hash against the blockchain record.
     */
    public Optional<Boolean> verifyConsent(UUID consentId, String expectedHash) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] consentIdBytes = uuidToBytes32(consentId);
            byte[] expectedHashBytes = hexToBytes32(expectedHash);
            Boolean isValid = contract.verifyConsent(consentIdBytes, expectedHashBytes).send();
            return Optional.of(isValid);
        } catch (Exception e) {
            log.error("Failed to verify consent {} on blockchain", consentId, e);
            return Optional.empty();
        }
    }

    /**
     * Checks if a consent is active on the blockchain.
     */
    public Optional<Boolean> isConsentActive(UUID consentId) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] consentIdBytes = uuidToBytes32(consentId);
            Boolean isActive = contract.isConsentActive(consentIdBytes).send();
            return Optional.of(isActive);
        } catch (Exception e) {
            log.error("Failed to check consent {} status on blockchain", consentId, e);
            return Optional.empty();
        }
    }

    /**
     * Gets consent status from blockchain.
     */
    public Optional<ConsentStatus> getConsentStatus(UUID consentId) {
        if (!config.isEnabled() || contract == null) {
            return Optional.empty();
        }
        try {
            byte[] consentIdBytes = uuidToBytes32(consentId);
            ConsentRegistryContract.ConsentRecord record = contract.getConsent(consentIdBytes).send();
            return Optional.of(ConsentStatus.fromValue(record.status));
        } catch (Exception e) {
            log.error("Failed to get consent {} status from blockchain", consentId, e);
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

    public record BlockchainTxResult(String txHash, BigInteger blockNumber, boolean success) {}
}

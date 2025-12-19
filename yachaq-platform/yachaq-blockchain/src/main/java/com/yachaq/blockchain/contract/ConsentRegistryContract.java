package com.yachaq.blockchain.contract;

import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.gas.ContractGasProvider;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

/**
 * Consent Registry Smart Contract - Web3j wrapper.
 * 
 * Stores consent hashes with expiration and revocation, emits lifecycle events.
 * Requirements: 62.1, 62.2
 * 
 * Solidity equivalent:
 * contract YachaqConsentRegistry {
 *     struct ConsentRecord {
 *         bytes32 consentHash;      // Hash of consent contract details
 *         bytes32 dsId;             // Data Sovereign identifier
 *         bytes32 requesterId;      // Requester identifier
 *         uint256 grantedAt;        // Timestamp when consent was granted
 *         uint256 expiresAt;        // Expiration timestamp
 *         uint256 revokedAt;        // Revocation timestamp (0 if not revoked)
 *         ConsentStatus status;
 *     }
 *     
 *     enum ConsentStatus { ACTIVE, EXPIRED, REVOKED }
 *     
 *     mapping(bytes32 => ConsentRecord) public consents;
 *     
 *     event ConsentGranted(bytes32 indexed consentId, bytes32 indexed dsId, bytes32 indexed requesterId, uint256 expiresAt);
 *     event ConsentRevoked(bytes32 indexed consentId, bytes32 indexed dsId, uint256 revokedAt);
 *     event ConsentExpired(bytes32 indexed consentId, bytes32 indexed dsId);
 *     event ConsentVerified(bytes32 indexed consentId, bool isValid);
 * }
 */
public class ConsentRegistryContract extends Contract {

    /**
     * Compiled Solidity bytecode.
     * 
     * To generate: 
     * 1. Write Solidity contract in contracts/ConsentRegistry.sol
     * 2. Compile with: solc --bin --abi contracts/ConsentRegistry.sol
     * 3. Paste the bytecode here
     * 
     * The contract must be deployed to the blockchain before this wrapper can be used.
     * Use ConsentRegistryContract.load() with the deployed contract address.
     */
    public static final String BINARY = "";

    public static final String FUNC_REGISTERCONSENT = "registerConsent";
    public static final String FUNC_REVOKECONSENT = "revokeConsent";
    public static final String FUNC_VERIFYCONSENT = "verifyConsent";
    public static final String FUNC_GETCONSENT = "getConsent";
    public static final String FUNC_ISCONSENTACTIVE = "isConsentActive";
    public static final String FUNC_MARKEXPIRED = "markExpired";

    // Events
    public static final Event CONSENT_GRANTED_EVENT = new Event("ConsentGranted",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // consentId
                    new TypeReference<Bytes32>(true) {},  // dsId
                    new TypeReference<Bytes32>(true) {},  // requesterId
                    new TypeReference<Uint256>() {}       // expiresAt
            ));

    public static final Event CONSENT_REVOKED_EVENT = new Event("ConsentRevoked",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // consentId
                    new TypeReference<Bytes32>(true) {},  // dsId
                    new TypeReference<Uint256>() {}       // revokedAt
            ));

    public static final Event CONSENT_EXPIRED_EVENT = new Event("ConsentExpired",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // consentId
                    new TypeReference<Bytes32>(true) {}   // dsId
            ));

    public static final Event CONSENT_VERIFIED_EVENT = new Event("ConsentVerified",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // consentId
                    new TypeReference<Bool>() {}          // isValid
            ));

    protected ConsentRegistryContract(String contractAddress, Web3j web3j, 
                                       Credentials credentials, ContractGasProvider gasProvider) {
        super(BINARY, contractAddress, web3j, credentials, gasProvider);
    }

    /**
     * Registers a new consent on the blockchain.
     */
    public RemoteFunctionCall<TransactionReceipt> registerConsent(
            byte[] consentId,
            byte[] consentHash,
            byte[] dsId,
            byte[] requesterId,
            BigInteger expiresAt) {
        final Function function = new Function(
                FUNC_REGISTERCONSENT,
                Arrays.asList(
                        new Bytes32(consentId),
                        new Bytes32(consentHash),
                        new Bytes32(dsId),
                        new Bytes32(requesterId),
                        new Uint256(expiresAt)
                ),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Revokes a consent on the blockchain.
     */
    public RemoteFunctionCall<TransactionReceipt> revokeConsent(byte[] consentId, byte[] dsId) {
        final Function function = new Function(
                FUNC_REVOKECONSENT,
                Arrays.asList(new Bytes32(consentId), new Bytes32(dsId)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Verifies a consent hash against the stored record.
     */
    public RemoteFunctionCall<Boolean> verifyConsent(byte[] consentId, byte[] expectedHash) {
        final Function function = new Function(
                FUNC_VERIFYCONSENT,
                Arrays.asList(new Bytes32(consentId), new Bytes32(expectedHash)),
                Arrays.asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    /**
     * Gets consent record details.
     */
    @SuppressWarnings("unchecked")
    public RemoteFunctionCall<ConsentRecord> getConsent(byte[] consentId) {
        final Function function = new Function(
                FUNC_GETCONSENT,
                Arrays.asList(new Bytes32(consentId)),
                Arrays.asList(
                        new TypeReference<Bytes32>() {},   // consentHash
                        new TypeReference<Bytes32>() {},   // dsId
                        new TypeReference<Bytes32>() {},   // requesterId
                        new TypeReference<Uint256>() {},   // grantedAt
                        new TypeReference<Uint256>() {},   // expiresAt
                        new TypeReference<Uint256>() {},   // revokedAt
                        new TypeReference<Uint256>() {}    // status
                ));
        return executeRemoteCallSingleValueReturn(function, ConsentRecord.class);
    }

    /**
     * Checks if a consent is currently active.
     */
    public RemoteFunctionCall<Boolean> isConsentActive(byte[] consentId) {
        final Function function = new Function(
                FUNC_ISCONSENTACTIVE,
                Arrays.asList(new Bytes32(consentId)),
                Arrays.asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    /**
     * Marks a consent as expired (can be called by anyone after expiration).
     */
    public RemoteFunctionCall<TransactionReceipt> markExpired(byte[] consentId) {
        final Function function = new Function(
                FUNC_MARKEXPIRED,
                Arrays.asList(new Bytes32(consentId)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Loads an existing contract at the given address.
     */
    public static ConsentRegistryContract load(String contractAddress, Web3j web3j,
                                                Credentials credentials, ContractGasProvider gasProvider) {
        return new ConsentRegistryContract(contractAddress, web3j, credentials, gasProvider);
    }

    /**
     * Consent record structure returned from contract.
     */
    public static class ConsentRecord {
        public byte[] consentHash;
        public byte[] dsId;
        public byte[] requesterId;
        public BigInteger grantedAt;
        public BigInteger expiresAt;
        public BigInteger revokedAt;
        public BigInteger status;

        public ConsentRecord(byte[] consentHash, byte[] dsId, byte[] requesterId,
                             BigInteger grantedAt, BigInteger expiresAt,
                             BigInteger revokedAt, BigInteger status) {
            this.consentHash = consentHash;
            this.dsId = dsId;
            this.requesterId = requesterId;
            this.grantedAt = grantedAt;
            this.expiresAt = expiresAt;
            this.revokedAt = revokedAt;
            this.status = status;
        }

        public boolean isActive() {
            return ConsentStatus.fromValue(status) == ConsentStatus.ACTIVE;
        }

        public boolean isRevoked() {
            return ConsentStatus.fromValue(status) == ConsentStatus.REVOKED;
        }

        public boolean isExpired() {
            return ConsentStatus.fromValue(status) == ConsentStatus.EXPIRED;
        }
    }

    /**
     * Consent status enum matching contract.
     */
    public enum ConsentStatus {
        ACTIVE(0),
        EXPIRED(1),
        REVOKED(2);

        private final int value;

        ConsentStatus(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static ConsentStatus fromValue(BigInteger value) {
            int intValue = value.intValue();
            for (ConsentStatus status : values()) {
                if (status.value == intValue) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown consent status: " + value);
        }
    }
}

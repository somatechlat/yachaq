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
 * Audit Anchor Smart Contract - Web3j wrapper.
 * 
 * Accepts Merkle roots and emits Anchor events, supports verification.
 * Requirements: 63.1, 63.2
 */
public class AuditAnchorContract extends Contract {

    public static final String BINARY = "";
    public static final String FUNC_ANCHORROOT = "anchorRoot";
    public static final String FUNC_VERIFYPROOF = "verifyProof";
    public static final String FUNC_GETANCHOR = "getAnchor";
    public static final String FUNC_GETANCHORCOUNT = "getAnchorCount";

    // Events
    public static final Event ANCHOR_CREATED_EVENT = new Event("AnchorCreated",
            Arrays.asList(
                    new TypeReference<Uint256>(true) {},  // anchorId
                    new TypeReference<Bytes32>() {},      // merkleRoot
                    new TypeReference<Uint256>() {},      // receiptCount
                    new TypeReference<Uint256>() {}       // timestamp
            ));

    public static final Event PROOF_VERIFIED_EVENT = new Event("ProofVerified",
            Arrays.asList(
                    new TypeReference<Uint256>(true) {},  // anchorId
                    new TypeReference<Bytes32>() {},      // leafHash
                    new TypeReference<Bool>() {}          // isValid
            ));

    protected AuditAnchorContract(String contractAddress, Web3j web3j,
                                   Credentials credentials, ContractGasProvider gasProvider) {
        super(BINARY, contractAddress, web3j, credentials, gasProvider);
    }

    /**
     * Anchors a Merkle root to the blockchain.
     */
    public RemoteFunctionCall<TransactionReceipt> anchorRoot(
            byte[] merkleRoot, BigInteger receiptCount, byte[] batchMetadataHash) {
        final Function function = new Function(
                FUNC_ANCHORROOT,
                Arrays.asList(
                        new Bytes32(merkleRoot),
                        new Uint256(receiptCount),
                        new Bytes32(batchMetadataHash)
                ),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Verifies a Merkle proof against an anchored root.
     */
    public RemoteFunctionCall<Boolean> verifyProof(
            BigInteger anchorId, byte[] leafHash, byte[][] proof, boolean[] proofFlags) {
        DynamicArray<Bytes32> proofArray = new DynamicArray<>(Bytes32.class,
                Arrays.stream(proof).map(Bytes32::new).toList());
        DynamicArray<Bool> flagsArray = new DynamicArray<>(Bool.class,
                java.util.stream.IntStream.range(0, proofFlags.length)
                        .mapToObj(i -> new Bool(proofFlags[i])).toList());
        
        final Function function = new Function(
                FUNC_VERIFYPROOF,
                Arrays.asList(new Uint256(anchorId), new Bytes32(leafHash), proofArray, flagsArray),
                Arrays.asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    /**
     * Gets anchor details by ID.
     */
    @SuppressWarnings("unchecked")
    public RemoteFunctionCall<AnchorRecord> getAnchor(BigInteger anchorId) {
        final Function function = new Function(
                FUNC_GETANCHOR,
                Arrays.asList(new Uint256(anchorId)),
                Arrays.asList(
                        new TypeReference<Bytes32>() {},   // merkleRoot
                        new TypeReference<Uint256>() {},   // receiptCount
                        new TypeReference<Bytes32>() {},   // batchMetadataHash
                        new TypeReference<Uint256>() {},   // timestamp
                        new TypeReference<Address>() {}    // submitter
                ));
        return executeRemoteCallSingleValueReturn(function, AnchorRecord.class);
    }

    /**
     * Gets total number of anchors.
     */
    public RemoteFunctionCall<BigInteger> getAnchorCount() {
        final Function function = new Function(
                FUNC_GETANCHORCOUNT,
                Collections.emptyList(),
                Arrays.asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public static AuditAnchorContract load(String contractAddress, Web3j web3j,
                                            Credentials credentials, ContractGasProvider gasProvider) {
        return new AuditAnchorContract(contractAddress, web3j, credentials, gasProvider);
    }

    /**
     * Anchor record structure returned from contract.
     */
    public static class AnchorRecord {
        public byte[] merkleRoot;
        public BigInteger receiptCount;
        public byte[] batchMetadataHash;
        public BigInteger timestamp;
        public String submitter;

        public AnchorRecord(byte[] merkleRoot, BigInteger receiptCount, byte[] batchMetadataHash,
                            BigInteger timestamp, String submitter) {
            this.merkleRoot = merkleRoot;
            this.receiptCount = receiptCount;
            this.batchMetadataHash = batchMetadataHash;
            this.timestamp = timestamp;
            this.submitter = submitter;
        }
    }
}

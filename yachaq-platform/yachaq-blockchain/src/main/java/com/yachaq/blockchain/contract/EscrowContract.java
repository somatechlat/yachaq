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
import java.util.List;

/**
 * Escrow Smart Contract - Web3j wrapper.
 * 
 * Implements deposit, lock, release, refund functions with multi-sig governance.
 * Requirements: 61.1, 61.2
 * 
 * Solidity equivalent:
 * contract YachaqEscrow {
 *     struct Escrow {
 *         bytes32 requestId;
 *         address requester;
 *         uint256 fundedAmount;
 *         uint256 lockedAmount;
 *         uint256 releasedAmount;
 *         uint256 refundedAmount;
 *         EscrowStatus status;
 *     }
 *     
 *     enum EscrowStatus { PENDING, FUNDED, LOCKED, SETTLED, REFUNDED, DISPUTED }
 *     
 *     mapping(bytes32 => Escrow) public escrows;
 *     mapping(address => bool) public governors;
 *     uint256 public requiredApprovals;
 *     
 *     event EscrowCreated(bytes32 indexed escrowId, bytes32 indexed requestId, address requester);
 *     event EscrowFunded(bytes32 indexed escrowId, uint256 amount);
 *     event EscrowLocked(bytes32 indexed escrowId, uint256 amount);
 *     event EscrowReleased(bytes32 indexed escrowId, address indexed recipient, uint256 amount);
 *     event EscrowRefunded(bytes32 indexed escrowId, address indexed requester, uint256 amount);
 *     event DisputeRaised(bytes32 indexed escrowId, address indexed raiser, string reason);
 *     event DisputeResolved(bytes32 indexed escrowId, bool releasedToDS);
 * }
 */
public class EscrowContract extends Contract {

    /**
     * Compiled Solidity bytecode.
     * 
     * To generate: 
     * 1. Write Solidity contract in contracts/Escrow.sol
     * 2. Compile with: solc --bin --abi contracts/Escrow.sol
     * 3. Paste the bytecode here
     * 
     * The contract must be deployed to the blockchain before this wrapper can be used.
     * Use EscrowContract.load() with the deployed contract address.
     */
    public static final String BINARY = "";
    
    public static final String FUNC_CREATEESCROW = "createEscrow";
    public static final String FUNC_DEPOSIT = "deposit";
    public static final String FUNC_LOCK = "lock";
    public static final String FUNC_RELEASE = "release";
    public static final String FUNC_REFUND = "refund";
    public static final String FUNC_RAISEDISPUTE = "raiseDispute";
    public static final String FUNC_RESOLVEDISPUTE = "resolveDispute";
    public static final String FUNC_GETESCROW = "getEscrow";
    public static final String FUNC_ISGOVERNOR = "isGovernor";

    // Events
    public static final Event ESCROW_CREATED_EVENT = new Event("EscrowCreated",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // escrowId
                    new TypeReference<Bytes32>(true) {},  // requestId
                    new TypeReference<Address>() {}       // requester
            ));

    public static final Event ESCROW_FUNDED_EVENT = new Event("EscrowFunded",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // escrowId
                    new TypeReference<Uint256>() {}       // amount
            ));

    public static final Event ESCROW_LOCKED_EVENT = new Event("EscrowLocked",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // escrowId
                    new TypeReference<Uint256>() {}       // amount
            ));

    public static final Event ESCROW_RELEASED_EVENT = new Event("EscrowReleased",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // escrowId
                    new TypeReference<Address>(true) {},  // recipient
                    new TypeReference<Uint256>() {}       // amount
            ));

    public static final Event ESCROW_REFUNDED_EVENT = new Event("EscrowRefunded",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // escrowId
                    new TypeReference<Address>(true) {},  // requester
                    new TypeReference<Uint256>() {}       // amount
            ));

    public static final Event DISPUTE_RAISED_EVENT = new Event("DisputeRaised",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // escrowId
                    new TypeReference<Address>(true) {},  // raiser
                    new TypeReference<Utf8String>() {}    // reason
            ));

    public static final Event DISPUTE_RESOLVED_EVENT = new Event("DisputeResolved",
            Arrays.asList(
                    new TypeReference<Bytes32>(true) {},  // escrowId
                    new TypeReference<Bool>() {}          // releasedToDS
            ));

    protected EscrowContract(String contractAddress, Web3j web3j, Credentials credentials,
                             ContractGasProvider gasProvider) {
        super(BINARY, contractAddress, web3j, credentials, gasProvider);
    }

    /**
     * Creates a new escrow for a request.
     */
    public RemoteFunctionCall<TransactionReceipt> createEscrow(byte[] requestId) {
        final Function function = new Function(
                FUNC_CREATEESCROW,
                Arrays.asList(new Bytes32(requestId)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Deposits funds into an escrow.
     */
    public RemoteFunctionCall<TransactionReceipt> deposit(byte[] escrowId, BigInteger amount) {
        final Function function = new Function(
                FUNC_DEPOSIT,
                Arrays.asList(new Bytes32(escrowId), new Uint256(amount)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Locks funds in escrow for a specific amount.
     */
    public RemoteFunctionCall<TransactionReceipt> lock(byte[] escrowId, BigInteger amount) {
        final Function function = new Function(
                FUNC_LOCK,
                Arrays.asList(new Bytes32(escrowId), new Uint256(amount)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Releases funds from escrow to a DS recipient.
     */
    public RemoteFunctionCall<TransactionReceipt> release(byte[] escrowId, String recipient, BigInteger amount) {
        final Function function = new Function(
                FUNC_RELEASE,
                Arrays.asList(new Bytes32(escrowId), new Address(recipient), new Uint256(amount)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Refunds remaining funds to the requester.
     */
    public RemoteFunctionCall<TransactionReceipt> refund(byte[] escrowId, BigInteger amount) {
        final Function function = new Function(
                FUNC_REFUND,
                Arrays.asList(new Bytes32(escrowId), new Uint256(amount)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Raises a dispute on an escrow (multi-sig governance).
     */
    public RemoteFunctionCall<TransactionReceipt> raiseDispute(byte[] escrowId, String reason) {
        final Function function = new Function(
                FUNC_RAISEDISPUTE,
                Arrays.asList(new Bytes32(escrowId), new Utf8String(reason)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Resolves a dispute (requires multi-sig approval).
     */
    public RemoteFunctionCall<TransactionReceipt> resolveDispute(byte[] escrowId, boolean releaseToDS) {
        final Function function = new Function(
                FUNC_RESOLVEDISPUTE,
                Arrays.asList(new Bytes32(escrowId), new Bool(releaseToDS)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * Gets escrow details.
     */
    @SuppressWarnings("unchecked")
    public RemoteFunctionCall<EscrowData> getEscrow(byte[] escrowId) {
        final Function function = new Function(
                FUNC_GETESCROW,
                Arrays.asList(new Bytes32(escrowId)),
                Arrays.asList(
                        new TypeReference<Bytes32>() {},   // requestId
                        new TypeReference<Address>() {},   // requester
                        new TypeReference<Uint256>() {},   // fundedAmount
                        new TypeReference<Uint256>() {},   // lockedAmount
                        new TypeReference<Uint256>() {},   // releasedAmount
                        new TypeReference<Uint256>() {},   // refundedAmount
                        new TypeReference<Uint256>() {}    // status
                ));
        return executeRemoteCallSingleValueReturn(function, EscrowData.class);
    }

    /**
     * Checks if an address is a governor.
     */
    public RemoteFunctionCall<Boolean> isGovernor(String address) {
        final Function function = new Function(
                FUNC_ISGOVERNOR,
                Arrays.asList(new Address(address)),
                Arrays.asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    /**
     * Loads an existing contract at the given address.
     */
    public static EscrowContract load(String contractAddress, Web3j web3j, 
                                       Credentials credentials, ContractGasProvider gasProvider) {
        return new EscrowContract(contractAddress, web3j, credentials, gasProvider);
    }

    /**
     * Escrow data structure returned from contract.
     */
    public static class EscrowData {
        public byte[] requestId;
        public String requester;
        public BigInteger fundedAmount;
        public BigInteger lockedAmount;
        public BigInteger releasedAmount;
        public BigInteger refundedAmount;
        public BigInteger status;

        public EscrowData(byte[] requestId, String requester, BigInteger fundedAmount,
                          BigInteger lockedAmount, BigInteger releasedAmount,
                          BigInteger refundedAmount, BigInteger status) {
            this.requestId = requestId;
            this.requester = requester;
            this.fundedAmount = fundedAmount;
            this.lockedAmount = lockedAmount;
            this.releasedAmount = releasedAmount;
            this.refundedAmount = refundedAmount;
            this.status = status;
        }
    }

    /**
     * Escrow status enum matching contract.
     */
    public enum EscrowStatus {
        PENDING(0),
        FUNDED(1),
        LOCKED(2),
        SETTLED(3),
        REFUNDED(4),
        DISPUTED(5);

        private final int value;

        EscrowStatus(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static EscrowStatus fromValue(BigInteger value) {
            int intValue = value.intValue();
            for (EscrowStatus status : values()) {
                if (status.value == intValue) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown escrow status: " + value);
        }
    }
}

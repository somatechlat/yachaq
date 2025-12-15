// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title YachaqAuditAnchor
 * @notice Audit Anchor smart contract for YACHAQ platform
 * @dev Accepts Merkle roots and supports verification
 * Requirements: 63.1, 63.2
 */
contract YachaqAuditAnchor {
    struct AnchorRecord {
        bytes32 merkleRoot;
        uint256 receiptCount;
        bytes32 batchMetadataHash;
        uint256 timestamp;
        address submitter;
    }

    AnchorRecord[] public anchors;
    mapping(address => bool) public authorizedSubmitters;
    address public owner;

    event AnchorCreated(uint256 indexed anchorId, bytes32 merkleRoot, uint256 receiptCount, uint256 timestamp);
    event ProofVerified(uint256 indexed anchorId, bytes32 leafHash, bool isValid);

    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner");
        _;
    }

    modifier onlyAuthorized() {
        require(authorizedSubmitters[msg.sender] || msg.sender == owner, "Not authorized");
        _;
    }

    constructor() {
        owner = msg.sender;
        authorizedSubmitters[msg.sender] = true;
    }

    function addSubmitter(address submitter) external onlyOwner {
        authorizedSubmitters[submitter] = true;
    }

    function removeSubmitter(address submitter) external onlyOwner {
        authorizedSubmitters[submitter] = false;
    }

    /**
     * @notice Anchors a Merkle root to the blockchain
     * @param merkleRoot The root hash of the Merkle tree
     * @param receiptCount Number of receipts in the batch
     * @param batchMetadataHash Hash of batch metadata
     */
    function anchorRoot(
        bytes32 merkleRoot,
        uint256 receiptCount,
        bytes32 batchMetadataHash
    ) external onlyAuthorized returns (uint256) {
        require(merkleRoot != bytes32(0), "Invalid root");
        require(receiptCount > 0, "Invalid count");
        
        uint256 anchorId = anchors.length;
        anchors.push(AnchorRecord({
            merkleRoot: merkleRoot,
            receiptCount: receiptCount,
            batchMetadataHash: batchMetadataHash,
            timestamp: block.timestamp,
            submitter: msg.sender
        }));
        
        emit AnchorCreated(anchorId, merkleRoot, receiptCount, block.timestamp);
        return anchorId;
    }

    /**
     * @notice Verifies a Merkle proof against an anchored root
     * @param anchorId The ID of the anchor
     * @param leafHash The hash of the leaf to verify
     * @param proof Array of sibling hashes
     * @param proofFlags Flags indicating left/right position
     */
    function verifyProof(
        uint256 anchorId,
        bytes32 leafHash,
        bytes32[] calldata proof,
        bool[] calldata proofFlags
    ) external returns (bool) {
        require(anchorId < anchors.length, "Invalid anchor");
        require(proof.length == proofFlags.length, "Invalid proof");
        
        bytes32 computedHash = leafHash;
        for (uint256 i = 0; i < proof.length; i++) {
            if (proofFlags[i]) {
                computedHash = keccak256(abi.encodePacked(proof[i], computedHash));
            } else {
                computedHash = keccak256(abi.encodePacked(computedHash, proof[i]));
            }
        }
        
        bool isValid = computedHash == anchors[anchorId].merkleRoot;
        emit ProofVerified(anchorId, leafHash, isValid);
        return isValid;
    }

    function getAnchor(uint256 anchorId) external view returns (
        bytes32 merkleRoot, uint256 receiptCount, bytes32 batchMetadataHash,
        uint256 timestamp, address submitter
    ) {
        require(anchorId < anchors.length, "Invalid anchor");
        AnchorRecord storage a = anchors[anchorId];
        return (a.merkleRoot, a.receiptCount, a.batchMetadataHash, a.timestamp, a.submitter);
    }

    function getAnchorCount() external view returns (uint256) {
        return anchors.length;
    }
}

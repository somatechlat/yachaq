// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title YachaqEscrow
 * @notice Escrow smart contract for YACHAQ platform
 * @dev Implements deposit, lock, release, refund with multi-sig governance
 * Requirements: 61.1, 61.2
 */
contract YachaqEscrow {
    enum EscrowStatus { PENDING, FUNDED, LOCKED, SETTLED, REFUNDED, DISPUTED }

    struct Escrow {
        bytes32 requestId;
        address requester;
        uint256 fundedAmount;
        uint256 lockedAmount;
        uint256 releasedAmount;
        uint256 refundedAmount;
        EscrowStatus status;
        uint256 createdAt;
    }

    struct Dispute {
        address raiser;
        string reason;
        uint256 raisedAt;
        uint256 approvalCount;
        mapping(address => bool) approvals;
    }

    mapping(bytes32 => Escrow) public escrows;
    mapping(bytes32 => Dispute) public disputes;
    mapping(address => bool) public governors;
    uint256 public requiredApprovals;
    address public owner;

    event EscrowCreated(bytes32 indexed escrowId, bytes32 indexed requestId, address requester);
    event EscrowFunded(bytes32 indexed escrowId, uint256 amount);
    event EscrowLocked(bytes32 indexed escrowId, uint256 amount);
    event EscrowReleased(bytes32 indexed escrowId, address indexed recipient, uint256 amount);
    event EscrowRefunded(bytes32 indexed escrowId, address indexed requester, uint256 amount);
    event DisputeRaised(bytes32 indexed escrowId, address indexed raiser, string reason);
    event DisputeResolved(bytes32 indexed escrowId, bool releasedToDS);

    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner");
        _;
    }

    modifier onlyGovernor() {
        require(governors[msg.sender], "Only governor");
        _;
    }

    constructor(address[] memory _governors, uint256 _requiredApprovals) {
        require(_governors.length >= _requiredApprovals, "Invalid approvals");
        owner = msg.sender;
        requiredApprovals = _requiredApprovals;
        for (uint i = 0; i < _governors.length; i++) {
            governors[_governors[i]] = true;
        }
    }

    function createEscrow(bytes32 requestId) external returns (bytes32) {
        bytes32 escrowId = keccak256(abi.encodePacked(requestId, msg.sender, block.timestamp));
        require(escrows[escrowId].createdAt == 0, "Escrow exists");
        
        escrows[escrowId] = Escrow({
            requestId: requestId,
            requester: msg.sender,
            fundedAmount: 0,
            lockedAmount: 0,
            releasedAmount: 0,
            refundedAmount: 0,
            status: EscrowStatus.PENDING,
            createdAt: block.timestamp
        });
        
        emit EscrowCreated(escrowId, requestId, msg.sender);
        return escrowId;
    }

    function deposit(bytes32 escrowId) external payable {
        Escrow storage escrow = escrows[escrowId];
        require(escrow.createdAt > 0, "Escrow not found");
        require(escrow.requester == msg.sender, "Not requester");
        require(escrow.status == EscrowStatus.PENDING || escrow.status == EscrowStatus.FUNDED, "Invalid status");
        
        escrow.fundedAmount += msg.value;
        escrow.status = EscrowStatus.FUNDED;
        
        emit EscrowFunded(escrowId, msg.value);
    }

    function lock(bytes32 escrowId, uint256 amount) external {
        Escrow storage escrow = escrows[escrowId];
        require(escrow.createdAt > 0, "Escrow not found");
        require(escrow.status == EscrowStatus.FUNDED, "Not funded");
        require(escrow.fundedAmount - escrow.lockedAmount >= amount, "Insufficient funds");
        
        escrow.lockedAmount += amount;
        escrow.status = EscrowStatus.LOCKED;
        
        emit EscrowLocked(escrowId, amount);
    }

    function release(bytes32 escrowId, address recipient, uint256 amount) external onlyGovernor {
        Escrow storage escrow = escrows[escrowId];
        require(escrow.createdAt > 0, "Escrow not found");
        require(escrow.status == EscrowStatus.LOCKED, "Not locked");
        require(escrow.lockedAmount - escrow.releasedAmount >= amount, "Insufficient locked");
        
        escrow.releasedAmount += amount;
        payable(recipient).transfer(amount);
        
        if (escrow.releasedAmount == escrow.lockedAmount) {
            escrow.status = EscrowStatus.SETTLED;
        }
        
        emit EscrowReleased(escrowId, recipient, amount);
    }

    function refund(bytes32 escrowId, uint256 amount) external {
        Escrow storage escrow = escrows[escrowId];
        require(escrow.createdAt > 0, "Escrow not found");
        require(escrow.requester == msg.sender || governors[msg.sender], "Not authorized");
        uint256 available = escrow.fundedAmount - escrow.lockedAmount - escrow.refundedAmount;
        require(available >= amount, "Insufficient available");
        
        escrow.refundedAmount += amount;
        payable(escrow.requester).transfer(amount);
        
        if (escrow.fundedAmount == escrow.refundedAmount + escrow.releasedAmount) {
            escrow.status = EscrowStatus.REFUNDED;
        }
        
        emit EscrowRefunded(escrowId, escrow.requester, amount);
    }

    function raiseDispute(bytes32 escrowId, string calldata reason) external {
        Escrow storage escrow = escrows[escrowId];
        require(escrow.createdAt > 0, "Escrow not found");
        require(escrow.status == EscrowStatus.LOCKED, "Not locked");
        
        escrow.status = EscrowStatus.DISPUTED;
        disputes[escrowId].raiser = msg.sender;
        disputes[escrowId].reason = reason;
        disputes[escrowId].raisedAt = block.timestamp;
        
        emit DisputeRaised(escrowId, msg.sender, reason);
    }

    function resolveDispute(bytes32 escrowId, bool releaseToDS) external onlyGovernor {
        Escrow storage escrow = escrows[escrowId];
        Dispute storage dispute = disputes[escrowId];
        require(escrow.status == EscrowStatus.DISPUTED, "Not disputed");
        require(!dispute.approvals[msg.sender], "Already approved");
        
        dispute.approvals[msg.sender] = true;
        dispute.approvalCount++;
        
        if (dispute.approvalCount >= requiredApprovals) {
            escrow.status = releaseToDS ? EscrowStatus.SETTLED : EscrowStatus.REFUNDED;
            emit DisputeResolved(escrowId, releaseToDS);
        }
    }

    function getEscrow(bytes32 escrowId) external view returns (
        bytes32 requestId, address requester, uint256 fundedAmount,
        uint256 lockedAmount, uint256 releasedAmount, uint256 refundedAmount, uint256 status
    ) {
        Escrow storage e = escrows[escrowId];
        return (e.requestId, e.requester, e.fundedAmount, e.lockedAmount, 
                e.releasedAmount, e.refundedAmount, uint256(e.status));
    }

    function isGovernor(address addr) external view returns (bool) {
        return governors[addr];
    }
}

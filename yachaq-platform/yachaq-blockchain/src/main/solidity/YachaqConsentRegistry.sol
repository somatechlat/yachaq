// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title YachaqConsentRegistry
 * @notice Consent Registry smart contract for YACHAQ platform
 * @dev Stores consent hashes with expiration and revocation
 * Requirements: 62.1, 62.2
 */
contract YachaqConsentRegistry {
    enum ConsentStatus { ACTIVE, EXPIRED, REVOKED }

    struct ConsentRecord {
        bytes32 consentHash;
        bytes32 dsId;
        bytes32 requesterId;
        uint256 grantedAt;
        uint256 expiresAt;
        uint256 revokedAt;
        ConsentStatus status;
    }

    mapping(bytes32 => ConsentRecord) public consents;
    address public owner;

    event ConsentGranted(bytes32 indexed consentId, bytes32 indexed dsId, bytes32 indexed requesterId, uint256 expiresAt);
    event ConsentRevoked(bytes32 indexed consentId, bytes32 indexed dsId, uint256 revokedAt);
    event ConsentExpired(bytes32 indexed consentId, bytes32 indexed dsId);
    event ConsentVerified(bytes32 indexed consentId, bool isValid);

    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner");
        _;
    }

    constructor() {
        owner = msg.sender;
    }

    function registerConsent(
        bytes32 consentId,
        bytes32 consentHash,
        bytes32 dsId,
        bytes32 requesterId,
        uint256 expiresAt
    ) external {
        require(consents[consentId].grantedAt == 0, "Consent exists");
        require(expiresAt > block.timestamp, "Invalid expiration");
        
        consents[consentId] = ConsentRecord({
            consentHash: consentHash,
            dsId: dsId,
            requesterId: requesterId,
            grantedAt: block.timestamp,
            expiresAt: expiresAt,
            revokedAt: 0,
            status: ConsentStatus.ACTIVE
        });
        
        emit ConsentGranted(consentId, dsId, requesterId, expiresAt);
    }

    function revokeConsent(bytes32 consentId, bytes32 dsId) external {
        ConsentRecord storage consent = consents[consentId];
        require(consent.grantedAt > 0, "Consent not found");
        require(consent.dsId == dsId, "Not DS owner");
        require(consent.status == ConsentStatus.ACTIVE, "Not active");
        
        consent.status = ConsentStatus.REVOKED;
        consent.revokedAt = block.timestamp;
        
        emit ConsentRevoked(consentId, dsId, block.timestamp);
    }

    function verifyConsent(bytes32 consentId, bytes32 expectedHash) external returns (bool) {
        ConsentRecord storage consent = consents[consentId];
        bool isValid = consent.grantedAt > 0 && 
                       consent.consentHash == expectedHash &&
                       consent.status == ConsentStatus.ACTIVE &&
                       consent.expiresAt > block.timestamp;
        
        emit ConsentVerified(consentId, isValid);
        return isValid;
    }

    function getConsent(bytes32 consentId) external view returns (
        bytes32 consentHash, bytes32 dsId, bytes32 requesterId,
        uint256 grantedAt, uint256 expiresAt, uint256 revokedAt, uint256 status
    ) {
        ConsentRecord storage c = consents[consentId];
        return (c.consentHash, c.dsId, c.requesterId, c.grantedAt, 
                c.expiresAt, c.revokedAt, uint256(c.status));
    }

    function isConsentActive(bytes32 consentId) external view returns (bool) {
        ConsentRecord storage consent = consents[consentId];
        return consent.status == ConsentStatus.ACTIVE && consent.expiresAt > block.timestamp;
    }

    function markExpired(bytes32 consentId) external {
        ConsentRecord storage consent = consents[consentId];
        require(consent.grantedAt > 0, "Consent not found");
        require(consent.status == ConsentStatus.ACTIVE, "Not active");
        require(consent.expiresAt <= block.timestamp, "Not expired");
        
        consent.status = ConsentStatus.EXPIRED;
        emit ConsentExpired(consentId, consent.dsId);
    }
}

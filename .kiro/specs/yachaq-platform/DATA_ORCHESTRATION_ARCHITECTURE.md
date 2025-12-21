.# YACHAQ Platform - Complete Data Orchestration Architecture

## Document Information
- **Version**: 1.0.0
- **Created**: 2025-12-21
- **Purpose**: Complete view of data flow, blockchain integration, and system orchestration

---

## Executive Summary

YACHAQ is a consent-first, edge-first personal data sovereignty platform. This document provides a comprehensive view of how data flows through the entire system, from Data Sovereign (DS) devices through the platform layer to blockchain anchoring.

### Core Principles
1. **Edge-First**: Data stays on user devices; platform queries devices live
2. **Consent-First**: Every access requires explicit, purpose-bound consent
3. **Privacy by Architecture**: Operators cannot access raw user data
4. **Transparency**: All events generate immutable audit receipts
5. **Fair Compensation**: Uniform pricing regardless of geography
6. **Decentralized**: Anyone can run nodes; no vendor lock-in

---

## Complete Data Flow Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    YACHAQ PLATFORM - DATA ORCHESTRATION                                  │
├─────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              DATA SOVEREIGN (DS) LAYER - PHONE-AS-NODE                          │    │
│  │                                                                                                  │    │
│  │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                  │    │
│  │   │  Connectors  │───▶│  Normalizer  │───▶│   Labeler    │───▶│ ODX Builder  │                  │    │
│  │   │ (HealthKit,  │    │ (Canonical   │    │ (Label       │    │ (Privacy-    │                  │    │
│  │   │  Spotify,    │    │  Events)     │    │  Ontology)   │    │  Safe Index) │                  │    │
│  │   │  Imports)    │    └──────────────┘    └──────────────┘    └──────────────┘                  │    │
│  │   └──────────────┘           │                                       │                          │    │
│  │                              ▼                                       ▼                          │    │
│  │                    ┌──────────────┐                        ┌──────────────┐                     │    │
│  │                    │ Local Vault  │                        │     ODX      │                     │    │
│  │                    │ (Encrypted   │                        │ (Labels +    │                     │    │
│  │                    │  Raw Data)   │                        │  Aggregates) │                     │    │
│  │                    └──────────────┘                        └──────────────┘                     │    │
│  │                              │                                       │                          │    │
│  │                              │                                       │                          │    │
│  │   ┌──────────────────────────┼───────────────────────────────────────┼──────────────────────┐   │    │
│  │   │                          ▼                                       ▼                      │   │    │
│  │   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                │   │    │
│  │   │  │ Request      │  │ Local        │  │ Contract     │  │ QueryPlan    │                │   │    │
│  │   │  │ Inbox        │◀─│ Matcher      │◀─│ Builder      │◀─│ VM (Sandbox) │                │   │    │
│  │   │  │ (Receives    │  │ (Eligibility │  │ (Consent     │  │ (Constrained │                │   │    │
│  │   │  │  Requests)   │  │  Check)      │  │  Signing)    │  │  Execution)  │                │   │    │
│  │   │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘                │   │    │
│  │   │                                                               │                         │   │    │
│  │   │                                                               ▼                         │   │    │
│  │   │                                              ┌──────────────────────────────┐           │   │    │
│  │   │                                              │    Time Capsule Packager     │           │   │    │
│  │   │                                              │    (Encrypted + TTL-bound)   │           │   │    │
│  │   │                                              └──────────────────────────────┘           │   │    │
│  │   │                                                               │                         │   │    │
│  │   │                                                               ▼                         │   │    │
│  │   │                                              ┌──────────────────────────────┐           │   │    │
│  │   │                                              │      P2P Transport           │           │   │    │
│  │   │                                              │   (WebRTC/libp2p E2E)        │           │   │    │
│  │   │                                              └──────────────────────────────┘           │   │    │
│  │   │                                                               │                         │   │    │
│  │   │  ┌──────────────┐  ┌──────────────┐                          │                         │   │    │
│  │   │  │ Network Gate │  │ On-Device    │                          │                         │   │    │
│  │   │  │ (Raw Egress  │  │ Audit Log    │◀─────────────────────────┘                         │   │    │
│  │   │  │  BLOCKED)    │  │ (Verifiable) │                                                    │   │    │
│  │   │  └──────────────┘  └──────────────┘                                                    │   │    │
│  │   └────────────────────────────────────────────────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                    │                                                     │
│                                                    │ P2P (Ciphertext Only)                               │
│                                                    ▼                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                    PLATFORM LAYER (COORDINATOR)                                  │    │
│  │                                    ⚠️ NEVER RECEIVES RAW DATA ⚠️                                 │    │
│  │                                                                                                  │    │
│  │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                  │    │
│  │   │  Screening   │───▶│   Consent    │───▶│   Matching   │───▶│    Query     │                  │    │
│  │   │   Engine     │    │   Engine     │    │   Engine     │    │ Orchestrator │                  │    │
│  │   │ (Policy      │    │ (Contract    │    │ (Eligibility │    │ (Dispatch    │                  │    │
│  │   │  Validation) │    │  Management) │    │  Matching)   │    │  to Devices) │                  │    │
│  │   └──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘                  │    │
│  │          │                   │                   │                   │                          │    │
│  │          ▼                   ▼                   ▼                   ▼                          │    │
│  │   ┌──────────────────────────────────────────────────────────────────────────────────────┐     │    │
│  │   │                              AUDIT RECEIPT LEDGER                                     │     │    │
│  │   │   • Consent Grant/Revoke Events    • Query Dispatch Events                           │     │    │
│  │   │   • Settlement Events              • Payout Events                                   │     │    │
│  │   │   • Merkle Tree Batching           • Blockchain Anchoring                            │     │    │
│  │   └──────────────────────────────────────────────────────────────────────────────────────┘     │    │
│  │                                                    │                                            │    │
│  │   ┌──────────────────────────────────────────────────────────────────────────────────────┐     │    │
│  │   │                              FINANCIAL LEDGER                                         │     │    │
│  │   │   • Double-Entry Accounting        • Escrow Management                               │     │    │
│  │   │   • Settlement Batches             • Payout Instructions                             │     │    │
│  │   │   • YC Token Management            • Earned vs Available Balances                    │     │    │
│  │   └──────────────────────────────────────────────────────────────────────────────────────┘     │    │
│  │                                                    │                                            │    │
│  └────────────────────────────────────────────────────┼────────────────────────────────────────────┘    │
│                                                       │                                                  │
│                                                       │ Anchoring                                        │
│                                                       ▼                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                    BLOCKCHAIN LAYER (EVM)                                        │    │
│  │                                                                                                  │    │
│  │   ┌────────────────────────┐  ┌────────────────────────┐  ┌────────────────────────┐            │    │
│  │   │    EscrowContract      │  │ ConsentRegistryContract│  │  AuditAnchorContract   │            │    │
│  │   │                        │  │                        │  │                        │            │    │
│  │   │  • deposit()           │  │  • registerConsent()   │  │  • anchorRoot()        │            │    │
│  │   │  • lock()              │  │  • revokeConsent()     │  │  • verifyProof()       │            │    │
│  │   │  • release()           │  │  • verifyConsent()     │  │  • getAnchor()         │            │    │
│  │   │  • refund()            │  │  • isConsentActive()   │  │  • getAnchorCount()    │            │    │
│  │   │  • raiseDispute()      │  │  • markExpired()       │  │                        │            │    │
│  │   │  • resolveDispute()    │  │                        │  │  Events:               │            │    │
│  │   │                        │  │  Events:               │  │  • AnchorCreated       │            │    │
│  │   │  Events:               │  │  • ConsentGranted      │  │  • ProofVerified       │            │    │
│  │   │  • EscrowCreated       │  │  • ConsentRevoked      │  │                        │            │    │
│  │   │  • EscrowFunded        │  │  • ConsentExpired      │  │                        │            │    │
│  │   │  • EscrowLocked        │  │  • ConsentVerified     │  │                        │            │    │
│  │   │  • EscrowReleased      │  │                        │  │                        │            │    │
│  │   │  • EscrowRefunded      │  │                        │  │                        │            │    │
│  │   │  • DisputeRaised       │  │                        │  │                        │            │    │
│  │   │  • DisputeResolved     │  │                        │  │                        │            │    │
│  │   └────────────────────────┘  └────────────────────────┘  └────────────────────────┘            │    │
│  │                                                                                                  │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                    REQUESTER LAYER                                               │    │
│  │                                                                                                  │    │
│  │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                  │    │
│  │   │  Requester   │───▶│   Request    │───▶│   Escrow     │───▶│  Clean Room  │                  │    │
│  │   │   Portal     │    │  Submission  │    │   Funding    │    │   Delivery   │                  │    │
│  │   │              │    │              │    │              │    │              │                  │    │
│  │   └──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘                  │    │
│  │                                                                      │                          │    │
│  │                                                                      ▼                          │    │
│  │                                                         ┌──────────────────────┐                │    │
│  │                                                         │   Time Capsule       │                │    │
│  │                                                         │   (Decrypted in      │                │    │
│  │                                                         │    Clean Room)       │                │    │
│  │                                                         └──────────────────────┘                │    │
│  │                                                                                                  │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Complete Request → Settlement Flow

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                              COMPLETE REQUEST → SETTLEMENT FLOW                                          │
├─────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                          │
│  PHASE 1: REQUEST CREATION & SCREENING                                                                   │
│  ─────────────────────────────────────                                                                   │
│  Requester → API Gateway → Screening Engine → Policy Validation → Escrow Creation                        │
│                                                                                                          │
│  PHASE 2: ESCROW FUNDING (BLOCKCHAIN)                                                                    │
│  ────────────────────────────────────                                                                    │
│  Requester → EscrowContract.deposit() → EscrowFunded Event → Request Activated                           │
│                                                                                                          │
│  PHASE 3: REQUEST BROADCAST                                                                              │
│  ─────────────────────────                                                                               │
│  Platform → Kafka → DS Request Inbox (Broadcast to all eligible nodes)                                   │
│                                                                                                          │
│  PHASE 4: LOCAL MATCHING (ON-DEVICE)                                                                     │
│  ────────────────────────────────────                                                                    │
│  Request Inbox → Local Matcher → ODX Check → Eligibility Decision                                        │
│                                                                                                          │
│  PHASE 5: CONSENT GRANT                                                                                  │
│  ──────────────────────                                                                                  │
│  DS Reviews Request → Contract Builder → Consent Signed → Platform Notified                              │
│  Platform → ConsentRegistryContract.registerConsent() → ConsentGranted Event                             │
│                                                                                                          │
│  PHASE 6: QUERY EXECUTION (ON-DEVICE)                                                                    │
│  ─────────────────────────────────────                                                                   │
│  QueryPlan Received → PlanVM Sandbox → Local Vault Access → Feature Extraction                           │
│  → Aggregation → Time Capsule Creation                                                                   │
│                                                                                                          │
│  PHASE 7: P2P DELIVERY                                                                                   │
│  ────────────────────                                                                                    │
│  Time Capsule → P2P Transport (WebRTC/libp2p) → Requester (E2E Encrypted)                                │
│  Network Gate: RAW DATA BLOCKED, ONLY CIPHERTEXT ALLOWED                                                 │
│                                                                                                          │
│  PHASE 8: SETTLEMENT                                                                                     │
│  ───────────────────                                                                                     │
│  Delivery Proof → Settlement Service → EscrowContract.release() → EscrowReleased Event                   │
│  → DS Balance Credited (Earned → Available after clearing)                                               │
│                                                                                                          │
│  PHASE 9: AUDIT ANCHORING                                                                                │
│  ────────────────────────                                                                                │
│  Audit Receipts → Merkle Tree → AuditAnchorContract.anchorRoot() → AnchorCreated Event                   │
│  → Tamper-Evident Proof Chain                                                                            │
│                                                                                                          │
│  PHASE 10: PAYOUT                                                                                        │
│  ───────────────────                                                                                     │
│  DS Requests Payout → Available Balance Check → Payout Instruction → External Payment                    │
│                                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Blockchain Integration Status

| Component | Contract | Java Wrapper | Service | Testing Status |
|-----------|----------|--------------|---------|----------------|
| **Escrow** | `YachaqEscrow.sol` | `EscrowContract.java` | `BlockchainEscrowService.java` | ⚠️ Simulated state only |
| **Consent** | `YachaqConsentRegistry.sol` | `ConsentRegistryContract.java` | `BlockchainConsentService.java` | ⚠️ Simulated state only |
| **Audit Anchor** | `YachaqAuditAnchor.sol` | `AuditAnchorContract.java` | `BlockchainAuditAnchorService.java` | ⚠️ Simulated state only |

### Current Configuration
```yaml
yachaq:
  blockchain:
    enabled: false  # DISABLED - No blockchain node running
    nodeUrl: http://localhost:8545
    escrowContractAddress: null
    consentRegistryAddress: null
    auditAnchorAddress: null
    privateKey: null
    gasPrice: 20000000000  # 20 Gwei
    gasLimit: 6721975
```

### Current Problem
The blockchain tests in `SmartContractPropertyTest.java` use **simulated state classes** (`EscrowState`, `ConsentState`, `AnchorState`) instead of real blockchain interaction because:

1. **No blockchain node in docker-compose.yml** - Missing Ganache/Hardhat
2. **No deployed contracts** - `BINARY = ""` in all contracts (no bytecode)
3. **`yachaq.blockchain.enabled = false`** - Blockchain disabled by default

---

## Infrastructure Components

### Current Docker Services (docker-compose.yml)
| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| PostgreSQL | postgres:16-alpine | 55432 | Primary database |
| Redis | redis:7-alpine | 55379 | Caching, rate limiting |
| Kafka | confluentinc/cp-kafka:7.5.0 | 55092 | Event streaming |
| Neo4j | neo4j:5-community | 55474/55687 | Graph database (KIPUX) |

### Missing Infrastructure
| Service | Required For | Status |
|---------|--------------|--------|
| Ganache/Hardhat | Blockchain testing | ❌ NOT CONFIGURED |
| Contract Deployment | Smart contract execution | ❌ NOT DEPLOYED |

---

## Smart Contract Details

### EscrowContract
**Purpose**: Manage escrow funds for data requests with multi-sig governance.

**Functions**:
- `createEscrow(requestId)` - Create new escrow
- `deposit(escrowId, amount)` - Fund escrow
- `lock(escrowId, amount)` - Lock funds for delivery
- `release(escrowId, recipient, amount)` - Release to DS
- `refund(escrowId, amount)` - Refund to requester
- `raiseDispute(escrowId, reason)` - Initiate dispute
- `resolveDispute(escrowId, releaseToDS)` - Resolve with multi-sig

**Status Transitions**:
```
PENDING → FUNDED → LOCKED → SETTLED
                        ↓
                   DISPUTED → SETTLED/REFUNDED
                        ↓
                   REFUNDED
```

### ConsentRegistryContract
**Purpose**: Immutable consent record with expiration and revocation.

**Functions**:
- `registerConsent(consentId, hash, dsId, requesterId, expiresAt)` - Register consent
- `revokeConsent(consentId, dsId)` - Revoke consent
- `verifyConsent(consentId, expectedHash)` - Verify hash
- `isConsentActive(consentId)` - Check active status
- `markExpired(consentId)` - Mark as expired

**Status Transitions**:
```
ACTIVE → EXPIRED
      ↓
   REVOKED
```

### AuditAnchorContract
**Purpose**: Anchor Merkle roots for tamper-evident audit trail.

**Functions**:
- `anchorRoot(merkleRoot, receiptCount, batchMetadataHash)` - Anchor batch
- `verifyProof(anchorId, leafHash, proof, flags)` - Verify inclusion
- `getAnchor(anchorId)` - Get anchor details
- `getAnchorCount()` - Get total anchors

---

## Data Flow Security Boundaries

### Trust Boundaries
| Boundary | Trust Level | Data Allowed |
|----------|-------------|--------------|
| Device Secure Storage | Trusted | Raw data (encrypted) |
| Sandboxed QueryPlan VM | Trusted | Constrained execution |
| Networks | Untrusted | Ciphertext only |
| Coordinator | Untrusted | Metadata only, NO raw data |
| Relays | Untrusted | Ciphertext only |
| Requesters | Untrusted | Consented outputs only |

### Network Gate Rules
| Payload Type | Destination | Allowed |
|--------------|-------------|---------|
| metadata-only | Coordinator | ✅ YES |
| ciphertext-capsule | Requester P2P | ✅ YES |
| raw-payload | ANY | ❌ BLOCKED |

---

## Related Documents
- `requirements.md` - Full requirements specification
- `design.md` - Detailed design document
- `tasks.md` - Implementation task list
- `TESTING_WORKBENCH_REQUIREMENTS.md` - Production-level testing requirements

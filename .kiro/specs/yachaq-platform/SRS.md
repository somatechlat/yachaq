# YACHAQ System Requirements Specification (Consolidated)

**Document Purpose:** Single source of truth for all system requirements, specifications, and technical standards.

**Version:** 1.0  
**Last Updated:** December 2025  
**Status:** APPROVED

---

## TABLE OF CONTENTS

1. [System Overview](#1-system-overview)
2. [System Objectives](#2-system-objectives)
3. [Core Invariants](#3-core-invariants)
4. [Actors & Trust Boundaries](#4-actors--trust-boundaries)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Non-Functional Requirements](#6-non-functional-requirements)
7. [Security Requirements](#7-security-requirements)
8. [Module Specifications](#8-module-specifications)
9. [Phone-as-Node Specification](#9-phone-as-node-specification)
10. [Metering & Verification](#10-metering--verification)
11. [Intelligence & Math Architecture](#11-intelligence--math-architecture)
12. [Threat Model & War Games](#12-threat-model--war-games)
13. [Pricing & Economics](#13-pricing--economics)
14. [Data Model](#14-data-model)
15. [Integration Requirements](#15-integration-requirements)
16. [Compliance Requirements](#16-compliance-requirements)

---

## 1. SYSTEM OVERVIEW

YACHAQ is a **consent-first personal data and knowledge sovereignty system** enabling:
- Granular, purpose-bound, time-bound data access requests
- Privacy-preserving matching to eligible users
- Explicit acceptance/negotiation/rejection by users
- Immutable audit trails for all events
- Uniform compensation per defined unit, displayed in user local currency
- Direct settlement through compliant payout rails
- Enterprise and community requester access via portal + APIs
- Open/inspectable protocol components, with secure closed operations secrets

**YACHAQ** (ya·chaq) — From Quechua: "one who knows, one who learns, one who understands."

---

## 2. SYSTEM OBJECTIVES

| ID | Objective |
|----|-----------|
| SO-1 | Ensure humans control data use through explicit consent |
| SO-2 | Make all access auditable, explainable, and user-visible |
| SO-3 | Enable legal, transparent compensation with non-speculative utility credits |
| SO-4 | Support both Verified Organizations and Community Requesters with tiered controls |
| SO-5 | Prevent operator access to raw personal data by default (privacy by architecture) |
| SO-6 | Operate globally with region-appropriate compliance and payout methods |

---

## 3. CORE INVARIANTS (Non-Negotiable)

These are **hard constraints** that must be true in production:

1. **Raw DS data stays device-resident by default**
2. **Discovery uses ODX summaries only** — no raw data in discovery
3. **Fine access requires explicit ConsentContract + signed QueryPlan**
4. **Time Capsules are encrypted and TTL-enforced** — crypto-shred + deletion receipts
5. **Default delivery is Clean Room** — exports are gated and high-risk
6. **All key events emit AuditReceipts** — append-only with Merkle batching
7. **KIPUX abstraction:** knots=receipts, cords=threads, weave=provenance graph
8. **Settlement uses escrow prefunding + double-entry accounting**
9. **Payout based on unit semantics**, not device count
10. **Anti-targeting + minors protections + strict allow-list transforms**

---

## 4. ACTORS & TRUST BOUNDARIES

### 4.1 Actors

| Actor | Description |
|-------|-------------|
| DS (Data Sovereign) | End user who owns data and consent decisions |
| Requester VO | Verified Organization (higher trust) |
| Requester CR | Community Requester (limited) |
| Platform Operator | SaaS admin console |
| Compliance/Audit Reviewer | Read-only access |
| Payment Provider(s) | Stripe, regional providers |
| Identity Provider(s) | OAuth (Google, Apple, Facebook) |

### 4.2 Trust Boundaries

| Boundary | Description |
|----------|-------------|
| DS Device | Private data stays here |
| Consent | Scope/purpose/time enforcement |
| Requester | Cannot see raw DS data without explicit reveal |
| Operator | Cannot access raw DS data by default |
| Ledger | Immutable receipts only |

---

## 5. HIGH-LEVEL ARCHITECTURE

### 5.1 Logical Components

1. **User Mobile App (iOS/Android)**
2. **User Web Portal**
3. **Requester Portal (Enterprise + Community)**
4. **YACHAQ SaaS Control Plane (Operator)**
5. **Consent Engine**
6. **Policy/Safety Screening Engine** (AI + rules)
7. **Matching Engine** (privacy-preserving)
8. **Data Connectors & Ingestion**
9. **Privacy Layer** (anonymization, pseudonymization, ZK proofs)
10. **Data Storage** (encrypted)
11. **Data Access Gateway** (scoped tokens)
12. **Audit Ledger Layer** (append-only + optional blockchain anchoring)
13. **Settlement Engine** (escrow, fees, YC accounting)
14. **Payout Orchestrator** (local currency, rails)
15. **Observability & Security Operations**

### 5.2 Node Network Roles

| Role | Function | Decrypts? |
|------|----------|-----------|
| RG (Relay Gateway) | Route encrypted envelopes | NO |
| CCW (Confidential Compute Worker) | Execute in TEE | YES (attested) |
| DN (Delivery Node) | Serve clean-room sessions | Controlled |

---

## 6. NON-FUNCTIONAL REQUIREMENTS

### 6.1 Availability & Reliability

| ID | Requirement |
|----|-------------|
| NFR-A1 | 99.9% monthly uptime (control plane APIs) |
| NFR-A2 | Degraded mode allows DS to view last-known consent/audit |
| NFR-A3 | DR: RPO ≤ 15 min, RTO ≤ 4 hours |

### 6.2 Performance

| ID | Requirement |
|----|-------------|
| NFR-P1 | Consent acceptance → confirmation ≤ 2s p95 |
| NFR-P2 | Request screening ≤ 5s p95 sync, ≤ 60s async |
| NFR-P3 | Audit receipt generation ≤ 1s p95 |
| NFR-P4 | Portal page load ≤ 2.5s p75 |

### 6.3 Scalability

| ID | Requirement |
|----|-------------|
| NFR-S1 | Horizontal scaling for matching/screening/notification |
| NFR-S2 | Storage separation: hot audit index vs cold archive |

### 6.4 UX Principles

| ID | Requirement |
|----|-------------|
| NFR-U1 | No dark patterns; no forced nudges |
| NFR-U2 | Quiet by design: user controls notifications |
| NFR-U3 | 8th-grade reading level "Simple View" + expandable "Technical View" |

---

## 7. SECURITY REQUIREMENTS

### 7.1 Identity & Authentication

| ID | Requirement |
|----|-------------|
| SR-I1 | OAuth2/OIDC for DS login |
| SR-I2 | MFA mandatory for VO requesters |
| SR-I3 | Short-lived tokens, refresh rotation, device binding |
| SR-I4 | Admin console: phishing-resistant MFA (WebAuthn) |

### 7.2 Authorization & Access Control

| ID | Requirement |
|----|-------------|
| SR-A1 | Zero-trust: every call authenticated + authorized (mTLS + JWT) |
| SR-A2 | ABAC/RBAC hybrid |
| SR-A3 | Least privilege; default deny |

### 7.3 Data Protection

| ID | Requirement |
|----|-------------|
| SR-D1 | TLS 1.2+ in transit (TLS 1.3 preferred) |
| SR-D2 | AES-256 at rest (KMS-managed) |
| SR-D3 | Field-level encryption for sensitive attributes |
| SR-D4 | HSM/KMS key management with rotation |

### 7.4 Privacy & Minimization

| ID | Requirement |
|----|-------------|
| SR-P1 | Collect minimum necessary; DS sees what/why/how long |
| SR-P2 | Raw data never sent to Requester without identity reveal consent |
| SR-P3 | Prefer on-device or proof-based eligibility checks |

### 7.5 Audit & Logging

| ID | Requirement |
|----|-------------|
| SR-L1 | Immutable audit receipts for all key events |
| SR-L2 | No raw payload in logs |
| SR-L3 | Security events to SIEM with tamper-evident retention |

### 7.6 Secure SDLC

| ID | Requirement |
|----|-------------|
| SR-SD1 | SAST/DAST/dependency scanning mandatory |
| SR-SD2 | Threat modeling per module |
| SR-SD3 | Pen test before beta, then quarterly |

### 7.7 Abuse/Fraud Controls

| ID | Requirement |
|----|-------------|
| SR-F1 | Requester rate limits, spend limits, anomaly detection |
| SR-F2 | DS payout fraud detection (velocity, fingerprinting, behavioral) |
| SR-F3 | Escrow holds + dispute workflows |

---

## 8. MODULE SPECIFICATIONS

### 8.1 DS Mobile App

**Purpose:** Data management, consent control, earnings

**Key Features:**
- Data source connections (OAuth connectors)
- Consent dashboard per category/attribute/purpose/duration
- Request inbox with requester tier, purpose, scope, LCD payout
- Audit timeline
- Payout wallet
- Emergency "pause all sharing"

### 8.2 Consent Engine

**Purpose:** Policy decision point for all data access

**Interfaces:**
- `createContract(ds, requester, scope, purpose, duration, price)`
- `evaluateAccess(contractId, requestedFields)`
- `revokeContract(contractId)`
- `getContracts(dsId)`

### 8.3 Query Orchestrator

**Purpose:** Dispatch live queries to devices

**Interfaces:**
- `dispatchQuery(queryPlan, eligibleDevices)`
- `collectResponses(queryId, timeout)`
- `buildCapsule(responses, ttl)`

### 8.4 Screening Engine

**Purpose:** Block abusive/illegal requests

**Interfaces:**
- `screenRequest(request)` → pass/fail + reason codes
- `getReasonCodes(screeningId)`
- `appealDecision(screeningId, evidence)`

### 8.5 Audit Receipt Ledger

**Purpose:** Immutable record of all events

**Interfaces:**
- `appendReceipt(event, actor, timestamp, hashes)`
- `getReceipts(filter)`
- `verifyReceipt(receiptId, proof)`
- `anchorBatch(receipts)` → blockchain

### 8.6 Financial Ledger

**Purpose:** Double-entry accounting

**Interfaces:**
- `postEntry(debit, credit, amount, reference)`
- `getBalance(accountId)`
- `reconcile(providerId, statement)`

### 8.7 Privacy Governor

**Purpose:** PRB, k-min cohorts, linkage defense

**Interfaces:**
- `allocatePRB(campaignId, riskProfile)`
- `lockPRB(campaignId)`
- `consumePRB(campaignId, transformId, riskCost)`
- `checkCohort(criteria, kMin)`
- `detectLinkage(requesterId, queryHistory)`

### 8.8 Clean Room Delivery

**Purpose:** Controlled data access environment

**Interfaces:**
- `createSession(capsuleId, requesterId, ttl)`
- `renderData(sessionId, query)`
- `logInteraction(sessionId, action)`
- `terminateSession(sessionId)`

### 8.9 KIPUX Graph Store

**Purpose:** Provenance graphs (knots, cords, weaves)

**Interfaces:**
- `appendKnot(knotType, data, cordId)`
- `createCord(cordType, metadata)`
- `queryWeave(filter)`
- `computeAnomalyScore(graphId)`

---

## 9. PHONE-AS-NODE SPECIFICATION

### 9.1 Default Decision

Phones are **first-class execution endpoints** (Full Agent) and may perform **non-decrypting network roles**:
- **Full Agent:** On-device data store + ODX + signed QueryPlan execution
- **Relay/Witness:** Route encrypted envelopes, mirror presence/index

Phones **MUST NOT** be decrypting CCW by default.

### 9.2 Device Lifecycle States

```
PENDING_ENROLLMENT → ACTIVE → QUARANTINED → DECOMMISSIONED
                         ↓
                    SUSPENDED
```

### 9.3 Device Caps & Anti-Farming

| Control | Description |
|---------|-------------|
| Enrollment Cap | 3-5 devices per DS-IND (policy value) |
| Concurrency Cap | Limits devices per DS per campaign |
| Unit Semantics Dedupe | UNIT-PERSON pays once per DS |

### 9.4 Fraud Hooks

- Device fingerprinting & integrity
- Location integrity (cross-source verification)
- Behavioral biometrics (anti-bot)
- Audit challenges

### 9.5 Lost/Compromised Device

One-button emergency action triggers:
- Immediate server-side quarantine
- Revoke refresh tokens
- Invalidate device presence
- Deny job dispatch
- Emit `DeviceQuarantineReceipt`

---

## 10. METERING & VERIFICATION

### 10.1 Three Separate Meters

| Meter | What It Measures | Who Gets Paid |
|-------|------------------|---------------|
| VU (Value Units) | Unit semantics completion | DS |
| RU (Resource Units) | CPU, memory, IO, bandwidth | Nodes |
| TU (Token Units) | LLM inference | Platform cost |

### 10.2 Unit Semantics Catalog (USC)

Every paid request references:
- **Unit Type:** PERSON / DEVICE / LOCATION / TIME / OBSERVATION
- **USID:** Auditable definition of fields, transforms, restrictions, TTL, quality

### 10.3 Verification Protocol

Requesters can verify (without raw data):
1. **Capsule Manifest:** Hashed field list, transform provenance, policy refs
2. **Proof Bundle:** Device signatures, attestations, redundancy receipts
3. **Delivery Receipts:** Creation, access, TTL, deletion timestamps

### 10.4 Disputes

- Settlement is automatic on objective rules
- Requester can open dispute within defined window
- Disputed units covered by risk reserve/holdback
- No "buyer approval hostage" pattern

---

## 11. INTELLIGENCE & MATH ARCHITECTURE

### 11.1 Normative Principles

| ID | Principle |
|----|-----------|
| P1 | AI may recommend; policy+math+receipts decide |
| P2 | Fail closed on uncertainty for safety-critical decisions |
| P3 | No raw payload in discovery (ODX-only) |
| P4 | Consent-first execution |
| P5 | Default clean-room delivery |
| P6 | Proof-first operations (receipts, Merkle batching) |
| P7 | Unit semantics payout, not device count |

### 11.2 AI Allowed Responsibilities

- Recommend campaign budgets and expected fill
- Assist policy classification (risk grading)
- Surface fraud patterns and propose holds
- Operate inside clean-room as constrained assistant

### 11.3 AI Forbidden Responsibilities

- Override consent or alter QueryPlan scope
- Enable narrow targeting or re-identification
- Change price/terms after acceptance
- Finalize DS payouts without deterministic checks

### 11.4 Privacy Risk Budget (PRB)

```
PRB_remaining = PRB_allocated − Σ risk_cost(transform, sensitivity, export_mode, cohort)
```

- Allocated at quote time
- Locked at acceptance
- Exports consume orders-of-magnitude more PRB

---

## 12. THREAT MODEL & WAR GAMES

### 12.1 Adversary Profiles

1. Malicious requester (re-identify, narrow-target, extract)
2. Careless requester (accidental risky cohort)
3. Fraud seller ring (device farms, co-location)
4. Compromised device (malware, fake proofs)
5. Node operator attacker (bypass confidentiality)
6. Insider threat (unauthorized access)
7. Competitor/attacker (DDoS, reputation poison)
8. Regulators/auditors (deplatforming risk)

### 12.2 Critical Failure Modes

| Mode | Controls | Kill Switches |
|------|----------|---------------|
| Privacy failure | k-min, PRB, rate limits | Derived-only mode |
| Buyer misuse | KYB, DUA, clean-room, watermarking | Disable exports |
| Fraud collapse | Unit dedupe, caps, holdback | Freeze payouts |
| PSP deplatforming | No custody, clear MO | Switch rails |
| App store removal | Purpose-bound permissions | Disable sensitive features |
| Security breach | No payload in logs, M-of-N keys | Rotate keys, quarantine |
| Node integrity failure | Attestation, redundancy | Operator-only nodes |

### 12.3 War Game Scenarios (Top 20)

1. Narrow cohort attempt
2. Repeated-query linkage attack
3. Export bypass attempt
4. Requester chargeback storm
5. DS device farm
6. Compromised phone exfil
7. Node lies about useful work
8. Ledger replication degraded
9. TTL expiry fails
10. Payload in logs
11. Minor in sensitive category
12. PSP high-risk flag
13. App store review challenge
14. Regional outage
15. Regulator inquiry
16. Requester re-ID attempt
17. Dispute abuse
18. Live event 100× spike
19. Cross-border constraints
20. Model/policy misclassification

---

## 13. PRICING & ECONOMICS

### 13.1 Uniform Compensation Model

- Same unit price for all DS in same request
- Local Currency Display (LCD) via platform FX reference
- YC equivalent shown optionally

### 13.2 Unit Types

| Type | Description |
|------|-------------|
| UNIT-PERSON | Pay once per DS identity |
| UNIT-DEVICE | Pay per verified distinct device |
| UNIT-LOCATION | Pay per verified sensor location |
| UNIT-TIME | Pay per minute/hour of participation |
| UNIT-OBSERVATION | Pay per verified observation/event |

### 13.3 Escrow Semantics

- Requester must pre-fund before delivery
- Amount = (maxParticipants × unitPrice × maxUnits) + platformFee
- Settlement atomic with delivery
- Failed deliveries trigger automatic refund

### 13.4 Market Integrity

- Auction mode for price discovery
- Category rate cards as guidance
- Anti-manipulation detection
- Floors/ceilings for protected categories

---

## 14. DATA MODEL

### 14.1 Core Entities

```typescript
interface DSProfile {
  id: UUID;
  pseudonym: string;
  status: 'active' | 'suspended' | 'banned';
  accountType: 'DS-IND' | 'DS-COMP' | 'DS-ORG';
}

interface ConsentContract {
  id: UUID;
  dsId: UUID;
  requesterId: UUID;
  scopeHash: string;
  purposeHash: string;
  durationStart: DateTime;
  durationEnd: DateTime;
  status: 'active' | 'revoked' | 'expired';
  compensationAmount: Decimal;
}

interface Request {
  id: UUID;
  requesterId: UUID;
  purpose: string;
  unitType: 'survey' | 'data_access' | 'participation';
  unitPrice: Decimal;
  maxParticipants: number;
  budget: Decimal;
  escrowId: UUID;
  status: 'draft' | 'screening' | 'active' | 'completed';
}

interface AuditReceipt {
  id: UUID;
  eventType: EventType;
  timestamp: DateTime;
  actorId: UUID;
  detailsHash: string;
  merkleProof?: MerkleProof;
  previousReceiptHash: string;
}

interface TimeCapsule {
  id: UUID;
  requestId: UUID;
  consentContractId: UUID;
  fieldManifestHash: string;
  encryptedPayload: EncryptedBlob;
  ttl: DateTime;
  status: 'created' | 'delivered' | 'expired' | 'deleted';
}
```

---

## 15. INTEGRATION REQUIREMENTS

| Integration | Purpose |
|-------------|---------|
| OAuth providers | Apple/Google/Facebook login |
| Payment Gateway | Requester payments |
| Payout Provider | DS payouts |
| Stripe | International payments |
| KYB/KYC vendors | Verification |
| Notification services | Email/SMS/push |
| HSM/KMS | Key management |
| SIEM | Security monitoring |

---

## 16. COMPLIANCE REQUIREMENTS

### 16.1 Design Targets

| Regulation | Status |
|------------|--------|
| GDPR | Consent, portability, deletion, purpose limitation |
| CCPA | Access, deletion, sale/sharing transparency |
| ISO 27001/27701 | Policies, controls mapping, audit trails |
| Regional Data Protection | Exceeds requirements |
| COPPA | Parental consent for under-13 |

### 16.2 Data Localization

Support regional storage options where required.

---

**END OF SRS**

*This document consolidates all system requirements specifications for YACHAQ.*

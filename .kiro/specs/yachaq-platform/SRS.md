# YACHAQ System Requirements Specification (Complete)

**Document Purpose:** Single source of truth for all system requirements, specifications, and technical standards for the YACHAQ system/platform.

**Version:** 1.0  
**Last Updated:** December 2025  
**Status:** APPROVED (Consolidated)

> **Assurance statement:** The system is designed for **high assurance** and **verifiable privacy-by-architecture**. No real system is literally “unbreakable,” but YACHAQ is specified to be **defense-in-depth, fail-closed, and auditable**.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [System Objectives](#2-system-objectives)
3. [Core Invariants](#3-core-invariants)
4. [Actors & Trust Boundaries](#4-actors--trust-boundaries)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Functional Requirements](#6-functional-requirements)
7. [Non-Functional Requirements](#7-non-functional-requirements)
8. [Security Requirements](#8-security-requirements)
9. [Module Specifications](#9-module-specifications)
10. [Phone-as-Node Specification](#10-phone-as-node-specification)
11. [Metering & Verification](#11-metering--verification)
12. [Intelligence & Math Architecture](#12-intelligence--math-architecture)
13. [Threat Model & War Games](#13-threat-model--war-games)
14. [Pricing & Economics](#14-pricing--economics)
15. [Data Model](#15-data-model)
16. [Integration Requirements](#16-integration-requirements)
17. [Compliance Requirements](#17-compliance-requirements)
18. [Appendix A — ODX Ontology v1](#appendix-a--odx-ontology-v1)
19. [Appendix B — ODX Criteria Language v1](#appendix-b--odx-criteria-language-v1)
20. [Appendix C — QueryPlan DSL v1](#appendix-c--queryplan-dsl-v1)
21. [Appendix D — Clean-Room Output Schemas v1](#appendix-d--clean-room-output-schemas-v1)
22. [Appendix E — AuditReceipts & KIPUX Provenance](#appendix-e--auditreceipts--kipux-provenance)

---

## 1. System Overview

YACHAQ is a **consent-first personal data and knowledge sovereignty system** enabling:
- Granular, purpose-bound, time-bound data access requests
- Privacy-preserving matching to eligible users
- Explicit acceptance / negotiation / rejection by users
- Immutable audit trails for all key events
- Uniform compensation per defined unit, displayed in user local currency
- Direct settlement through compliant payout rails
- Enterprise (Verified Organizations) and community requester access via portal + APIs
- Open/inspectable protocol components, with narrowly-scoped operational secrets (e.g., signing keys)

**Meaning:** *YACHAQ* (Quechua) — “one who knows, learns, understands.”

**Core design choice:** **Phone-as-Node**.
- The phone is the **execution endpoint**.
- Raw data stays on device by default.
- Discovery uses an on-device summary index (**ODX**) only.
- Fulfillment is a signed **QueryPlan** executed locally.
- Delivery is P2P via encrypted **Time Capsules**.

---

## 2. System Objectives

| ID | Objective |
|----|-----------|
| SO-1 | Ensure humans control data use through explicit consent |
| SO-2 | Make all access auditable, explainable, and user-visible |
| SO-3 | Enable legal, transparent compensation with non-speculative utility credits and/or fiat payouts |
| SO-4 | Support Verified Organizations (VO) and Community Requesters (CR) with tiered controls |
| SO-5 | Prevent operator access to raw personal data by default (privacy by architecture) |
| SO-6 | Operate globally with region-appropriate compliance and payout methods |

---

## 3. Core Invariants (Non-Negotiable)

These constraints MUST be true in production:

1) **Raw DS data stays device-resident by default**
2) **Discovery uses ODX summaries only** (no raw payload in discovery)
3) **Fine access requires explicit ConsentContract + signed QueryPlan**
4) **Time Capsules are encrypted and TTL-enforced** (crypto-shred + deletion receipts, where enforceable)
5) **Default delivery is Clean Room** (exports are gated and high-risk)
6) **All key events emit AuditReceipts** (append-only, Merkle batched)
7) **KIPUX abstraction:** knots=receipts, cords=threads, weave=provenance graph
8) **Settlement uses escrow prefunding + double-entry accounting**
9) **Payout based on unit semantics**, not device count
10) **Anti-targeting + minors protections + strict allow-list transforms**

---

## 4. Actors & Trust Boundaries

### 4.1 Actors

| Actor | Description |
|-------|-------------|
| DS (Data Sovereign) | End user who owns data and consent decisions |
| Requester VO | Verified Organization (higher trust tier) |
| Requester CR | Community Requester (limited tier) |
| Platform Operator | Runs the control plane; no raw data access by default |
| Compliance/Audit Reviewer | Read-only access to receipts and control-plane audit trails |
| Payment Provider(s) | Payments and payouts rails |
| Identity Provider(s) | OAuth/OIDC for login; optional VC/DID for identity reveal |

### 4.2 Trust boundaries

| Boundary | Description |
|----------|-------------|
| DS Device | Private raw data and ODX live here |
| Consent | Scope/purpose/time enforcement; fail-closed |
| Requester | Receives only what DS consented to; default de-identified |
| Operator | Control plane only; no raw data ingestion paths |
| Ledger | Immutable receipts only |

---

## 5. High-Level Architecture

### 5.1 Logical components

1) **DS Mobile App (iOS/Android)** — Node Runtime + UI
2) **DS Web Portal** — account, receipts, payouts overview
3) **Requester Portal (VO + CR)** — create requests, verify capsules, manage disputes
4) **YACHAQ Control Plane (Operator)** — policy, moderation, templates, ops
5) **Consent Engine** — contract creation, evaluation, revocation
6) **Policy/Safety Screening Engine** — rules + AI assistance, emits signed PolicyStamp
7) **Matching Engine** — privacy-preserving distribution and local matching
8) **Connector Framework** — OS frameworks, OAuth, user-provided exports
9) **Privacy Governor** — PRB (risk budgeting), k-min cohorts, linkage defense
10) **Audit Ledger Layer** — append-only receipts, Merkle batching, optional anchoring
11) **Settlement Engine** — escrow, fees, double-entry ledger
12) **Payout Orchestrator** — local currency rails
13) **Observability & SecOps** — telemetry without personal payload

### 5.2 Node network roles

| Role | Function | Decrypts? | Default in v1 |
|------|----------|-----------|---------------|
| RG (Relay Gateway) | Route encrypted envelopes | NO | Optional |
| DN (Delivery Node) | Serve clean-room sessions (requester side) | Controlled | Optional |
| CCW (Confidential Compute Worker) | Execute in TEE | YES (attested) | **NOT default** |

**CCW note:** Any decryption outside DS device conflicts with the default invariant. Therefore, CCW is **disabled by default** and may only be enabled as an explicit, high-risk mode (treated as “export-equivalent”), subject to policy and explicit DS consent.

### 5.3 Data movement model
- **Discovery:** coordinator → node (request broadcast); node matches locally using ODX.
- **Fulfillment:** node executes QueryPlan locally, produces Time Capsule.
- **Delivery:** node → requester P2P transfer; coordinator may provide signaling/relay for ciphertext only.

---

## 6. Functional Requirements

### 6.1 Account, onboarding, and trust center
- FR-ONB-1: DS onboarding MUST explain invariants and show a “Proof & Transparency” dashboard.
- FR-ONB-2: DS MUST be able to select privacy presets (Minimal/Standard/Full) and customize.
- FR-ONB-3: DS MUST have an emergency “Pause all sharing” kill switch.

### 6.2 Data acquisition (device-only)
- FR-DATA-1: Data acquisition MUST be local-only: OS frameworks, OAuth, and user-provided exports/imports.
- FR-DATA-2: The system MUST NOT scrape other apps, log keystrokes, or bypass OS privacy boundaries.
- FR-DATA-3: Imports MUST never be uploaded; parsing MUST be streaming and size-limited.

### 6.3 ODX (On-Device Discovery Index)
- FR-ODX-1: ODX MUST contain only labels, aggregates, buckets, cluster IDs, and coarse geo/time.
- FR-ODX-2: ODX MUST never store raw text/audio/media or precise GPS by default.
- FR-ODX-3: ODX MUST support “Why did I match?” explanations locally.

### 6.4 Request lifecycle
- FR-REQ-1: Requesters MUST create requests via templates or custom criteria (ODX criteria language).
- FR-REQ-2: Every request MUST be screened; approved requests MUST carry a signed PolicyStamp.
- FR-REQ-3: High-risk requests MUST be automatically downscoped (coarse-only, derived-only, higher k-min).

### 6.5 ConsentContracts
- FR-CC-1: DS MUST review plain-language summaries and technical view before accepting.
- FR-CC-2: ConsentContracts MUST include scope, purpose hash, duration, payout, TTL, identity requirement.
- FR-CC-3: DS MUST be able to reject or negotiate scope.

### 6.6 QueryPlan execution
- FR-QP-1: QueryPlan MUST be signed by requester, validated against contract and policy stamp.
- FR-QP-2: QueryPlan MUST execute in a sandbox (allowlisted ops, no network, resource limits).
- FR-QP-3: Plan Preview MUST show outputs and privacy impact before execution.

### 6.7 Time Capsules & P2P delivery
- FR-CAP-1: Time Capsules MUST be encrypted end-to-end and bound to ConsentContract + QueryPlan.
- FR-CAP-2: P2P transfer MUST be resumable, integrity-checked, and receipt-based.
- FR-CAP-3: TTL must be enforced via key lifecycle controls (crypto-shred) and deletion receipts where applicable.

### 6.8 AuditReceipts & KIPUX provenance
- FR-AUD-1: All key events MUST emit AuditReceipts.
- FR-AUD-2: Receipts MUST be append-only and Merkle-batched.
- FR-AUD-3: DS MUST be able to export an audit bundle for disputes and audits.

### 6.9 Settlement & payouts
- FR-FIN-1: Requesters MUST pre-fund escrow prior to delivery.
- FR-FIN-2: Double-entry ledger MUST reconcile all movements.
- FR-FIN-3: DS sees uniform unit price and local currency display.

### 6.10 Identity reveal (optional)
- FR-ID-1: DS identity is anonymous/pseudonymous by default.
- FR-ID-2: If identity reveal is required, DS MUST opt in explicitly.
- FR-ID-3: Identity artifacts MUST be sent directly to requester (P2P), not through YACHAQ servers.

---

## 7. Non-Functional Requirements

### 7.1 Availability & reliability
| ID | Requirement |
|----|-------------|
| NFR-A1 | 99.9% monthly uptime for control-plane APIs |
| NFR-A2 | Degraded mode allows DS to view last-known consent/audit |
| NFR-A3 | DR: RPO ≤ 15 min, RTO ≤ 4 hours |

### 7.2 Performance
| ID | Requirement |
|----|-------------|
| NFR-P1 | Consent acceptance → confirmation ≤ 2s p95 |
| NFR-P2 | Request screening ≤ 5s p95 sync, ≤ 60s async |
| NFR-P3 | Audit receipt generation ≤ 1s p95 |
| NFR-P4 | Portal page load ≤ 2.5s p75 |

### 7.3 UX principles
| ID | Requirement |
|----|-------------|
| NFR-U1 | No dark patterns; no forced nudges |
| NFR-U2 | Quiet by design: user controls notifications |
| NFR-U3 | 8th-grade reading level “Simple View” + expandable “Technical View” |

---

## 8. Security Requirements

### 8.1 Identity & authentication
| ID | Requirement |
|----|-------------|
| SR-I1 | OAuth2/OIDC for DS login |
| SR-I2 | MFA mandatory for VO requesters |
| SR-I3 | Short-lived tokens, refresh rotation, device binding |
| SR-I4 | Admin console uses phishing-resistant MFA (WebAuthn) |

### 8.2 Authorization & access control
| ID | Requirement |
|----|-------------|
| SR-A1 | Zero-trust: every call authenticated + authorized |
| SR-A2 | ABAC/RBAC hybrid |
| SR-A3 | Least privilege; default deny |

### 8.3 Data protection
| ID | Requirement |
|----|-------------|
| SR-D1 | TLS 1.2+ in transit (TLS 1.3 preferred) |
| SR-D2 | Encryption at rest for all control-plane secrets |
| SR-D3 | Field-level encryption for sensitive control-plane attributes |
| SR-D4 | KMS/HSM-managed key rotation for operator keys |

### 8.4 Privacy & minimization
| ID | Requirement |
|----|-------------|
| SR-P1 | Collect minimum necessary; DS sees what/why/how long |
| SR-P2 | Raw data never sent to requester without explicit consent and strict gating |
| SR-P3 | Prefer on-device/proof-based eligibility checks |
| SR-P4 | Anti-targeting protections; k-min cohorts; linkage defense |

### 8.5 Audit & logging
| ID | Requirement |
|----|-------------|
| SR-L1 | Immutable AuditReceipts for all key events |
| SR-L2 | No raw payload in logs |
| SR-L3 | Security events to SIEM with tamper-evident retention |

### 8.6 Secure SDLC
| ID | Requirement |
|----|-------------|
| SR-SD1 | SAST/DAST/dependency scanning mandatory |
| SR-SD2 | Threat modeling per module |
| SR-SD3 | Pen test before beta, then quarterly |

### 8.7 Abuse/fraud controls
| ID | Requirement |
|----|-------------|
| SR-F1 | Requester rate limits, spend limits, anomaly detection |
| SR-F2 | DS payout fraud detection (velocity, device integrity, unit dedupe) |
| SR-F3 | Escrow holds + objective dispute workflows |

---

## 9. Module Specifications

This section enumerates the complete modules required for a full YACHAQ platform.

### 9.1 DS Mobile App (Provider)
**Purpose:** Local data management, consent control, earnings, audit, delivery.

**Modules:**
1) Onboarding & Trust Center
2) Connector Manager (OS frameworks, OAuth, Imports)
3) Permissions Console (OS + YACHAQ scopes)
4) ODX Inspector (local-only)
5) Requests Inbox
6) Consent Studio (scope editor + plan preview)
7) Plan Execution Monitor
8) P2P Delivery Monitor
9) Earnings Wallet + Receipts
10) Identity Wallet (optional)
11) Audit Timeline + Export
12) Emergency Controls (pause/revoke/purge)

### 9.2 DS Web Portal
- View consents, earnings, receipts, disputes.
- Export audit bundles.
- Manage payout preferences.

### 9.3 Requester Portal (VO + CR)
- Create requests (templates/custom)
- Budget/PRB estimation
- Policy outcomes and downscoping suggestions
- Capsule verification tools
- Clean-room session controls (if enabled)
- Disputes and evidence

### 9.4 Operator Control Plane
- Requester vetting & tier management
- Policy configuration and moderation workflows
- Template registry and schema governance
- Incident response, key rotation, and security controls

### 9.5 Consent Engine
Interfaces:
- `createContract(ds, requester, scope, purpose, duration, price)`
- `evaluateAccess(contractId, requestedFields)`
- `revokeContract(contractId)`
- `getContracts(dsId)`

### 9.6 Query Orchestrator (Distribution + coordination)
- Publish approved requests (broadcast or rotating topics)
- Provide rendezvous/signaling tokens
- Never collects raw data

### 9.7 Screening Engine
Interfaces:
- `screenRequest(request)` → pass/fail + reason codes
- `getReasonCodes(screeningId)`
- `appealDecision(screeningId, evidence)`

### 9.8 Audit Receipt Ledger
Interfaces:
- `appendReceipt(event, actor, timestamp, hashes)`
- `getReceipts(filter)`
- `verifyReceipt(receiptId, proof)`
- `anchorBatch(receipts)` (optional)

### 9.9 Financial Ledger (Double-entry)
Interfaces:
- `postEntry(debit, credit, amount, reference)`
- `getBalance(accountId)`
- `reconcile(providerId, statement)`

### 9.10 Privacy Governor
Interfaces:
- `allocatePRB(campaignId, riskProfile)`
- `lockPRB(campaignId)`
- `consumePRB(campaignId, transformId, riskCost)`
- `checkCohort(criteria, kMin)`
- `detectLinkage(requesterId, queryHistory)`

### 9.11 Clean Room Delivery (optional, strongly gated)
Interfaces:
- `createSession(capsuleId, requesterId, ttl)`
- `renderData(sessionId, query)`
- `logInteraction(sessionId, action)`
- `terminateSession(sessionId)`

### 9.12 KIPUX Graph Store
Interfaces:
- `appendKnot(knotType, data, cordId)`
- `createCord(cordType, metadata)`
- `queryWeave(filter)`
- `computeAnomalyScore(graphId)`

---

## 10. Phone-as-Node Specification

### 10.1 Default decision
Phones are first-class execution endpoints (“Full Agent”):
- On-device store + vault
- ODX build
- Signed QueryPlan execution

Phones MAY optionally act as non-decrypting network roles (relay/witness) where safe.

### 10.2 Device lifecycle states
`PENDING_ENROLLMENT → ACTIVE → SUSPENDED → QUARANTINED → DECOMMISSIONED`

### 10.3 Device caps & anti-farming
| Control | Description |
|---------|-------------|
| Enrollment Cap | 3–5 devices per DS-IND (policy value) |
| Concurrency Cap | Limits devices per DS per campaign |
| Unit Semantics Dedupe | UNIT-PERSON pays once per DS |

### 10.4 Integrity and fraud hooks
- Device integrity signals (soft fail; degrade outputs)
- Rate limits and anti-sybil constraints
- Audit challenges (proof of execution, redundancy receipts)

### 10.5 Lost/compromised device
One-button emergency action triggers:
- Quarantine device token
- Revoke refresh tokens
- Invalidate device presence
- Deny job dispatch
- Emit `DeviceQuarantineReceipt`

---

## 11. Metering & Verification

### 11.1 Three separate meters
| Meter | What it measures | Who gets paid |
|------|-------------------|--------------|
| VU (Value Units) | Unit semantics completion | DS |
| RU (Resource Units) | CPU/memory/IO/bandwidth | Nodes (optional) |
| TU (Token Units) | LLM inference | Platform cost |

### 11.2 Unit Semantics Catalog (USC)
Every paid request references:
- Unit Type: PERSON / DEVICE / LOCATION / TIME / OBSERVATION
- USID: auditable definition of fields, transforms, restrictions, TTL, quality

### 11.3 Verification protocol
Requesters verify without raw discovery:
1) Capsule Manifest (hashed fields list + transform provenance)
2) Proof Bundle (signatures, policy references, receipts)
3) Delivery Receipts (created/delivered/expired/deleted)

### 11.4 Disputes
- Objective rules based on receipts
- Dispute window defined per request
- Risk reserve/holdback covers disputes
- No “buyer approval hostage” pattern

---

## 12. Intelligence & Math Architecture

### 12.1 Normative principles
| ID | Principle |
|----|-----------|
| P1 | AI may recommend; policy+math+receipts decide |
| P2 | Fail closed on uncertainty for safety-critical decisions |
| P3 | No raw payload in discovery (ODX-only) |
| P4 | Consent-first execution |
| P5 | Default clean-room delivery |
| P6 | Proof-first operations (receipts, Merkle batching) |
| P7 | Unit semantics payout, not device count |

### 12.2 AI allowed responsibilities
- Recommend budgets and expected fill
- Assist policy classification (risk grading)
- Surface fraud patterns and propose holds
- Operate in clean-room as constrained assistant

### 12.3 AI forbidden responsibilities
- Override consent or expand QueryPlan scope
- Enable narrow targeting or re-identification
- Change price/terms after acceptance
- Finalize DS payouts without deterministic checks

### 12.4 Privacy Risk Budget (PRB)
`PRB_remaining = PRB_allocated − Σ risk_cost(transform, sensitivity, export_mode, cohort)`

- Allocated at quote time
- Locked at acceptance
- Exports consume significantly more PRB

---

## 13. Threat Model & War Games

### 13.1 Adversary profiles
1) Malicious requester (re-identify, narrow-target, extract)
2) Careless requester (accidental risky cohort)
3) Fraud seller ring (device farms)
4) Compromised device (malware)
5) Insider threat
6) Competitor attacker (DDoS, poisoning)
7) Regulators/auditors (deplatforming risk)

### 13.2 Critical failure modes
| Mode | Controls | Kill switches |
|------|----------|--------------|
| Privacy failure | k-min, PRB, rate limits | Derived-only mode |
| Buyer misuse | KYB/DUA, clean-room, watermarking | Disable exports |
| Fraud collapse | Unit dedupe, caps, holdback | Freeze payouts |
| App store removal risk | Purpose-bound permissions | Disable sensitive features |
| Security breach | No payload in logs, key rotation | Quarantine + rotate keys |

### 13.3 War game scenarios (Top 20)
1) Narrow cohort attempt
2) Repeated-query linkage attack
3) Export bypass attempt
4) Chargeback storm
5) Device farm
6) Compromised phone exfil
7) Node lies about useful work
8) Ledger replication degraded
9) TTL expiry fails
10) Payload in logs
11) Minor in sensitive category
12) PSP high-risk flag
13) App store review challenge
14) Regional outage
15) Regulator inquiry
16) Re-ID attempt
17) Dispute abuse
18) Live event 100× spike
19) Cross-border constraints
20) Policy misclassification

---

## 14. Pricing & Economics

### 14.1 Uniform compensation model
- Same unit price for all DS in same request
- Local Currency Display (LCD) via platform FX reference

### 14.2 Unit types
| Type | Description |
|------|-------------|
| UNIT-PERSON | Pay once per DS identity |
| UNIT-DEVICE | Pay per verified distinct device |
| UNIT-LOCATION | Pay per verified sensor location |
| UNIT-TIME | Pay per minute/hour participation |
| UNIT-OBSERVATION | Pay per verified observation/event |

### 14.3 Escrow semantics
- Requester pre-funds before delivery
- Settlement atomic with delivery receipts
- Failed deliveries trigger automatic refund

---

## 15. Data Model

### 15.1 Core entities (control plane)
```typescript
interface DSProfile {
  id: UUID;
  pseudonym: string;
  status: 'active' | 'suspended' | 'banned';
  accountType: 'DS-IND' | 'DS-COMP' | 'DS-ORG';
}

interface RequesterProfile {
  id: UUID;
  tier: 'CR' | 'VO1' | 'VO2' | 'VO3';
  orgName: string;
  reputationScore: number;
  publicKeys: string[];
}

interface Request {
  id: UUID;
  requesterId: UUID;
  purpose: string;
  criteria: string; // ODX criteria language
  unitType: 'UNIT-PERSON'|'UNIT-DEVICE'|'UNIT-LOCATION'|'UNIT-TIME'|'UNIT-OBSERVATION';
  unitPrice: Decimal;
  maxParticipants: number;
  budget: Decimal;
  escrowId: UUID;
  riskClass: 'A'|'B'|'C';
  status: 'draft'|'screening'|'active'|'completed'|'cancelled';
}

interface ConsentContract {
  id: UUID;
  dsId: UUID;
  requesterId: UUID;
  scopeHash: string;
  purposeHash: string;
  durationStart: DateTime;
  durationEnd: DateTime;
  status: 'active'|'revoked'|'expired';
  compensationAmount: Decimal;
}

interface AuditReceipt {
  id: UUID;
  eventType: string;
  timestamp: DateTime;
  actorId: UUID;
  detailsHash: string;
  merkleProof?: any;
  previousReceiptHash: string;
}

interface EscrowRecord {
  id: UUID;
  requestId: UUID;
  amount: Decimal;
  status: 'prefunded'|'released'|'refunded'|'disputed';
  releaseConditions: string[];
}
```

### 15.2 Device-local entities (node)
```typescript
interface CanonicalEvent {
  eventId: UUID;
  source: string;
  recordType: string;
  tStart: DateTime;
  tEnd: DateTime;
  coarseGeoCell?: string;
  derivedFeatures: Record<string, any>;
  labels: string[];
  rawRef: string; // vault pointer
  ontologyVersion: string;
  schemaVersion: string;
}

interface ODXEntry {
  facetKey: string;
  timeBucket: string;
  geoBucket?: string;
  count: number;
  quality: 'framework'|'oauth'|'import';
  privacyFloor?: { kMin: number };
}

interface QueryPlan {
  planId: UUID;
  planVersion: string;
  steps: any[];
  limits: { maxRuntimeMs: number; maxEvents: number; maxOutputKb: number };
  requesterSignature: string;
}

interface TimeCapsule {
  id: UUID;
  requestId: UUID;
  consentContractId: UUID;
  fieldManifestHash: string;
  encryptedPayload: string;
  ttl: DateTime;
  status: 'created'|'delivered'|'expired'|'deleted';
}
```

---

## 16. Integration Requirements

| Integration | Purpose |
|------------|---------|
| OAuth providers | Apple/Google login |
| Payment gateway | Requester payments |
| Payout provider | DS payouts |
| Notification services | Email/SMS/push |
| KMS/HSM | Control-plane key management |
| SIEM | Security monitoring |
| Connectors | Health frameworks, OAuth services, export imports |
| P2P | WebRTC/libp2p transport with signaling |

---

## 17. Compliance Requirements

### 17.1 Design targets
| Regulation/Standard | Status |
|---------------------|--------|
| GDPR | Consent, portability, deletion, purpose limitation |
| CCPA | Access, deletion, sale/sharing transparency |
| COPPA | Under-13 parental consent; strict defaults |
| ISO 27001/27701 | Controls mapping; audit trails |

### 17.2 Data localization
Support regional storage options for control-plane data and receipts where required.

---

# Appendix A — ODX Ontology v1
(See: complete namespaces, sensitivity classes, and examples.)

**Namespaces:** `domain.*`, `time.*`, `geo.*`, `quality.*`, `privacy.*`, `device.*`  
**Rules:** no raw payload, no precise GPS by default, k-min enforcement for sensitive cohorts.

---

# Appendix B — ODX Criteria Language v1
A request eligibility language that references ODX facets only.

**Design goals:** safe, non-targeting, statically checkable.

**Core operators:**
- `HAS(label)`
- `COUNT(label, window) >= n`
- `IN_TIME(window)`
- `IN_GEO(zone)` (evaluated locally, zone IDs are hashed/policy-defined)
- `AND/OR/NOT` with bounded nesting

**Hard restrictions:**
- No exact address criteria.
- Sensitivity gating: health + minors + fine geo/time ⇒ forced downscope or reject.

---

# Appendix C — QueryPlan DSL v1
A constrained, deterministic plan executed locally.

**Allowlisted ops:** `SELECT`, `FILTER`, `PROJECT`, `BUCKETIZE`, `AGGREGATE`, `REDACT`, `EXPORT`, `PACK_CAPSULE`.

**Forbidden:** network ops, arbitrary code, raw payload export unless explicitly enabled by policy + DS.

---

# Appendix D — Clean-Room Output Schemas v1
Standard derived schemas to reduce risky custom exports.

1) `yachaq.weekly_habits.v1`
2) `yachaq.sleep_adherence.v1`
3) `yachaq.activity_summary.v1`
4) `yachaq.mobility_summary.v1`
5) `yachaq.media_listening_patterns.v1`

Each schema defines:
- required fields (aggregated/bucketed)
- sensitivity class
- default k-min
- default time/geo coarsening

---

# Appendix E — AuditReceipts & KIPUX Provenance

**AuditReceipt:** append-only events with hashes; batched into Merkle trees.

**KIPUX model:**
- **Knot:** a receipt node
- **Cord:** a thread of related receipts (e.g., a transaction)
- **Weave:** provenance graph across cords

**Required receipts (minimum):**
- RequestCreated, RequestStamped, RequestPublished
- DeviceMatched (local), ContractSigned (DS), ContractCountersigned (Requester)
- PlanValidated, PlanExecuted
- CapsuleCreated, CapsuleDelivered
- EscrowReleased, PayoutSettled
- TTLExpired, CryptoShredCommitted (where used)
- DeviceQuarantined (if applicable)

---

**END OF SRS**


# YACHAQ Platform - Architecture Decision Record

## Document Purpose

Complete technology architecture for YACHAQ, mapped to all 23 services and 52 correctness properties defined in design.md. Every choice is justified. MPC, ZKP, and confidential computing are first-class citizens.

**Version:** 2.0  
**Status:** APPROVED  
**Last Updated:** December 2025

---

## TABLE OF CONTENTS

1. [Architecture Philosophy](#1-architecture-philosophy)
2. [Service-to-Technology Mapping](#2-service-to-technology-mapping)
3. [Database Architecture](#3-database-architecture)
4. [Privacy & Cryptography (MPC, ZKP, HE)](#4-privacy--cryptography-layer)
5. [Core Platform Stack](#5-core-platform-stack)
6. [API & Communication](#6-api--communication)
7. [Security Infrastructure](#7-security-infrastructure)
8. [Blockchain Layer](#8-blockchain-layer)
9. [ML/AI Infrastructure](#9-mlai-infrastructure)
10. [Client Applications](#10-client-applications)
11. [Observability](#11-observability)
12. [Infrastructure](#12-infrastructure)
13. [Complete Architecture Diagram](#13-complete-architecture-diagram)
14. [Technology Summary](#14-technology-summary)

---

## 1. ARCHITECTURE PHILOSOPHY

### Core Principles

1. **Right Tool for Right Job** — Polyglot persistence, specialized engines
2. **Privacy by Cryptography** — MPC, ZKP, confidential computing where needed
3. **Scale from Day One** — Handle 10M+ users without redesign
4. **Event-Driven Core** — Immutable audit trail, loose coupling
5. **Zero Trust** — Every layer authenticated, encrypted, verified
6. **AI Handles Complexity** — No compromises for operational simplicity

### Design Constraints (from design.md)

- **Edge-First**: Data stays on user devices
- **Consent-First**: Every access requires explicit consent
- **Privacy by Architecture**: Operators cannot access raw user data
- **52 Correctness Properties** must be enforceable
- **23 Services** must be supported

---


## 2. SERVICE-TO-TECHNOLOGY MAPPING

### Complete Service Inventory

Based on design.md analysis, YACHAQ has 23 distinct services. Each mapped to optimal technology:

| # | Service | Primary Function | Database | Compute | Special Tech |
|---|---------|------------------|----------|---------|--------------|
| 1 | **DS Client Suite** | User app | SQLite+SQLCipher | React Native | - |
| 2 | **On-Device Data Store (ODS)** | Encrypted local DB | SQLite+SQLCipher | On-device | AES-256-GCM |
| 3 | **On-Device Label Index (ODX)** | Privacy-safe discovery | SQLite | On-device | Bloom filters |
| 4 | **Consent Engine** | Policy decisions | PostgreSQL | Spring Boot | OPA |
| 5 | **Query Orchestrator** | Live device queries | Kafka | Spring Boot | gRPC streaming |
| 6 | **Screening Engine** | Request safety | PostgreSQL | Spring Boot | DJL (NLP) |
| 7 | **Audit Receipt Ledger** | Immutable events | Kafka + PostgreSQL | Spring Boot | Merkle trees |
| 8 | **Financial Ledger** | Double-entry accounting | PostgreSQL | Spring Boot | - |
| 9 | **Device Attestation** | Device verification | PostgreSQL | Spring Boot | HSM |
| 10 | **Clean Room Delivery** | Secure data access | - | **Intel SGX/AMD SEV** | Confidential VM |
| 11 | **Model-Data Lineage** | ML provenance | **Neo4j** | Spring Boot | Graph queries |
| 12 | **Replay Protection** | Nonce registry | **Redis** | Spring Boot | TTL keys |
| 13 | **Privacy Governor** | PRB, k-min, linkage | PostgreSQL | Spring Boot | **MPC Engine** |
| 14 | **Requester Governance** | KYB, tiers | PostgreSQL | Spring Boot | - |
| 15 | **Verifier Service** | Proofs, quality | PostgreSQL | Spring Boot | **ZKP Verifier** |
| 16 | **Evidence Pack Generator** | Compliance bundles | PostgreSQL | Spring Boot | Merkle proofs |
| 17 | **Fraud & Risk Engine** | ML scoring | PostgreSQL | **Apache Flink** | Spark MLlib |
| 18 | **Safe Mode Controller** | Emergency toggles | Redis + PostgreSQL | Spring Boot | Feature flags |
| 19 | **KIPUX Graph Store** | Provenance graph | **Neo4j** | Spring Boot | Cypher queries |
| 20 | **DS Device Graph** | Device relationships | **Neo4j** | Spring Boot | Trust scoring |
| 21 | **IoT Division** | Sensor data | **TimescaleDB** | Spring Boot | Time-series |
| 22 | **Market Integrity** | Auctions | PostgreSQL + Redis | Spring Boot | Rate cards |
| 23 | **Clean-Room Hardening** | Anti-exfiltration | - | **Confidential Computing** | Network isolation |

---

## 3. DATABASE ARCHITECTURE

### Polyglot Persistence Strategy

YACHAQ uses **6 specialized databases** — each optimal for its data pattern:

### 3.1 PostgreSQL 16 — Transactional Core

| Attribute | Value |
|-----------|-------|
| **Version** | 16.x |
| **License** | PostgreSQL License (Free) |
| **Extensions** | pgcrypto, pg_stat_statements, pgaudit |

**Stores:**
- User profiles (DSProfile, RequesterTier)
- Consent contracts
- Requests and campaigns
- Escrow accounts
- Financial journal entries
- Policy decisions
- Device attestations

**Why PostgreSQL:**
- ACID transactions for financial data
- JSONB for flexible consent scope
- Row-Level Security for multi-tenancy
- Partitioning for 7-year audit retention
- 30 years battle-tested

**Schema Design:**
```sql
-- Multi-tenant with RLS
ALTER TABLE consent_contracts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON consent_contracts
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

-- Partitioned audit (by month)
CREATE TABLE audit_receipts_materialized (
    id UUID,
    created_at TIMESTAMPTZ,
    event_type TEXT,
    ...
) PARTITION BY RANGE (created_at);
```

---

### 3.2 Apache Kafka 3.7 — Event Sourcing

| Attribute | Value |
|-----------|-------|
| **Version** | 3.7.x |
| **License** | Apache 2.0 (Free) |
| **Schema** | Apache Avro |

**Stores:**
- Consent events (source of truth)
- Audit receipts (immutable log)
- Settlement events
- Device query dispatch
- Fraud signals

**Why Kafka:**
- Append-only immutable log
- Exactly-once semantics
- Infinite retention (7+ years)
- Event replay for debugging
- Decoupled microservices

**Topics:**
| Topic | Retention | Purpose |
|-------|-----------|---------|
| `consent.events` | Forever | Consent lifecycle |
| `audit.receipts` | Forever | All audit events |
| `settlement.events` | Forever | Financial settlements |
| `device.queries` | 24h | Live query dispatch |
| `fraud.signals` | 90d | Fraud detection |
| `screening.results` | 30d | Request screening |

---

### 3.3 Redis 7 (Valkey) — Hot Cache & Nonces

| Attribute | Value |
|-----------|-------|
| **Version** | 7.x (or Valkey fork) |
| **License** | BSD-3 / Apache 2.0 |
| **Cluster** | Yes |

**Stores:**
- Session tokens
- Consent cache (hot data)
- Nonce registry (replay protection)
- Rate limit counters
- Safe mode flags
- Auction state

**Why Redis:**
- Sub-millisecond latency
- TTL for automatic nonce expiration
- Lua scripting for atomic operations
- Pub/sub for real-time updates

**Key Patterns:**
```
nonce:{nonce_value} → {capsuleId, createdAt} TTL=capsule_ttl
session:{token} → {userId, scopes} TTL=15min
ratelimit:{requesterId}:{window} → count TTL=window
safemode:global → {flags} 
```

---

### 3.4 Neo4j 5 — Graph Database

| Attribute | Value |
|-----------|-------|
| **Version** | 5.x |
| **License** | GPL-3.0 (Community) / Commercial |
| **Query Language** | Cypher |

**Stores:**
- KIPUX provenance graph (knots, cords, weaves)
- Model-data lineage
- DS device graph
- Fraud relationship analysis

**Why Neo4j:**
- Best graph query language (Cypher)
- ACID transactions on graphs
- Native graph storage (not relational overlay)
- Best developer experience
- Proven at scale (eBay, NASA, Walmart)

**Graph Models:**

```cypher
// KIPUX Provenance
(ds:DataSovereign)-[:GRANTED]->(consent:Consent)
(consent)-[:AUTHORIZED]->(query:Query)
(query)-[:PRODUCED]->(capsule:Capsule)
(capsule)-[:SETTLED]->(payment:Payment)

// Device Graph
(ds:DataSovereign)-[:OWNS]->(device:Device)
(device)-[:ATTESTED_BY]->(attestation:Attestation)

// Model Lineage
(model:MLModel)-[:TRAINED_ON]->(dataset:Dataset)
(dataset)-[:CONTRIBUTED_BY]->(ds:DataSovereign)
```

**Why Not JanusGraph:**
- Neo4j has better DX and query performance
- Cypher >> Gremlin for readability
- Native ACID vs eventual consistency

---

### 3.5 TimescaleDB 2 — Time-Series

| Attribute | Value |
|-----------|-------|
| **Version** | 2.x |
| **License** | Apache 2.0 (Community) |
| **Base** | PostgreSQL extension |

**Stores:**
- IoT sensor data
- Platform metrics history
- User activity time-series
- Fraud velocity metrics

**Why TimescaleDB:**
- PostgreSQL extension (same stack)
- Automatic partitioning (hypertables)
- 90%+ compression
- SQL interface (no new query language)
- Continuous aggregates

**Schema:**
```sql
CREATE TABLE sensor_readings (
    time TIMESTAMPTZ NOT NULL,
    sensor_id UUID,
    fleet_id UUID,
    value DOUBLE PRECISION,
    quality_score DECIMAL
);
SELECT create_hypertable('sensor_readings', 'time');
```

---

### 3.6 SQLite + SQLCipher — Mobile/Edge

| Attribute | Value |
|-----------|-------|
| **Version** | SQLite 3.x + SQLCipher |
| **License** | Public Domain / BSD |
| **Location** | On-device only |

**Stores:**
- On-Device Data Store (ODS)
- On-Device Label Index (ODX)
- Offline consent cache
- Local audit receipts

**Why SQLite:**
- Zero-config, serverless
- Proven on billions of devices
- SQLCipher: AES-256 encryption at rest
- Works offline

---

### 3.7 MinIO — Object Storage

| Attribute | Value |
|-----------|-------|
| **Version** | Latest |
| **License** | AGPL-3.0 (Free) |
| **API** | S3-compatible |

**Stores:**
- Time capsule encrypted payloads
- Evidence pack bundles
- ML model artifacts
- Audit exports

**Why MinIO:**
- S3-compatible (no vendor lock-in)
- On-prem or cloud
- Encryption at rest
- Versioning for audit

---


## 4. PRIVACY & CRYPTOGRAPHY LAYER

This is the **critical differentiator** for YACHAQ. Privacy is enforced by cryptography, not policy.

### 4.1 Multi-Party Computation (MPC)

| Attribute | Value |
|-----------|-------|
| **Library** | MP-SPDZ / SCALE-MAMBA |
| **Protocol** | SPDZ (Malicious security) |
| **License** | BSD / Apache 2.0 |

**Use Cases in YACHAQ:**

| Use Case | MPC Application | Why MPC |
|----------|-----------------|---------|
| **Privacy-Preserving Matching** | Compute eligibility without revealing DS attributes | Requester learns "DS matches" not "DS age=25" |
| **Secure Aggregation** | Compute statistics across DS data | Platform never sees individual values |
| **Threshold Decryption** | Decrypt capsules only with quorum | No single party can access data |
| **Private Set Intersection** | Match DS to request criteria | Neither party reveals full set |

**Architecture:**
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  MPC Node 1 │     │  MPC Node 2 │     │  MPC Node 3 │
│  (Platform) │     │  (Auditor)  │     │  (DS Rep)   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                    ┌──────┴──────┐
                    │ MPC Result  │
                    │ (Aggregate) │
                    └─────────────┘
```

**Implementation:**
```java
// Privacy Governor calls MPC for k-anonymity check
public class PrivacyGovernor {
    
    private final MPCClient mpcClient;
    
    public boolean checkKAnonymity(EligibilityCriteria criteria, int kMin) {
        // MPC computes cohort size without revealing individual DS
        SecureInteger cohortSize = mpcClient.secureCount(
            criteria.toSecureQuery()
        );
        return cohortSize.reveal() >= kMin;
    }
    
    public SecureAggregate computeAggregate(String field, AggregateType type) {
        // MPC computes aggregate without revealing individual values
        return mpcClient.secureAggregate(field, type);
    }
}
```

---

### 4.2 Zero-Knowledge Proofs (ZKP)

| Attribute | Value |
|-----------|-------|
| **Framework** | Circom + SnarkJS (browser) / Bellman (Rust) |
| **Proof System** | Groth16 / PLONK |
| **License** | Apache 2.0 / MIT |

**Use Cases in YACHAQ:**

| Use Case | ZKP Application | What's Proven |
|----------|-----------------|---------------|
| **Attribute Verification** | Prove age > 18 | "I'm adult" without revealing birthdate |
| **Income Bracket** | Prove income in range | "Income $50K-100K" without exact amount |
| **Location Proof** | Prove in country | "I'm in target region" without GPS coordinates |
| **Consent Validity** | Prove consent exists | "Valid consent exists" without revealing terms |

**Circuit Example (Circom):**
```circom
template AgeOver18() {
    signal input birthYear;
    signal input birthMonth;
    signal input birthDay;
    signal input currentYear;
    signal input currentMonth;
    signal input currentDay;
    signal output isAdult;
    
    // Compute age and prove >= 18
    // Without revealing exact birthdate
}
```

**Integration:**
```java
public class VerifierService {
    
    private final ZKPVerifier zkpVerifier;
    
    public boolean verifyAttributeProof(
        String proofType, 
        byte[] proof, 
        byte[] publicInputs) {
        
        return zkpVerifier.verify(proofType, proof, publicInputs);
    }
}
```

---

### 4.3 Homomorphic Encryption (HE)

| Attribute | Value |
|-----------|-------|
| **Library** | Microsoft SEAL / OpenFHE |
| **Scheme** | BFV (integers) / CKKS (floats) |
| **License** | MIT |

**Use Cases in YACHAQ:**

| Use Case | HE Application |
|----------|----------------|
| **Encrypted Search** | Search ODX without decrypting |
| **Secure Scoring** | Compute fraud score on encrypted data |
| **Private Analytics** | Aggregate encrypted values |

**Note:** HE is computationally expensive. Use selectively for high-value operations.

---

### 4.4 Confidential Computing

| Attribute | Value |
|-----------|-------|
| **Technology** | Intel SGX / AMD SEV / ARM TrustZone |
| **Cloud Support** | Azure Confidential Computing, GCP Confidential VMs |

**Use Cases in YACHAQ:**

| Service | Confidential Computing Use |
|---------|---------------------------|
| **Clean Room Delivery** | Process data in encrypted memory |
| **Clean-Room Hardening** | Prevent data exfiltration |
| **Key Management** | HSM-backed key operations |

**Architecture:**
```
┌─────────────────────────────────────────┐
│         Confidential VM (SGX/SEV)       │
│  ┌───────────────────────────────────┐  │
│  │     Encrypted Memory (Enclave)    │  │
│  │  ┌─────────────────────────────┐  │  │
│  │  │   Clean Room Application    │  │  │
│  │  │   - Decrypt capsule         │  │  │
│  │  │   - Process query           │  │  │
│  │  │   - Return result only      │  │  │
│  │  └─────────────────────────────┘  │  │
│  └───────────────────────────────────┘  │
│  Platform cannot read enclave memory    │
└─────────────────────────────────────────┘
```

---

### 4.5 Threshold Cryptography

| Attribute | Value |
|-----------|-------|
| **Library** | threshold-bls / frost-dalek |
| **Scheme** | Shamir Secret Sharing + BLS |

**Use Cases:**
- **Distributed Key Generation (DKG)** — No single party holds full key
- **Threshold Signatures** — Require k-of-n parties to sign
- **Escrow Release** — Multi-party approval for fund release

**Implementation:**
```java
public class ThresholdKeyManager {
    
    // 3-of-5 threshold for capsule decryption
    private static final int THRESHOLD = 3;
    private static final int TOTAL_SHARES = 5;
    
    public byte[] combineShares(List<KeyShare> shares) {
        if (shares.size() < THRESHOLD) {
            throw new InsufficientSharesException();
        }
        return shamirCombine(shares);
    }
}
```

---

### 4.6 Cryptography Summary

| Technique | Library | Use Case | Performance |
|-----------|---------|----------|-------------|
| **MPC** | MP-SPDZ | Secure matching, aggregation | Seconds |
| **ZKP** | Circom/SnarkJS | Attribute proofs | Milliseconds (verify) |
| **HE** | SEAL/OpenFHE | Encrypted computation | Slow (selective use) |
| **Confidential Computing** | SGX/SEV | Clean room | Native speed |
| **Threshold Crypto** | frost-dalek | Distributed keys | Milliseconds |

---


## 5. CORE PLATFORM STACK

### 5.1 Language: Java 21 LTS

| Attribute | Value |
|-----------|-------|
| **Version** | 21 LTS (Eclipse Temurin) |
| **License** | GPL v2 with Classpath Exception |

**Why Java 21:**
- **Virtual Threads**: Handle 100K+ concurrent device connections
- **Pattern Matching**: Clean consent state machines
- **Records**: Immutable audit receipts
- **Sealed Classes**: Compiler-enforced consent states
- **LTS until 2031**: Matches 7-year audit retention

---

### 5.2 Framework: Spring Boot 3.4.x

| Attribute | Value |
|-----------|-------|
| **Version** | 3.4.x |
| **License** | Apache 2.0 |

**Why Spring Boot:**
- Native integration with all Apache projects
- Production-ready actuators
- GraalVM native compilation support
- Massive ecosystem

---

### 5.3 Build: Apache Maven 3.9.x

| Attribute | Value |
|-----------|-------|
| **Version** | 3.9.x |
| **License** | Apache 2.0 |

**Project Structure:**
```
yachaq-platform/
├── yachaq-core/              # Domain models, interfaces
├── yachaq-consent/           # Consent Engine
├── yachaq-audit/             # Audit Receipt Ledger
├── yachaq-financial/         # Financial Ledger
├── yachaq-query/             # Query Orchestrator
├── yachaq-screening/         # Screening Engine
├── yachaq-privacy/           # Privacy Governor + MPC
├── yachaq-verification/      # Verifier Service + ZKP
├── yachaq-fraud/             # Fraud & Risk Engine
├── yachaq-graph/             # KIPUX + Device Graph (Neo4j)
├── yachaq-cleanroom/         # Clean Room Services
├── yachaq-market/            # Market Integrity
├── yachaq-iot/               # IoT Division
├── yachaq-blockchain/        # Smart Contracts
├── yachaq-api/               # REST/GraphQL APIs
├── yachaq-gateway/           # API Gateway config
└── yachaq-mobile-sdk/        # React Native bridge
```

---

## 6. API & COMMUNICATION

### 6.1 External API: REST + GraphQL

| Component | Technology | Purpose |
|-----------|------------|---------|
| **REST** | Spring Web MVC | CRUD operations, webhooks |
| **GraphQL** | Spring for GraphQL | Flexible queries, subscriptions |
| **Documentation** | SpringDoc OpenAPI | Auto-generated docs |

---

### 6.2 Internal Communication: gRPC + Kafka

| Pattern | Technology | Use Case |
|---------|------------|----------|
| **Sync RPC** | gRPC | Service-to-service calls |
| **Async Events** | Apache Kafka | Event-driven communication |
| **Streaming** | gRPC Streaming | Live device queries |

**Why gRPC internally:**
- Binary protocol (10x faster than JSON)
- Strong typing with Protobuf
- Bidirectional streaming for device queries
- Native load balancing

---

### 6.3 API Gateway: Apache APISIX

| Attribute | Value |
|-----------|-------|
| **Version** | 3.9.x |
| **License** | Apache 2.0 |

**Features Used:**
- Rate limiting (per requester, per DS)
- JWT validation (Keycloak integration)
- Request routing
- Circuit breaker
- Prometheus metrics

---

## 7. SECURITY INFRASTRUCTURE

### 7.1 Identity: Keycloak

| Attribute | Value |
|-----------|-------|
| **Version** | 24.x |
| **License** | Apache 2.0 |

**Features:**
- OAuth2/OIDC (Google, Apple, Facebook)
- Multi-tenant realms
- Fine-grained authorization
- MFA support

---

### 7.2 Secrets: HashiCorp Vault

| Attribute | Value |
|-----------|-------|
| **Version** | 1.15.x |
| **License** | BSL 1.1 |

**Use Cases:**
- Database credentials (dynamic)
- API keys (Payment providers)
- Encryption keys (Transit engine)
- PKI certificates

---

### 7.3 Policy Engine: Open Policy Agent (OPA)

| Attribute | Value |
|-----------|-------|
| **Version** | 0.62.x |
| **License** | Apache 2.0 |

**Policies:**
- Consent evaluation
- Field-level access control
- Transform restrictions
- k-anonymity enforcement

---

### 7.4 HSM: Hardware Security Module

| Attribute | Value |
|-----------|-------|
| **Options** | AWS CloudHSM / Azure Dedicated HSM / Thales Luna |

**Use Cases:**
- Blockchain signing keys
- Root encryption keys
- Attestation verification

---

## 8. BLOCKCHAIN LAYER

### 8.1 Network: Polygon (EVM)

| Attribute | Value |
|-----------|-------|
| **Network** | Polygon PoS |
| **Why** | Low gas fees, EVM compatible, fast finality |

---

### 8.2 Smart Contracts

| Contract | Purpose | Key Functions |
|----------|---------|---------------|
| **YachaqEscrow** | Trustless fund locking | deposit, lock, release, refund |
| **ConsentRegistry** | On-chain consent proofs | register, revoke, verify |
| **AuditAnchor** | Merkle root anchoring | anchor, verifyProof |
| **Governance** | Multi-sig upgrades | propose, vote, execute |

---

### 8.3 Client: Web3j

| Attribute | Value |
|-----------|-------|
| **Version** | 4.11.x |
| **License** | Apache 2.0 |

---

## 9. ML/AI INFRASTRUCTURE

### 9.1 Training: Apache Spark MLlib

| Attribute | Value |
|-----------|-------|
| **Version** | 3.5.x |
| **License** | Apache 2.0 |

**Models:**
- Fraud detection
- Request matching
- Value estimation

---

### 9.2 Inference: DJL (Deep Java Library)

| Attribute | Value |
|-----------|-------|
| **Version** | 0.27.x |
| **License** | Apache 2.0 |

**Models:**
- NLP screening (DistilBERT)
- Embeddings (Sentence-BERT)

---

### 9.3 Stream Processing: Apache Flink

| Attribute | Value |
|-----------|-------|
| **Version** | 1.19.x |
| **License** | Apache 2.0 |

**Use Cases:**
- Real-time fraud detection
- Velocity monitoring
- Anomaly detection

---

### 9.4 Feature Store: Apache Hudi

| Attribute | Value |
|-----------|-------|
| **Version** | 0.14.x |
| **License** | Apache 2.0 |

---

### 9.5 Pipeline: Apache Airflow

| Attribute | Value |
|-----------|-------|
| **Version** | 2.8.x |
| **License** | Apache 2.0 |

---


## 10. CLIENT APPLICATIONS

### 10.1 DS Mobile App

| Attribute | Value |
|-----------|-------|
| **Framework** | React Native 0.73.x |
| **Language** | TypeScript 5.x |
| **State** | Zustand |
| **Storage** | MMKV + SQLite/SQLCipher |
| **Navigation** | React Navigation 6.x |

**Key Libraries:**
- `react-native-mmkv` — Fast encrypted storage
- `react-native-quick-sqlite` — SQLCipher integration
- `react-native-keychain` — Secure credential storage
- `react-native-biometrics` — Face ID / fingerprint

---

### 10.2 Web Portals (DS, Requester, Admin)

| Attribute | Value |
|-----------|-------|
| **Framework** | React 18.x |
| **Build** | Vite 5.x |
| **Language** | TypeScript 5.x |
| **UI** | Shadcn/ui + Tailwind CSS |
| **State** | Zustand |
| **Forms** | React Hook Form + Zod |
| **Data Fetching** | TanStack Query |
| **i18n** | react-i18next |

**Portals:**
| Portal | URL | Purpose |
|--------|-----|---------|
| DS Portal | app.yachaq.com | Data sovereign dashboard |
| Requester Portal | business.yachaq.com | Campaign management |
| Admin Console | admin.yachaq.com | Platform operations |

---

## 11. OBSERVABILITY

### 11.1 Metrics: Micrometer + Prometheus

| Attribute | Value |
|-----------|-------|
| **Collection** | Micrometer 1.12.x |
| **Storage** | Prometheus 2.50.x |
| **Visualization** | Grafana 10.x |

---

### 11.2 Tracing: Apache SkyWalking

| Attribute | Value |
|-----------|-------|
| **Version** | 9.7.x |
| **License** | Apache 2.0 |

**Why SkyWalking:**
- Full APM (traces + metrics + logs)
- Java agent (zero code changes)
- Service topology visualization

---

### 11.3 Logging: Log4j2 + OpenSearch

| Attribute | Value |
|-----------|-------|
| **Framework** | Apache Log4j2 2.23.x |
| **Aggregation** | OpenSearch 2.x |
| **Visualization** | OpenSearch Dashboards |

---

## 12. INFRASTRUCTURE

### 12.1 Containers: Docker

| Attribute | Value |
|-----------|-------|
| **Version** | 25.x |
| **Registry** | Harbor (self-hosted) or ECR/GCR |

---

### 12.2 Orchestration: Kubernetes

| Attribute | Value |
|-----------|-------|
| **Version** | 1.29.x |
| **Distribution** | EKS / GKE / AKS |

---

### 12.3 IaC: Terraform

| Attribute | Value |
|-----------|-------|
| **Version** | 1.7.x |

---

### 12.4 CI/CD: GitHub Actions + ArgoCD

| Stage | Tool |
|-------|------|
| Build & Test | GitHub Actions |
| Security Scan | Trivy, Snyk |
| Deploy | ArgoCD (GitOps) |

---

### 12.5 Development Port Mapping

All development ports use the **55000+ range** to avoid conflicts with local services.

| Service | Port | Internal Port | Description |
|---------|------|---------------|-------------|
| **API Server** | 55080 | 8080 | Main REST/GraphQL API |
| **PostgreSQL** | 55432 | 5432 | Primary transactional database |
| **Redis** | 55379 | 6379 | Cache, sessions, nonce registry |
| **Kafka** | 55092 | 9092 | Event streaming |
| **Neo4j HTTP** | 55474 | 7474 | Graph database browser |
| **Neo4j Bolt** | 55687 | 7687 | Graph database protocol |
| **TimescaleDB** | 55433 | 5432 | IoT time-series (if separate) |
| **OpenSearch** | 55200 | 9200 | Full-text search |
| **MinIO** | 55000 | 9000 | Object storage |
| **MinIO Console** | 55001 | 9001 | MinIO admin UI |
| **Keycloak** | 55081 | 8080 | Identity provider |
| **Vault** | 55200 | 8200 | Secrets management |
| **Prometheus** | 55090 | 9090 | Metrics collection |
| **Grafana** | 55030 | 3000 | Monitoring dashboards |
| **Jaeger** | 55686 | 16686 | Distributed tracing UI |

**Connection Strings (Development):**
```bash
# PostgreSQL
jdbc:postgresql://localhost:55432/yachaq

# Redis
redis://localhost:55379

# Kafka
localhost:55092

# Neo4j
bolt://localhost:55687
```

---

## 13. COMPLETE ARCHITECTURE DIAGRAM

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                      CLIENTS                                             │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐                    │
│  │    DS Mobile      │  │    DS Web         │  │  Requester Portal │                    │
│  │  (React Native)   │  │    (React)        │  │     (React)       │                    │
│  │  SQLite+SQLCipher │  │                   │  │                   │                    │
│  └─────────┬─────────┘  └─────────┬─────────┘  └─────────┬─────────┘                    │
│            └──────────────────────┼──────────────────────┘                              │
│                                   ▼                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐   │
│  │                         API GATEWAY (Apache APISIX)                              │   │
│  │            Rate Limiting │ JWT Auth │ Routing │ Circuit Breaker                  │   │
│  └─────────────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                        │
┌───────────────────────────────────────┼───────────────────────────────────────┐
│                              SECURITY LAYER                                    │
├───────────────────────────────────────┼───────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │   Keycloak   │  │    Vault     │  │     OPA      │  │     HSM      │       │
│  │  OAuth/OIDC  │  │   Secrets    │  │   Policies   │  │    Keys      │       │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘       │
└───────────────────────────────────────┼───────────────────────────────────────┘
                                        │
┌───────────────────────────────────────┼───────────────────────────────────────┐
│                         SPRING BOOT MICROSERVICES                              │
├───────────────────────────────────────┼───────────────────────────────────────┤
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐   │
│  │  Consent   │ │   Query    │ │ Settlement │ │ Screening  │ │   Audit    │   │
│  │  Engine    │ │Orchestrator│ │  Service   │ │  Engine    │ │  Service   │   │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘ └────────────┘   │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐   │
│  │  Privacy   │ │  Verifier  │ │   Fraud    │ │   Market   │ │    IoT     │   │
│  │  Governor  │ │  Service   │ │   Engine   │ │ Integrity  │ │  Division  │   │
│  │  + MPC     │ │  + ZKP     │ │  + Flink   │ │            │ │            │   │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘ └────────────┘   │
└───────────────────────────────────────┼───────────────────────────────────────┘
                                        │
┌───────────────────────────────────────┼───────────────────────────────────────┐
│                         PRIVACY & CRYPTOGRAPHY                                 │
├───────────────────────────────────────┼───────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │  MPC Engine  │  │ ZKP Verifier │  │  Threshold   │  │ Confidential │       │
│  │  (MP-SPDZ)   │  │   (Circom)   │  │   Crypto     │  │  Computing   │       │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘       │
└───────────────────────────────────────┼───────────────────────────────────────┘
                                        │
┌───────────────────────────────────────┼───────────────────────────────────────┐
│                              DATA LAYER                                        │
├───────────────────────────────────────┼───────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ PostgreSQL   │  │ Apache Kafka │  │    Redis     │  │    Neo4j     │       │
│  │ Transactions │  │   Events     │  │    Cache     │  │    Graph     │       │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                         │
│  │ TimescaleDB  │  │ OpenSearch   │  │    MinIO     │                         │
│  │ Time-Series  │  │   Search     │  │   Objects    │                         │
│  └──────────────┘  └──────────────┘  └──────────────┘                         │
└───────────────────────────────────────┼───────────────────────────────────────┘
                                        │
┌───────────────────────────────────────┼───────────────────────────────────────┐
│                            BLOCKCHAIN LAYER                                    │
├───────────────────────────────────────┼───────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                         │
│  │   Escrow     │  │   Consent    │  │    Audit     │                         │
│  │  Contract    │  │   Registry   │  │   Anchor     │                         │
│  └──────────────┘  └──────────────┘  └──────────────┘                         │
│                           │                                                    │
│                    ┌──────┴──────┐                                             │
│                    │   Polygon   │                                             │
│                    │    (EVM)    │                                             │
│                    └─────────────┘                                             │
└───────────────────────────────────────────────────────────────────────────────┘
                                        │
┌───────────────────────────────────────┼───────────────────────────────────────┐
│                              ML/AI LAYER                                       │
├───────────────────────────────────────┼───────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ Spark MLlib  │  │     DJL      │  │ Apache Flink │  │ Apache Hudi  │       │
│  │  Training    │  │  Inference   │  │   Streaming  │  │   Features   │       │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘       │
└───────────────────────────────────────────────────────────────────────────────┘
                                        │
┌───────────────────────────────────────┼───────────────────────────────────────┐
│                            OBSERVABILITY                                       │
├───────────────────────────────────────┼───────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │  Prometheus  │  │  SkyWalking  │  │   Log4j2 +   │  │   Grafana    │       │
│  │   Metrics    │  │   Tracing    │  │  OpenSearch  │  │  Dashboards  │       │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘       │
└───────────────────────────────────────────────────────────────────────────────┘
```

---


## 14. TECHNOLOGY SUMMARY

### Complete Technology Stack

| Category | Technology | Version | License | Purpose |
|----------|------------|---------|---------|---------|
| **Language** | Java | 21 LTS | GPL v2 | Core platform |
| **Framework** | Spring Boot | 3.4.x | Apache 2.0 | Application framework |
| **Build** | Apache Maven | 3.9.x | Apache 2.0 | Build automation |
| **DB - Transactional** | PostgreSQL | 16.x | PostgreSQL | ACID transactions |
| **DB - Events** | Apache Kafka | 3.7.x | Apache 2.0 | Event sourcing |
| **DB - Cache** | Redis (Valkey) | 7.x | BSD-3 | Hot cache, nonces |
| **DB - Graph** | Neo4j | 5.x | GPL-3.0 | KIPUX, lineage |
| **DB - Time-Series** | TimescaleDB | 2.x | Apache 2.0 | IoT, metrics |
| **DB - Search** | OpenSearch | 2.x | Apache 2.0 | Full-text search |
| **DB - Objects** | MinIO | Latest | AGPL-3.0 | Blob storage |
| **DB - Mobile** | SQLite + SQLCipher | 3.x | Public Domain | On-device |
| **MPC** | MP-SPDZ | Latest | BSD | Secure computation |
| **ZKP** | Circom + SnarkJS | Latest | Apache 2.0 | Zero-knowledge proofs |
| **Confidential** | Intel SGX / AMD SEV | - | - | Secure enclaves |
| **Identity** | Keycloak | 24.x | Apache 2.0 | OAuth/OIDC |
| **Gateway** | Apache APISIX | 3.9.x | Apache 2.0 | API gateway |
| **Secrets** | HashiCorp Vault | 1.15.x | BSL 1.1 | Secrets management |
| **Policy** | OPA | 0.62.x | Apache 2.0 | Policy engine |
| **Blockchain** | Polygon + Web3j | 4.11.x | Apache 2.0 | Smart contracts |
| **ML Training** | Apache Spark MLlib | 3.5.x | Apache 2.0 | Model training |
| **ML Inference** | DJL | 0.27.x | Apache 2.0 | NLP inference |
| **Stream** | Apache Flink | 1.19.x | Apache 2.0 | Real-time processing |
| **Features** | Apache Hudi | 0.14.x | Apache 2.0 | Feature store |
| **Pipeline** | Apache Airflow | 2.8.x | Apache 2.0 | ML pipelines |
| **Web UI** | React | 18.x | MIT | Web portals |
| **Mobile** | React Native | 0.73.x | MIT | DS mobile app |
| **Metrics** | Prometheus | 2.50.x | Apache 2.0 | Metrics |
| **Tracing** | Apache SkyWalking | 9.7.x | Apache 2.0 | Distributed tracing |
| **Logging** | Apache Log4j2 | 2.23.x | Apache 2.0 | Logging |
| **Dashboards** | Grafana | 10.x | AGPL-3.0 | Visualization |
| **Containers** | Docker | 25.x | Apache 2.0 | Containerization |
| **Orchestration** | Kubernetes | 1.29.x | Apache 2.0 | Container orchestration |
| **IaC** | Terraform | 1.7.x | BSL 1.1 | Infrastructure as code |
| **CI/CD** | GitHub Actions + ArgoCD | - | Free | Continuous deployment |

---

### Database Summary

| Database | Data Stored | Why This One |
|----------|-------------|--------------|
| **PostgreSQL** | Users, consents, requests, escrow, financial | ACID, JSONB, RLS, partitioning |
| **Kafka** | Events (consent, audit, settlement) | Immutable log, exactly-once |
| **Redis** | Sessions, nonces, rate limits, cache | Sub-ms latency, TTL |
| **Neo4j** | KIPUX graph, lineage, device graph | Best Cypher queries, ACID |
| **TimescaleDB** | IoT sensors, metrics history | Time-series optimized |
| **OpenSearch** | Full-text search, audit search | ML integration, Apache 2.0 |
| **MinIO** | Capsules, evidence packs, models | S3-compatible, encrypted |
| **SQLite** | On-device data (ODS, ODX) | Offline-first, encrypted |

---

### Privacy Technology Summary

| Technology | Use Case | When to Use |
|------------|----------|-------------|
| **MPC (MP-SPDZ)** | Secure matching, aggregation | Multiple parties, no trusted third party |
| **ZKP (Circom)** | Attribute proofs | Prove property without revealing data |
| **Confidential Computing** | Clean room processing | Process sensitive data securely |
| **Threshold Crypto** | Distributed key management | No single point of compromise |
| **Homomorphic Encryption** | Encrypted computation | Selective high-value operations |

---

### Service-to-Database Mapping

| Service | Primary DB | Secondary DB | Cache |
|---------|------------|--------------|-------|
| Consent Engine | PostgreSQL | Kafka | Redis |
| Query Orchestrator | Kafka | PostgreSQL | Redis |
| Audit Ledger | Kafka | PostgreSQL | - |
| Financial Ledger | PostgreSQL | Kafka | - |
| Privacy Governor | PostgreSQL | - | Redis |
| KIPUX Graph | Neo4j | PostgreSQL | - |
| Device Graph | Neo4j | PostgreSQL | Redis |
| Fraud Engine | PostgreSQL | Kafka | Redis |
| IoT Division | TimescaleDB | Kafka | - |
| Search | OpenSearch | PostgreSQL | Redis |
| Clean Room | MinIO | - | - |

---

## 15. CORRECTNESS PROPERTY COVERAGE

All 52 correctness properties from design.md are supported by this architecture:

| Property Range | Technology Support |
|----------------|-------------------|
| 1-10 (Consent, Escrow, Audit) | PostgreSQL + Kafka + OPA |
| 11-20 (Privacy, TTL, Replay) | Redis + MPC + Confidential Computing |
| 21-30 (Lineage, Device, Payout) | Neo4j + PostgreSQL |
| 31-40 (Evidence, Merkle, Graph) | Kafka + Neo4j + Blockchain |
| 41-52 (Device, License, Tenant) | PostgreSQL + Neo4j + OPA |

---

## 16. COST ESTIMATE

### Software Licenses

| Category | Cost |
|----------|------|
| All open-source software | **$0** |
| Neo4j Community Edition | **$0** |
| Commercial Neo4j (if needed) | ~$36K/year |

### Infrastructure (Cloud)

| Component | Monthly Cost |
|-----------|--------------|
| Kubernetes cluster (3 nodes) | $300-600 |
| PostgreSQL (managed) | $100-300 |
| Kafka (managed) | $200-500 |
| Redis (managed) | $50-150 |
| Neo4j (self-hosted) | $0 (included in K8s) |
| Object storage | $50-100 |
| **Total** | **$700-1,650/month** |

---

**Document End**

*This architecture is the authoritative source for all technology decisions in YACHAQ. It maps to all 23 services and 52 correctness properties defined in design.md.*

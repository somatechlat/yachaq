# YACHAQ Platform - Architecture Diagrams

## Document Purpose

Comprehensive visual documentation of the YACHAQ Platform architecture using Mermaid diagrams. All diagrams are renderable in GitHub, GitLab, VS Code, and most modern markdown viewers.

**Version:** 1.0  
**Last Updated:** December 2025

---

## TABLE OF CONTENTS

1. [System Context](#1-system-context)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Data Flow Diagrams](#3-data-flow-diagrams)
4. [Service Architecture](#4-service-architecture)
5. [Database Architecture](#5-database-architecture)
6. [Security Architecture](#6-security-architecture)
7. [Sequence Diagrams](#7-sequence-diagrams)
8. [Deployment Architecture](#8-deployment-architecture)

---

## 1. SYSTEM CONTEXT

### 1.1 C4 Context Diagram

```mermaid
graph TB
    subgraph "External Systems"
        GOOGLE[Google OAuth]
        APPLE[Apple Sign-In]
        PAYMENTS[Payment Gateway]
        PAYOUTS[Payout Provider]
        POLYGON[Polygon Blockchain]
    end

    subgraph "YACHAQ Platform"
        PLATFORM[YACHAQ Core Platform]
    end
    
    subgraph "Users"
        DS[Data Sovereigns]
        REQ[Requesters]
        ADMIN[Platform Admins]
    end
    
    DS -->|Mobile App| PLATFORM
    REQ -->|Web Portal| PLATFORM
    ADMIN -->|Admin Console| PLATFORM
    
    PLATFORM -->|OAuth| GOOGLE
    PLATFORM -->|OAuth| APPLE
    PLATFORM -->|Card Payments| PAYMENTS
    PLATFORM -->|Disbursements| PAYOUTS
    PLATFORM -->|Anchoring| POLYGON
```

### 1.2 Actor Relationships

```mermaid
graph LR
    subgraph "Data Sovereigns"
        DS_IND[DS-IND: Individual]
        DS_COMP[DS-COMP: Company]
        DS_ORG[DS-ORG: Organization]
    end
    
    subgraph "Requesters"
        RQ_COM[RQ-COM: Commercial]
        RQ_AR[RQ-AR: Academic]
        RQ_NGO[RQ-NGO: Non-Profit]
    end
    
    subgraph "Platform"
        CONSENT[Consent Engine]
        MATCH[Matching Engine]
        SETTLE[Settlement]
    end
    
    DS_IND --> CONSENT
    DS_COMP --> CONSENT
    DS_ORG --> CONSENT
    
    RQ_COM --> MATCH
    RQ_AR --> MATCH
    RQ_NGO --> MATCH
    
    CONSENT <--> MATCH
    MATCH --> SETTLE
```

---

## 2. HIGH-LEVEL ARCHITECTURE

### 2.1 Platform Layers

```mermaid
graph TB
    subgraph "Client Layer"
        MOBILE[React Native Mobile]
        WEB_DS[DS Web Portal]
        WEB_REQ[Requester Portal]
        WEB_ADMIN[Admin Console]
    end
    
    subgraph "Gateway Layer"
        APISIX[Apache APISIX]
        KEYCLOAK[Keycloak IAM]
    end
    
    subgraph "Service Layer"
        CONSENT[Consent Engine]
        QUERY[Query Orchestrator]
        SCREEN[Screening Engine]
        SETTLE[Settlement Service]
        AUDIT[Audit Service]
        PRIVACY[Privacy Governor]
    end
    
    subgraph "Data Layer"
        PG[(PostgreSQL)]
        KAFKA[(Kafka)]
        REDIS[(Redis)]
        NEO4J[(Neo4j)]
    end
    
    subgraph "External Layer"
        BC[Polygon Blockchain]
        PAY[Payment Providers]
    end
    
    MOBILE --> APISIX
    WEB_DS --> APISIX
    WEB_REQ --> APISIX
    WEB_ADMIN --> APISIX
    
    APISIX --> KEYCLOAK
    APISIX --> CONSENT
    APISIX --> QUERY
    APISIX --> SCREEN
    
    CONSENT --> PG
    CONSENT --> KAFKA
    QUERY --> KAFKA
    QUERY --> REDIS
    AUDIT --> KAFKA
    AUDIT --> NEO4J
    SETTLE --> PG
    SETTLE --> PAY
    AUDIT --> BC
```

### 2.2 Trust Boundaries

```mermaid
graph TB
    subgraph "Trust Boundary: User Device"
        style TB1 fill:#e1f5fe
        ODS[On-Device Store<br/>SQLite+SQLCipher]
        ODX[Label Index]
        SDK[YACHAQ SDK]
    end
    
    subgraph "Trust Boundary: Platform DMZ"
        style TB2 fill:#fff3e0
        GW[API Gateway]
        WAF[Web Application Firewall]
    end
    
    subgraph "Trust Boundary: Platform Core"
        style TB3 fill:#e8f5e9
        SERVICES[Microservices]
        MPC[MPC Engine]
        ZKP[ZKP Verifier]
    end
    
    subgraph "Trust Boundary: Data"
        style TB4 fill:#fce4ec
        DB[(Databases)]
        VAULT[HashiCorp Vault]
        HSM[HSM]
    end
    
    subgraph "Trust Boundary: External"
        style TB5 fill:#f3e5f5
        CHAIN[Blockchain]
        PAYMENTS[Payment Rails]
    end
    
    SDK --> GW
    GW --> SERVICES
    SERVICES --> DB
    SERVICES --> MPC
    SERVICES --> ZKP
    DB --> VAULT
    VAULT --> HSM
    SERVICES --> CHAIN
    SERVICES --> PAYMENTS
```

---

## 3. DATA FLOW DIAGRAMS

### 3.1 Consent Grant Flow

```mermaid
flowchart LR
    DS[Data Sovereign] -->|1. View Request| APP[Mobile App]
    APP -->|2. Accept| API[API Gateway]
    API -->|3. Validate| CONSENT[Consent Engine]
    CONSENT -->|4. Check Escrow| ESCROW[Escrow Service]
    ESCROW -->|5. Funds OK| CONSENT
    CONSENT -->|6. Create Contract| PG[(PostgreSQL)]
    CONSENT -->|7. Emit Event| KAFKA[(Kafka)]
    KAFKA -->|8. Generate Receipt| AUDIT[Audit Service]
    AUDIT -->|9. Store| NEO4J[(Neo4j)]
    CONSENT -->|10. Confirm| APP
    APP -->|11. Display| DS
```

### 3.2 Query Execution Flow

```mermaid
flowchart TB
    REQ[Requester] -->|1. Submit Query| API[API Gateway]
    API -->|2. Authorize| CONSENT[Consent Engine]
    CONSENT -->|3. Valid Consent| QUERY[Query Orchestrator]
    QUERY -->|4. Create Plan| PLAN[Query Plan]
    PLAN -->|5. Sign| HSM[HSM]
    QUERY -->|6. Dispatch| KAFKA[(Kafka)]
    KAFKA -->|7. Push| DEVICE[DS Device]
    DEVICE -->|8. Execute Locally| ODS[On-Device Store]
    ODS -->|9. Encrypted Response| DEVICE
    DEVICE -->|10. Return| QUERY
    QUERY -->|11. Build Capsule| CAPSULE[Time Capsule]
    CAPSULE -->|12. Store| MINIO[(MinIO)]
    QUERY -->|13. Deliver| REQ
```

### 3.3 Settlement Flow

```mermaid
flowchart LR
    COMPLETE[Consent Complete] -->|1. Trigger| SETTLE[Settlement Service]
    SETTLE -->|2. Calculate| CALC[Fee Calculator]
    CALC -->|3. DS Share| SETTLE
    SETTLE -->|4. Debit Escrow| ESCROW[(Escrow)]
    SETTLE -->|5. Credit DS| BALANCE[(DS Balance)]
    SETTLE -->|6. Journal Entry| LEDGER[(Financial Ledger)]
    LEDGER -->|7. Double-Entry| PG[(PostgreSQL)]
    SETTLE -->|8. Emit Event| KAFKA[(Kafka)]
    KAFKA -->|9. Receipt| AUDIT[Audit Service]
    
    subgraph "Payout (On Request)"
        BALANCE -->|10. Request| PAYOUT[Payout Service]
        PAYOUT -->|11. Fraud Check| FRAUD[Fraud Engine]
        FRAUD -->|12. OK| PAYOUT
        PAYOUT -->|13. Disburse| PROVIDER[Payout Provider]
    end
```

### 3.4 Data Lifecycle

```mermaid
flowchart TB
    subgraph "Collection"
        DS[DS Device] -->|Collect| ODS[On-Device Store]
        ODS -->|Encrypt| ENC[AES-256-GCM]
    end
    
    subgraph "Discovery"
        ODS -->|Labels Only| ODX[Label Index]
        ODX -->|Sync| PLATFORM[Platform Index]
    end
    
    subgraph "Access"
        PLATFORM -->|Match| QUERY[Query]
        QUERY -->|Consent Check| CONSENT[Consent Engine]
        CONSENT -->|Approved| QUERY
        QUERY -->|Execute| ODS
    end
    
    subgraph "Delivery"
        ODS -->|Response| CAPSULE[Time Capsule]
        CAPSULE -->|TTL| EXPIRE[Auto-Expire]
    end
    
    subgraph "Deletion"
        EXPIRE -->|Crypto-Shred| DELETE[Secure Delete]
        DELETE -->|Certificate| AUDIT[Audit]
    end
```

---

## 4. SERVICE ARCHITECTURE

### 4.1 Microservices Map

```mermaid
graph TB
    subgraph "Core Services"
        CONSENT[Consent Engine]
        QUERY[Query Orchestrator]
        SCREEN[Screening Engine]
        AUDIT[Audit Service]
        SETTLE[Settlement Service]
    end
    
    subgraph "Privacy Services"
        PRIVACY[Privacy Governor]
        MPC[MPC Engine]
        ZKP[ZKP Verifier]
        CLEANROOM[Clean Room]
    end
    
    subgraph "Graph Services"
        KIPUX[KIPUX Graph]
        DEVICE_GRAPH[Device Graph]
        LINEAGE[Model Lineage]
    end
    
    subgraph "Financial Services"
        ESCROW[Escrow Manager]
        LEDGER[Financial Ledger]
        PAYOUT[Payout Service]
    end
    
    subgraph "Intelligence Services"
        FRAUD[Fraud Engine]
        MARKET[Market Integrity]
        SAFE[Safe Mode Controller]
    end
    
    CONSENT --> AUDIT
    QUERY --> CONSENT
    QUERY --> PRIVACY
    SCREEN --> PRIVACY
    SETTLE --> LEDGER
    SETTLE --> ESCROW
    PAYOUT --> FRAUD
    KIPUX --> AUDIT
```

### 4.2 Service Communication

```mermaid
graph LR
    subgraph "Sync (gRPC)"
        A[Service A] -->|gRPC| B[Service B]
    end
    
    subgraph "Async (Kafka)"
        C[Producer] -->|Event| KAFKA[(Kafka)]
        KAFKA -->|Event| D[Consumer 1]
        KAFKA -->|Event| E[Consumer 2]
    end
    
    subgraph "Cache (Redis)"
        F[Service] -->|Read/Write| REDIS[(Redis)]
    end
    
    subgraph "Stream (gRPC)"
        G[Query Orchestrator] <-->|Bidirectional| H[Device]
    end
```

---

## 5. DATABASE ARCHITECTURE

### 5.1 Polyglot Persistence

```mermaid
graph TB
    subgraph "Transactional (PostgreSQL)"
        PG[(PostgreSQL 16)]
        PG --> USERS[Users]
        PG --> CONSENTS[Consents]
        PG --> REQUESTS[Requests]
        PG --> ESCROW[Escrow]
        PG --> JOURNAL[Journal Entries]
    end
    
    subgraph "Events (Kafka)"
        KAFKA[(Kafka 3.7)]
        KAFKA --> CONSENT_EVT[consent.events]
        KAFKA --> AUDIT_EVT[audit.receipts]
        KAFKA --> SETTLE_EVT[settlement.events]
    end
    
    subgraph "Cache (Redis)"
        REDIS[(Redis 7)]
        REDIS --> SESSIONS[Sessions]
        REDIS --> NONCES[Nonces]
        REDIS --> RATE[Rate Limits]
    end
    
    subgraph "Graph (Neo4j)"
        NEO4J[(Neo4j 5)]
        NEO4J --> KIPUX[KIPUX Provenance]
        NEO4J --> DEVICE[Device Graph]
        NEO4J --> MODEL[Model Lineage]
    end
    
    subgraph "Time-Series (TimescaleDB)"
        TSDB[(TimescaleDB)]
        TSDB --> IOT[IoT Sensors]
        TSDB --> METRICS[Platform Metrics]
    end
    
    subgraph "Objects (MinIO)"
        MINIO[(MinIO)]
        MINIO --> CAPSULES[Time Capsules]
        MINIO --> EVIDENCE[Evidence Packs]
    end
```

### 5.2 Data Partitioning Strategy

```mermaid
graph LR
    subgraph "PostgreSQL Partitioning"
        AUDIT_TABLE[audit_receipts] --> P1[2024-Q1]
        AUDIT_TABLE --> P2[2024-Q2]
        AUDIT_TABLE --> P3[2024-Q3]
        AUDIT_TABLE --> P4[2024-Q4]
    end
    
    subgraph "Kafka Partitioning"
        TOPIC[consent.events] --> K1[Partition 0<br/>DS A-M]
        TOPIC --> K2[Partition 1<br/>DS N-Z]
        TOPIC --> K3[Partition 2<br/>Overflow]
    end
    
    subgraph "Redis Clustering"
        CLUSTER[Redis Cluster] --> S1[Shard 1<br/>Sessions]
        CLUSTER --> S2[Shard 2<br/>Nonces]
        CLUSTER --> S3[Shard 3<br/>Cache]
    end
```

---

## 6. SECURITY ARCHITECTURE

### 6.1 Security Layers

```mermaid
graph TB
    subgraph "Edge Security"
        WAF[WAF / DDoS Protection]
        CDN[CDN with TLS 1.3]
    end
    
    subgraph "Gateway Security"
        APISIX[Apache APISIX]
        RATE[Rate Limiting]
        JWT[JWT Validation]
    end
    
    subgraph "Identity Security"
        KEYCLOAK[Keycloak]
        MFA[Multi-Factor Auth]
        OAUTH[OAuth2/OIDC]
    end
    
    subgraph "Application Security"
        OPA[Open Policy Agent]
        RBAC[Role-Based Access]
        CONSENT_CHECK[Consent Validation]
    end
    
    subgraph "Data Security"
        VAULT[HashiCorp Vault]
        HSM[Hardware Security Module]
        ENC[AES-256-GCM Encryption]
    end
    
    subgraph "Privacy Security"
        MPC[Multi-Party Computation]
        ZKP[Zero-Knowledge Proofs]
        SGX[Intel SGX Enclaves]
    end
    
    WAF --> CDN --> APISIX
    APISIX --> RATE --> JWT
    JWT --> KEYCLOAK --> MFA
    KEYCLOAK --> OPA --> RBAC
    RBAC --> VAULT --> HSM
    OPA --> MPC
    OPA --> ZKP
    OPA --> SGX
```

### 6.2 Key Management

```mermaid
graph TB
    subgraph "Key Hierarchy"
        ROOT[K-ROOT<br/>HSM-Protected]
        ROOT --> K_DS[K-DS<br/>Per Data Sovereign]
        K_DS --> K_CAT[K-CAT<br/>Per Category]
        ROOT --> K_PLATFORM[K-PLATFORM<br/>Platform Keys]
        K_PLATFORM --> K_SIGN[Signing Keys]
        K_PLATFORM --> K_ENC[Encryption Keys]
    end
    
    subgraph "Key Storage"
        VAULT[HashiCorp Vault]
        HSM[CloudHSM]
        DEVICE[Device Keychain]
    end
    
    ROOT --> HSM
    K_PLATFORM --> VAULT
    K_DS --> DEVICE
```

---

## 7. SEQUENCE DIAGRAMS

### 7.1 Complete Request-to-Payout Flow

```mermaid
sequenceDiagram
    participant REQ as Requester
    participant API as API Gateway
    participant SCR as Screening
    participant ESC as Escrow
    participant MAT as Matching
    participant DS as Data Sovereign
    participant CON as Consent
    participant QRY as Query
    participant SET as Settlement
    participant PAY as Payout
    
    REQ->>API: 1. Create Request
    API->>SCR: 2. Screen Request
    SCR-->>API: 3. Approved
    API->>ESC: 4. Fund Escrow
    ESC-->>API: 5. Funded
    API->>MAT: 6. Find Eligible DS
    MAT-->>DS: 7. Deliver Request
    DS->>CON: 8. Grant Consent
    CON-->>DS: 9. Contract Created
    DS->>QRY: 10. Execute Query
    QRY-->>REQ: 11. Deliver Capsule
    REQ->>SET: 12. Confirm Receipt
    SET->>ESC: 13. Release Funds
    SET-->>DS: 14. Credit Balance
    DS->>PAY: 15. Request Payout
    PAY-->>DS: 16. Funds Disbursed
```

### 7.2 Consent Revocation Flow

```mermaid
sequenceDiagram
    participant DS as Data Sovereign
    participant APP as Mobile App
    participant API as API Gateway
    participant CON as Consent Engine
    participant CACHE as Redis Cache
    participant KAFKA as Kafka
    participant AUDIT as Audit Service
    
    DS->>APP: 1. Tap Revoke
    APP->>API: 2. DELETE /consent/{id}
    API->>CON: 3. Revoke Contract
    CON->>CON: 4. Update Status
    CON->>CACHE: 5. Invalidate Cache
    CON->>KAFKA: 6. Emit CONSENT_REVOKED
    KAFKA->>AUDIT: 7. Generate Receipt
    CON-->>API: 8. Revoked (< 60s SLA)
    API-->>APP: 9. Confirmation
    APP-->>DS: 10. Display Success
    
    Note over CON,CACHE: All future access blocked within 60 seconds
```

### 7.3 MPC Secure Matching

```mermaid
sequenceDiagram
    participant REQ as Requester
    participant PRIV as Privacy Governor
    participant MPC1 as MPC Node 1<br/>(Platform)
    participant MPC2 as MPC Node 2<br/>(Auditor)
    participant MPC3 as MPC Node 3<br/>(DS Rep)
    
    REQ->>PRIV: 1. Match Request (criteria)
    PRIV->>MPC1: 2. Secret Share (criteria)
    PRIV->>MPC2: 2. Secret Share (criteria)
    PRIV->>MPC3: 2. Secret Share (criteria)
    
    MPC1->>MPC1: 3. Local Computation
    MPC2->>MPC2: 3. Local Computation
    MPC3->>MPC3: 3. Local Computation
    
    MPC1-->>MPC2: 4. Exchange Shares
    MPC2-->>MPC3: 4. Exchange Shares
    MPC3-->>MPC1: 4. Exchange Shares
    
    MPC1->>PRIV: 5. Reveal Result
    MPC2->>PRIV: 5. Reveal Result
    MPC3->>PRIV: 5. Reveal Result
    
    PRIV->>PRIV: 6. Combine (k ≥ 50?)
    PRIV-->>REQ: 7. Cohort Size (no individual data)
    
    Note over MPC1,MPC3: No single party sees raw DS attributes
```

### 7.4 Clean Room Session

```mermaid
sequenceDiagram
    participant REQ as Requester
    participant CR as Clean Room Service
    participant SGX as SGX Enclave
    participant MINIO as MinIO
    participant AUDIT as Audit Service
    
    REQ->>CR: 1. Create Session
    CR->>SGX: 2. Initialize Enclave
    SGX-->>CR: 3. Attestation Proof
    CR->>MINIO: 4. Fetch Capsule
    MINIO-->>SGX: 5. Encrypted Data
    SGX->>SGX: 6. Decrypt in Enclave
    
    loop Analysis
        REQ->>CR: 7. Execute Query
        CR->>SGX: 8. Run in Enclave
        SGX->>SGX: 9. Process (no export)
        SGX-->>CR: 10. Result Only
        CR->>AUDIT: 11. Log Interaction
        CR-->>REQ: 12. Display Result
    end
    
    REQ->>CR: 13. End Session
    CR->>SGX: 14. Destroy Enclave
    CR->>AUDIT: 15. Session Complete
    
    Note over SGX: Data never leaves encrypted memory
```

---

## 8. DEPLOYMENT ARCHITECTURE

### 8.1 Kubernetes Deployment

```mermaid
graph TB
    subgraph "Kubernetes Cluster"
        subgraph "Ingress"
            ING[Ingress Controller]
            CERT[Cert Manager]
        end
        
        subgraph "Namespace: yachaq-core"
            CONSENT_POD[Consent Engine<br/>3 replicas]
            QUERY_POD[Query Orchestrator<br/>3 replicas]
            AUDIT_POD[Audit Service<br/>2 replicas]
        end
        
        subgraph "Namespace: yachaq-data"
            PG_POD[PostgreSQL<br/>Primary + Replica]
            REDIS_POD[Redis Cluster<br/>6 nodes]
            KAFKA_POD[Kafka<br/>3 brokers]
        end
        
        subgraph "Namespace: yachaq-security"
            KEYCLOAK_POD[Keycloak<br/>2 replicas]
            VAULT_POD[Vault<br/>3 nodes]
        end
        
        subgraph "Namespace: yachaq-observability"
            PROM[Prometheus]
            GRAFANA[Grafana]
            SKY[SkyWalking]
        end
    end
    
    ING --> CONSENT_POD
    ING --> QUERY_POD
    CONSENT_POD --> PG_POD
    CONSENT_POD --> KAFKA_POD
    QUERY_POD --> REDIS_POD
    CONSENT_POD --> KEYCLOAK_POD
    CONSENT_POD --> VAULT_POD
```

### 8.2 Multi-Region Deployment

```mermaid
graph TB
    subgraph "Region: Primary"
        EC_LB[Load Balancer]
        EC_K8S[K8s Cluster]
        EC_PG[(PostgreSQL Primary)]
        EC_KAFKA[(Kafka)]
    end
    
    subgraph "Region: US-East (DR)"
        US_LB[Load Balancer]
        US_K8S[K8s Cluster]
        US_PG[(PostgreSQL Replica)]
        US_KAFKA[(Kafka Mirror)]
    end
    
    subgraph "Global"
        DNS[Route 53 / CloudFlare]
        CDN[CDN Edge]
    end
    
    DNS --> CDN
    CDN --> EC_LB
    CDN --> US_LB
    EC_LB --> EC_K8S
    US_LB --> US_K8S
    EC_PG -->|Streaming Replication| US_PG
    EC_KAFKA -->|MirrorMaker| US_KAFKA
```

### 8.3 CI/CD Pipeline

```mermaid
flowchart LR
    subgraph "Source"
        GIT[GitHub]
    end
    
    subgraph "Build"
        GHA[GitHub Actions]
        TEST[Unit Tests]
        LINT[Linting]
        SCAN[Security Scan]
    end
    
    subgraph "Artifacts"
        REG[Container Registry]
        HELM[Helm Charts]
    end
    
    subgraph "Deploy"
        ARGO[ArgoCD]
        DEV[Dev Cluster]
        STG[Staging Cluster]
        PROD[Production Cluster]
    end
    
    GIT -->|Push| GHA
    GHA --> TEST --> LINT --> SCAN
    SCAN -->|Pass| REG
    SCAN -->|Pass| HELM
    REG --> ARGO
    HELM --> ARGO
    ARGO -->|Auto| DEV
    ARGO -->|Manual| STG
    ARGO -->|Manual + Approval| PROD
```

---

## 9. ADDITIONAL DIAGRAMS

### 9.1 KIPUX Provenance Graph Structure

```mermaid
graph LR
    subgraph "Weave (Campaign)"
        W[Weave: Campaign-123]
    end
    
    subgraph "Cords (Threads)"
        C1[Cord: DS Contribution]
        C2[Cord: Capsule Thread]
        C3[Cord: Settlement Batch]
    end
    
    subgraph "Knots (Events)"
        K1[Knot: Consent Granted]
        K2[Knot: Query Executed]
        K3[Knot: Capsule Created]
        K4[Knot: Settlement Posted]
        K5[Knot: Payout Completed]
    end
    
    W --> C1
    W --> C2
    W --> C3
    C1 --> K1
    C2 --> K2
    C2 --> K3
    C3 --> K4
    C3 --> K5
    
    K1 -->|hash| K2
    K2 -->|hash| K3
    K3 -->|hash| K4
    K4 -->|hash| K5
```

### 9.2 Device Trust Scoring

```mermaid
graph TB
    subgraph "Attestation Signals"
        SAFETY[SafetyNet/DeviceCheck]
        TPM[TPM Attestation]
        BEHAVIOR[Behavioral Analysis]
    end
    
    subgraph "Trust Calculation"
        SCORE[Trust Score Engine]
        HIGH[High Trust: 0.8-1.0]
        MED[Medium Trust: 0.5-0.8]
        LOW[Low Trust: 0.0-0.5]
    end
    
    subgraph "Actions"
        FULL[Full Access]
        LIMITED[Limited Access]
        BLOCKED[Enhanced Verification]
    end
    
    SAFETY --> SCORE
    TPM --> SCORE
    BEHAVIOR --> SCORE
    
    SCORE --> HIGH --> FULL
    SCORE --> MED --> LIMITED
    SCORE --> LOW --> BLOCKED
```

### 9.3 Privacy Risk Budget Flow

```mermaid
flowchart TB
    subgraph "Quote Phase"
        REQ[Request Created] --> ALLOC[Allocate PRB]
        ALLOC --> DRAFT[PRB: Draft]
    end
    
    subgraph "Acceptance Phase"
        DRAFT --> ACCEPT[Requester Accepts]
        ACCEPT --> LOCK[Lock PRB]
        LOCK --> LOCKED[PRB: Locked]
    end
    
    subgraph "Execution Phase"
        LOCKED --> TRANSFORM[Apply Transform]
        TRANSFORM --> CONSUME[Consume PRB]
        CONSUME --> CHECK{PRB > 0?}
        CHECK -->|Yes| TRANSFORM
        CHECK -->|No| EXHAUST[PRB: Exhausted]
    end
    
    subgraph "Blocking"
        EXHAUST --> BLOCK[Block Further Operations]
    end
```

---

**Document End**

*All diagrams use Mermaid syntax and are renderable in GitHub, GitLab, VS Code, and most modern markdown viewers.*

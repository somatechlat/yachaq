<p align="center">
  <img src="https://img.shields.io/badge/YACHAQ-Data%20Sovereignty-6366f1?style=for-the-badge&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCIgdmlld0JveD0iMCAwIDI0IDI0IiBmaWxsPSJub25lIiBzdHJva2U9IndoaXRlIiBzdHJva2Utd2lkdGg9IjIiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTEyIDJhMTAgMTAgMCAxIDAgMTAgMTBIMTJWMnoiLz48cGF0aCBkPSJNMjEuMTggOC44MmMtLjI4LS40NC0uNTktLjg1LS45My0xLjI0Ii8+PC9zdmc+" alt="YACHAQ Badge"/>
</p>

<h1 align="center">🌟 YACHAQ Platform</h1>

<p align="center">
  <strong>Consent-First Personal Data Sovereignty Infrastructure</strong>
</p>

<p align="center">
  <em>"Ya·chaq" — From Quechua: "one who knows, one who learns, one who understands"</em>
</p>

<p align="center">
  <a href="#-overview">Overview</a> •
  <a href="#-key-features">Features</a> •
  <a href="#-architecture">Architecture</a> •
  <a href="#-getting-started">Getting Started</a> •
  <a href="#-documentation">Docs</a> •
  <a href="#-license">License</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21%20LTS-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4.x-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/Redis-7.x-DC382D?style=flat-square&logo=redis&logoColor=white" alt="Redis"/>
  <img src="https://img.shields.io/badge/Kafka-3.x-231F20?style=flat-square&logo=apachekafka&logoColor=white" alt="Kafka"/>
</p>

---

## 🌍 Overview

**YACHAQ** is a revolutionary consent-first, edge-first personal data sovereignty platform that puts individuals in complete control of their personal data. The platform enables people to understand, manage, and benefit from their data while creating a transparent marketplace where organizations can ethically request access to user data and insights.

### The Problem We Solve

In today's digital economy:
- 🔒 Users have no control over how their data is collected and used
- 💰 The value of personal data flows to corporations, not individuals
- 🕵️ Privacy is an afterthought, not a design principle
- 📊 Data access is opaque and non-consensual

### Our Solution

YACHAQ flips the paradigm:
- ✅ **Consent-First**: Every data access requires explicit, purpose-bound consent
- 🏠 **Edge-First**: Data stays on user devices; platform queries devices live
- 💎 **Fair Compensation**: Uniform pricing regardless of geography
- 🔍 **Full Transparency**: Immutable audit trails for all data events
- 🛡️ **Privacy by Architecture**: Operators cannot access raw user data

---

## ✨ Key Features

### For Data Sovereigns (Users)

| Feature | Description |
|---------|-------------|
| 🎛️ **Granular Consent** | Control data sharing per category, attribute, purpose, and duration |
| 💵 **Fair Compensation** | Earn from your data with uniform pricing in your local currency |
| 📱 **Mobile-First** | Manage everything from your phone with offline support |
| 🔐 **Privacy Controls** | Anonymization by default, identity reveal only with explicit consent |
| 📋 **Audit Trail** | Complete history of all data access with blockchain anchoring |
| ⏱️ **Time-Limited Access** | Data access expires automatically via Time Capsules |

### For Requesters (Organizations)

| Feature | Description |
|---------|-------------|
| 🎯 **Ethical Data Access** | Purpose-bound requests with transparent terms |
| 🏢 **Tiered Verification** | Verified Organizations (VO) and Community Requesters (CR) |
| 💳 **Escrow Pre-Funding** | Guaranteed payment through locked escrow |
| 🤖 **AI-Powered Matching** | Privacy-preserving matching without exposing user data |
| 📊 **Clean Room Delivery** | Controlled data access environment |
| ✅ **Compliance Ready** | GDPR, CCPA, ISO 27001 aligned |

### Platform Capabilities

| Capability | Technology |
|------------|------------|
| 🔐 **Encryption** | AES-256-GCM at rest, TLS 1.3 in transit |
| ⛓️ **Blockchain** | EVM-compatible anchoring for tamper-evidence |
| 🌳 **Merkle Trees** | Efficient batch verification of audit receipts |
| 🔑 **Key Hierarchy** | K-ROOT → K-DS → K-CAT envelope encryption |
| 🛡️ **k-Anonymity** | Minimum cohort size (k≥50) for all queries |
| ⚡ **Property Testing** | Formal correctness verification with jqwik |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           DATA SOVEREIGN LAYER                               │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐              │
│  │   Mobile App    │  │  On-Device      │  │  On-Device      │              │
│  │   (iOS/Android) │  │  Data Store     │  │  Label Index    │              │
│  │                 │  │  (ODS)          │  │  (ODX)          │              │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘              │
└───────────┼─────────────────────┼─────────────────────┼──────────────────────┘
            │                     │                     │
            ▼                     ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            PLATFORM LAYER                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   Consent    │  │   Matching   │  │   Query      │  │  Screening   │     │
│  │   Engine     │  │   Engine     │  │  Orchestrator│  │   Engine     │     │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   Escrow     │  │  Settlement  │  │   Payout     │  │   Privacy    │     │
│  │   Service    │  │   Service    │  │   Service    │  │   Governor   │     │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────────────────────┘
            │                     │                     │
            ▼                     ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            LEDGER LAYER                                      │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐   │
│  │   Audit Receipt      │  │   Financial          │  │   Blockchain     │   │
│  │   Ledger             │  │   Ledger             │  │   Anchor         │   │
│  │   (Append-Only)      │  │   (Double-Entry)     │  │   (EVM)          │   │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Module Structure

```
yachaq-platform/
├── yachaq-core/          # Domain entities, repositories, migrations
├── yachaq-api/           # REST API, services, controllers
├── yachaq-consent/       # Consent engine
├── yachaq-audit/         # Audit receipt ledger
├── yachaq-financial/     # Financial ledger, escrow
├── yachaq-query/         # Query orchestrator, time capsules
├── yachaq-screening/     # Request screening engine
├── yachaq-privacy/       # Privacy governor, k-anonymity
├── yachaq-blockchain/    # Smart contracts, anchoring
├── yachaq-fraud/         # Fraud detection
├── yachaq-cleanroom/     # Clean room delivery
├── yachaq-verification/  # Device attestation
├── yachaq-graph/         # KIPUX provenance graphs
├── yachaq-market/        # Market integrity
├── yachaq-iot/           # IoT division
└── yachaq-gateway/       # API gateway
```

---

## 🚀 Getting Started

### Prerequisites

- **Java 21 LTS** (OpenJDK or GraalVM)
- **Maven 3.9+**
- **Docker & Docker Compose**
- **PostgreSQL 16** (or use Docker)
- **Redis 7.x** (or use Docker)

### Quick Start

```bash
# Clone the repository
git clone https://github.com/somatechlat/yachaq.git
cd yachaq/yachaq-platform

# Start infrastructure with Docker
docker-compose up -d

# Build the project
mvn clean install

# Run tests
mvn test

# Start the API
mvn spring-boot:run -pl yachaq-api
```

### Configuration

Key environment variables:

```bash
# Database
YACHAQ_DB_URL=jdbc:postgresql://localhost:5432/yachaq
YACHAQ_DB_USERNAME=yachaq
YACHAQ_DB_PASSWORD=secret

# Redis
YACHAQ_REDIS_HOST=localhost
YACHAQ_REDIS_PORT=6379

# Security
YACHAQ_JWT_SECRET=your-256-bit-secret
YACHAQ_ENCRYPTION_ROOT_KEY=your-root-key

# Matching
YACHAQ_MATCHING_MIN_COHORT_SIZE=50
```

---

## 📊 Correctness Properties

YACHAQ is built with formal correctness in mind. Key properties verified through property-based testing:

| Property | Description | Requirement |
|----------|-------------|-------------|
| **P1** | Consent contracts contain all required fields | 3.1 |
| **P2** | Revocation blocks access within 60 seconds | 3.4 |
| **P3** | Escrow must be funded before delivery | 7.1, 7.2 |
| **P4** | Uniform compensation regardless of geography | 10.2 |
| **P5** | Audit receipts generated for all key events | 12.1 |
| **P6** | AES-256-GCM encryption at rest | 121.1 |
| **P7** | Data integrity via cryptographic hashes | 125.1 |
| **P8** | Valid Merkle proofs for blockchain anchoring | 126.3 |
| **P9** | Double-entry balance (debits = credits) | 186.1 |
| **P11** | k-anonymity threshold (k ≥ 50) | 196.1 |
| **P13** | Time capsule TTL enforcement | 206.2 |
| **P14** | Token issuance round-trip | 1.2 |
| **P15** | Query plan signature verification | 216.1, 216.2 |

---

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [Requirements](/.kiro/specs/yachaq-platform/requirements.md) | EARS-compliant system requirements |
| [Design](/.kiro/specs/yachaq-platform/design.md) | Architecture and data models |
| [Tasks](/.kiro/specs/yachaq-platform/tasks.md) | Implementation plan |
| [ADR](/.kiro/specs/yachaq-platform/ARCHITECTURE_DECISION_RECORD.md) | Architecture decisions |
| [Legal](/.kiro/specs/yachaq-platform/LEGAL.md) | Privacy and compliance |

---

## 🧪 Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest="ConsentServicePropertyTest"

# Run with coverage
mvn test jacoco:report
```

### Test Categories

- **Property Tests**: Formal correctness verification (jqwik)
- **Unit Tests**: Component-level testing
- **Integration Tests**: Cross-service testing

---

## 🛡️ Security

YACHAQ implements defense-in-depth:

- **Encryption**: AES-256-GCM at rest, TLS 1.3 in transit
- **Authentication**: OAuth2/OIDC with short-lived tokens
- **Authorization**: RBAC + ABAC with OPA policies
- **Key Management**: HSM-backed key hierarchy
- **Audit**: Append-only ledger with blockchain anchoring
- **Privacy**: k-anonymity, differential privacy support

Report security issues to: security@yachaq.io

---

## 🗺️ Roadmap

### Completed ✅
- [x] Core infrastructure and database schemas
- [x] Authentication and identity service
- [x] Consent engine with revocation SLA
- [x] Audit receipt ledger with Merkle trees
- [x] Request screening engine
- [x] Escrow and financial ledger
- [x] Privacy-preserving matching engine
- [x] Query orchestrator and time capsules
- [x] Data protection layer (encryption/integrity)
- [x] Settlement and payout services

### In Progress 🚧
- [ ] YC token management
- [ ] On-device components (Client SDK)
- [ ] Smart contracts (Blockchain layer)
- [ ] Device attestation service
- [ ] Clean room hardening

### Planned 📋
- [ ] Phone-as-Node P2P architecture
- [ ] Privacy governor service
- [ ] Model-data lineage ledger
- [ ] Extended account types

---

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- The Quechua people for the inspiration behind our name
- The open-source community for the amazing tools we build upon
- Our early adopters and contributors

---

<p align="center">
  <strong>Built with ❤️ for a more equitable data economy</strong>
</p>

<p align="center">
  <a href="https://yachaq.io">Website</a> •
  <a href="https://docs.yachaq.io">Documentation</a> •
  <a href="https://twitter.com/yachaq_io">Twitter</a>
</p>

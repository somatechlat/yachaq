# Yachaq Platform Architecture

## Overview

The Yachaq Platform is a multi-service system designed around the principles of data sovereignty, consent management, and secure data exchange. It is built as a Java-based backend with a modular, service-oriented architecture, and provides SDKs for client integration.

## Core Principles

-   **Consent-First:** No data is accessed or processed without an explicit, verifiable consent contract.
-   **Immutability:** All significant events (consents, queries, etc.) are recorded as immutable audit receipts, forming a hash chain for integrity.
-   **Data Minimization:** The platform provides tools for requesters to access only the data they need, using features like field-level access control and transform restrictions.
-   **Security by Design:** The architecture includes secure-by-default mechanisms like clean rooms, data encryption, and automated verification workflows.

## High-Level Components

The system is organized into several key components, primarily as Maven modules within the `yachaq-platform/` directory.

1.  **`yachaq-api` (API Layer):**
    *   Provides the public-facing **GraphQL API**.
    *   Contains the GraphQL resolvers (`QueryResolver`, `MutationResolver`, `SubscriptionResolver`) that delegate requests to the appropriate backend services.
    *   Handles incoming requests, authentication, and data transformation for the API layer.

2.  **`yachaq-core` (Core Domain):**
    *   The heart of the application, containing the primary domain entities like `ConsentContract`, `AuditReceipt`, and `Request`.
    *   Defines the core business logic and state transitions for these entities.
    *   Contains the JPA repositories for database interaction.

3.  **Service Modules (e.g., `yachaq-consent`, `yachaq-audit`, `yachaq-settlement`):**
    *   These modules contain the business logic for specific domains.
    *   For example, `ConsentService` would handle the logic for granting and revoking consent, while `AuditService` would manage the creation and retrieval of audit receipts.
    *   They are called by the API layer and interact with the `yachaq-core` domain and repositories.

4.  **`yachaq-sdk` (Software Development Kits):**
    *   Provides client libraries for interacting with the Yachaq API.
    *   The **`typescript`** SDK offers a type-safe client for web and Node.js applications.

5.  **`yachaq-blockchain` (Blockchain Integration):**
    *   Contains logic for anchoring critical events (like consent creation) to a public or private blockchain for ultimate verifiability.
    *   Includes Solidity smart contracts (`.sol` files) for managing on-chain records.

## Data Flow Example: Granting Consent

1.  A user action in a client application triggers a call to the **TypeScript SDK**.
2.  The SDK calls the `grantConsent` mutation in the Yachaq **GraphQL API**.
3.  The `MutationResolver` in `yachaq-api` receives the request.
4.  It calls the `createConsent` method in the `ConsentService`.
5.  `ConsentService` creates a new `ConsentContract` entity from `yachaq-core`.
6.  It also creates an associated `AuditReceipt` entity.
7.  Both entities are saved to the database via their respective repositories in `yachaq-core`.
8.  The `MutationResolver` returns the result to the client.

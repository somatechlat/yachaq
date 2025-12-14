# YACHAQ Legal & Compliance Documentation (Consolidated)

**Document Purpose:** Single source of truth for all legal policies, privacy documentation, and compliance requirements.

**Version:** 1.0  
**Last Updated:** December 2025  
**Status:** TEMPLATE - Requires legal counsel review before launch

---

## TABLE OF CONTENTS

1. [Global Privacy Policy](#1-global-privacy-policy)
2. [Mobile App Permissions Notice](#2-mobile-app-permissions-notice)
3. [In-App Just-in-Time Notices](#3-in-app-just-in-time-notices)
4. [Mobile App Privacy Addendum](#4-mobile-app-privacy-addendum)
5. [Minors & Parental Consent Policy](#5-minors--parental-consent-policy)
6. [Safety & Security Disclaimer](#6-safety--security-disclaimer)
7. [App Store Compliance Checklist](#7-app-store-compliance-checklist)

---

# 1. GLOBAL PRIVACY POLICY

## 1.1 Who We Are

"YACHAQ," "we," "us," or "our" refers to the entity that operates the Platform.

**Contact:** privacy@yachaq.com  
**Data Protection contact:** dpo@yachaq.com

## 1.2 What YACHAQ Is

YACHAQ is a consent-first data/knowledge sovereignty platform. Our architecture:
- Keeps raw personal data device-resident by default
- Uses ODX for discovery (coarse labels only; no raw data)
- Requires explicit consent via ConsentContract + signed QueryPlan
- Delivers results through Time Capsules (encrypted, time-limited)
- Defaults to clean-room delivery (exports are gated)
- Maintains append-only AuditReceipt ledger (payload-free)
- Settles via escrow prefunding and double-entry accounting

## 1.3 Key Definitions

| Term | Definition |
|------|------------|
| **Data Subject (DS)** | Individual/organization contributing data under ConsentContract |
| **Requester (RQ)** | Individual/organization requesting access under ConsentContract |
| **ConsentContract** | Agreement defining purpose, scope, time, compensation, restrictions |
| **QueryPlan** | Signed execution plan specifying what may be computed/accessed |
| **ODX** | Coarse discovery index (labels/timestamps only, no raw data) |
| **Time Capsule** | Encrypted delivery container with TTL and deletion requirements |
| **Clean-room** | Restricted analysis environment with controlled exports |

## 1.4 Roles: Controller / Processor

**Platform operations:** YACHAQ acts as data controller for accounts, security, fraud prevention, receipts, billing.

**Marketplace transactions:** Requester may be controller of results obtained. YACHAQ facilitates under ConsentContract.

**Service providers:** Vetted vendors act as processors under contract.

## 1.5 Information We Handle

### Information You Provide
- Account information (name, email, phone, auth factors)
- Verification information (KYB, fraud prevention)
- Support communications

### Device-Resident Data (Default)
- Sensor signals (camera, mic, location, motion)
- Local files/media you select
- Connected IoT sensor data

**Raw data remains on device unless you accept ConsentContract + QueryPlan.**

### Discovery Data (ODX)
- Coarse labels ("has traffic sensor data")
- Coarse timestamps and granularity bands
- Designed to exclude raw data

### Security Telemetry
- Pseudonymous device identifiers
- Security signals (root/jailbreak, emulator)
- Hashed fingerprinting for fraud prevention
- Coarse network signals (IP-derived region)

### Transaction Metadata (Payload-Free)
- Consent/plan metadata (hashes, signatures, TTL)
- Job lifecycle receipts
- Clean-room access logs
- Dispute and resolution receipts

### Financial Information
- Escrow funding, reservations, releases
- Payout calculations by unit semantics
- Tax and accounting records

## 1.6 How We Use Information

1. **Provide Platform** - accounts, consent, QueryPlan execution
2. **Marketplace operations** - discovery, scheduling, settlement
3. **Security & fraud prevention** - abuse detection, device caps
4. **Compliance** - accounting, tax, legal obligations
5. **Service improvement** - reliability, performance, safety
6. **Communications** - service messages, security alerts

## 1.7 Consent-First Execution

Data request executes only if:
1. DS explicitly accepts ConsentContract
2. Execution uses signed QueryPlan defining:
   - Purpose and scope
   - Allowed transforms
   - Output restrictions
   - Delivery mode (clean-room default)
   - TTL and deletion behavior
   - Compensation and unit semantics

Device refuses execution if plan is invalid, expired, or out-of-scope.

## 1.8 Clean-Room & Export Policy

**Default:** Results delivered in clean-room with restricted exports.

**Exports (high-risk) may require:**
- Separate explicit consent
- Stronger verification
- Watermarking/fingerprinting
- Additional anti-targeting safeguards
- Manual review

**Reality:** No system can fully prevent copying of viewed information.

## 1.9 Sharing and Disclosure

- **With Requesters:** Only as permitted by ConsentContract/QueryPlan
- **With service providers:** Under contractual confidentiality
- **With node network:** Relay nodes route encrypted; CCW under attestation
- **For legal/safety:** Comply with legal obligations, protect rights

## 1.10 Data Retention

- **On-device raw data:** Controlled by user settings
- **Time Capsules:** Encrypted, time-limited per TTL
- **Receipts/ledger:** Retained for auditability; payload-free

## 1.11 Security Measures

- Encryption in transit and at rest
- Signed QueryPlans and cryptographic verification
- Strict access controls and least-privilege
- Monitoring and anomaly detection
- Device trust scoring, quarantine, decommission
- Secure deletion/expiry workflows

## 1.12 Your Rights

Depending on jurisdiction:
- Access to information
- Correction
- Deletion
- Restriction/objection
- Portability
- Withdrawing consent
- Opting out of sale/share

## 1.13 California (CCPA/CPRA) Notice

California residents may have rights to:
- Know/access
- Delete
- Correct
- Opt out of sale/sharing
- Limit use of sensitive personal information

We provide "Do Not Sell or Share" mechanism and recognize opt-out signals.

## 1.14 International Transfers

Cross-border transfers use appropriate safeguards (e.g., SCCs).

## 1.15 Changes to Policy

Updates posted with effective date. Material changes announced in-app or via email.

---

# 2. MOBILE APP PERMISSIONS NOTICE

## 2.1 What YACHAQ Does on Your Phone

Raw personal data stays on device by default. The app can:
1. Store data in encrypted on-device store
2. Maintain coarse discovery index (ODX)
3. Execute allowed transforms locally when you accept ConsentContract + QueryPlan
4. Deliver results via encrypted Time Capsules

## 2.2 Permission-by-Permission Disclosure

### Camera
- **When:** Only for camera-based features/campaigns
- **What:** Default to derived metrics (counts, heatmaps)
- **Leaves phone:** Derived outputs only; raw export is high-risk
- **Controls:** Deny/allow; revoke anytime

### Microphone
- **When:** Only for audio-based features/campaigns
- **What:** Default to derived metrics (decibel ranges, events)
- **Leaves phone:** Derived outputs; raw audio requires explicit consent
- **Controls:** Deny/allow; revoke anytime

### Location
- **When:** Campaign eligibility or live-event proof
- **What:** Prefer approximate; precise only when needed
- **Leaves phone:** Coarse location proofs for eligibility
- **Controls:** Grant approximate/precise; deny; revoke

### Photos/Media/Files
- **When:** Import/export or media selection for campaigns
- **What:** Access only selected items, not entire library
- **Leaves phone:** Within QueryPlan scope, encrypted capsules

### Bluetooth
- **When:** IoT gateway or proximity participation
- **What:** Connect to paired sensors only
- **Leaves phone:** Sensor derived outputs and health signals

### Motion/Activity/Fitness
- **When:** Motion-related campaigns
- **What:** Prefer derived features (step counts, activity states)
- **Leaves phone:** Derived metrics only

### Notifications
- **When:** Consent requests, campaign events, security alerts
- **What:** User awareness only

### Biometrics
- **When:** Optional for high-risk actions (exports, payouts)
- **What:** OS verifies; YACHAQ doesn't receive biometric template

### Device Identifiers
- **What:** Security telemetry (hashed/derived) for fraud detection
- **Not sold:** Used only for security, fraud prevention, payout integrity

---

# 3. IN-APP JUST-IN-TIME NOTICES

## 3.1 First-Run Privacy Summary

**Title:** Your data stays on your device by default

**Body:** YACHAQ keeps raw personal data on your device. We only run a data request after you explicitly approve it. Requests are defined by a signed plan listing exactly what can be used, for what purpose, for how long, and what you get paid.

## 3.2 Permission Pre-Prompts

### Camera
**Title:** Allow Camera?  
**Body:** We use the camera only for features you turn on. By default we compute derived metrics on-device and avoid exporting raw video. You can deny or revoke access anytime.

### Microphone
**Title:** Allow Microphone?  
**Body:** We use the microphone only for features you turn on. By default we compute derived metrics on-device and avoid exporting raw audio.

### Location
**Title:** Allow Location?  
**Body:** Some campaigns require location eligibility or live-event proof. We prefer approximate location. Precise or background location is requested only when needed.

## 3.3 ConsentContract Acceptance

**Must show:**
- Who is requesting (Requester identity)
- Purpose
- What data/signals requested (scope)
- Allowed transforms
- Output type (derived vs export)
- Time window + TTL
- Compensation and unit type
- Clean-room restrictions

**Footnote:** You can revoke future participation, but results already delivered may not be retractable. Clean-room and watermarking reduce misuse risk but cannot eliminate copying.

## 3.4 Export Warning

**Title:** Export is High Risk

**Body:** Exports can increase re-identification or misuse risk. YACHAQ defaults to clean-room delivery. If you continue, your export may be limited, watermarked, delayed, or blocked.

## 3.5 Lost/Compromised Device

**Title:** Lost or Compromised Device

**Body:** Quarantine immediately stops new requests and payouts from this device. You can enroll a new device after verification.

## 3.6 Security Risk Detected

**Title:** Device Security Risk Detected

**Body:** This device appears rooted/jailbroken or running in an unsafe environment. Participation may be limited until you verify on a secure device.

---

# 4. MOBILE APP PRIVACY ADDENDUM

## 4.1 On-Device vs Off-Device Processing

### On-Device (Default)
- Raw personal data and sensor signals
- Local execution of allowed transforms
- Local computation of derived metrics
- Local packaging into encrypted capsules

### Off-Device (Platform)
- Coarse discovery index (ODX)
- Consent and plan metadata (hashes, signatures)
- Receipt metadata (payload-free)
- Anti-fraud telemetry (hashed/derived)
- Settlement/escrow/ledger records

## 4.2 Legal Bases

- **Consent:** Optional permissions, sensitive data
- **Contract necessity:** Execute ConsentContract/QueryPlan
- **Legal obligation:** Tax, accounting, compliance
- **Legitimate interests:** Security, fraud prevention

---

# 5. MINORS & PARENTAL CONSENT POLICY

## 5.1 Age Gating

YACHAQ uses age gating to apply additional safeguards for minors, including disabling high-risk features by default.

## 5.2 Under-13 Users (COPPA)

If collecting personal information from children under 13, parental notice and verifiable consent required before enabling features that collect personal information via device permissions.

## 5.3 Teen Protections (13-17)

- Stronger anti-targeting and cohort minimums
- Default to derived metrics for sensors/camera
- Stricter export controls
- Additional fraud and safety checks

## 5.4 Prohibited Use

Users may not collect/share personal data about minors without necessary permissions and legal authority.

## 5.5 Parent/Guardian Controls

- Review and revoke consents
- Request account closure
- Request deletion where legally required

---

# 6. SAFETY & SECURITY DISCLAIMER

## 6.1 No Absolute Security Guarantee

We use strong encryption, signed plans, time-limited delivery, and audit receipts. However, no software guarantees absolute security. Security also depends on how you protect your device and account.

## 6.2 Device Integrity Requirements

To use earning features, you agree:
- Not to use rooted/jailbroken devices if app detects unsafe environment
- Not to run in emulators/automation designed to manipulate participation
- To keep OS reasonably updated

## 6.3 Camera/Microphone/Location Use

If you grant permissions:
- These sensors may capture sensitive information
- You can deny or revoke anytime
- App should only access when you activate relevant features
- OS indicators may show when camera/mic active

## 6.4 Clean-Room Limits

Clean-room reduces misuse risk. However, YACHAQ cannot guarantee requesters will never copy or misuse outputs. Watermarking and audit trails help detect misuse but cannot eliminate it.

## 6.5 Lost/Stolen Phones

Use "Lost Device" action immediately. Quarantine stops new requests and payouts. Delays increase risk.

## 6.6 Third-Party Data Responsibility

Only contribute data you have the right to share. Do not use YACHAQ to collect/share information about others without required permissions.

---

# 7. APP STORE COMPLIANCE CHECKLIST

## 7.1 Apple App Store (iOS)

### Permission Requests
- Request only when needed with clear explanation
- Provide explicit consent and clear indication when recording

### App Privacy Details
- Disclose data collected and usage in Privacy Details
- Disclose variation by region/age/opt-in status

### In-App Privacy
- Always-available Privacy Policy link

## 7.2 Google Play (Android)

### Data Safety Section
- Complete form: what data collected/shared, handling
- Keep disclosures consistent with actual behavior

### Privacy Policy URL
- Public, accessible URL in listing and in-app

## 7.3 GDPR/UK GDPR

- Layered notice approach
- Required disclosures (controller, purposes, legal basis, retention, rights, transfers)

## 7.4 COPPA (US)

- If collecting from under-13, obtain verifiable parental consent
- Device permissions can constitute personal information

## 7.5 Implementation Guidelines

### DO
- Use just-in-time consent screens before OS permission dialogs
- Default to derived metrics for camera/IoT
- Provide per-request consent with clear purpose/scope/TTL/compensation
- Provide revoke and lost-device quarantine

### DON'T
- Request permissions "just because"
- Collect raw camera/mic continuously by default
- Hide what you do with permissioned data

---

**END OF LEGAL DOCUMENTATION**

*This document is a template. Local counsel review required before launch in any jurisdiction.*

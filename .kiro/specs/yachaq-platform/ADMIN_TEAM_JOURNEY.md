# YACHAQ Platform - Admin Team Journey

## Document Information
- **Version**: 1.0.0
- **Created**: 2025-12-21
- **Purpose**: Complete journey for YACHAQ internal admin team managing the platform
- **Compliance**: VIBE CODING RULES - NO MOCKS, NO PLACEHOLDERS, REAL INFRASTRUCTURE ONLY

---

## Executive Summary

This document describes the complete journey of the **YACHAQ Admin Team** - the internal team responsible for platform operations, requester vetting, policy management, and dispute resolution.

### CRITICAL PRINCIPLE: YACHAQ AS PURE ORCHESTRATOR

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                        ADMIN TEAM RESPONSIBILITIES                                       │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  ✅ WHAT ADMIN TEAM DOES:                                                                │
│     • Verify requester identities (KYB)                                                  │
│     • Review and approve/reject data requests                                            │
│     • Manage platform policies                                                           │
│     • Handle disputes and violations                                                     │
│     • Monitor system health and compliance                                               │
│     • Generate audit reports for regulators                                              │
│                                                                                          │
│  ❌ WHAT ADMIN TEAM CANNOT DO:                                                           │
│     • Access user raw data (we never have it)                                            │
│     • Hold or transfer money (we're not a bank)                                          │
│     • Override user consent (blockchain-immutable)                                       │
│     • Modify audit trail (append-only, anchored)                                         │
│     • Access encryption keys (user-controlled)                                           │
│                                                                                          │
│  💡 ADMIN ACTIONS ARE FULLY AUDITED                                                      │
│     • Every admin action generates audit receipt                                         │
│     • All receipts anchored to blockchain                                                │
│     • Admins cannot hide their actions                                                   │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Admin Team Roles

| Role | Responsibilities | Access Level |
|------|------------------|--------------|
| **Platform Admin** | System configuration, user management | ADMIN |
| **Compliance Officer** | KYB review, policy enforcement | COMPLIANCE |
| **Support Agent** | User inquiries, basic troubleshooting | SUPPORT |
| **Security Analyst** | Threat monitoring, incident response | SECURITY |
| **Finance Ops** | Payment reconciliation, fee management | FINANCE |

---

## Admin Portal Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              ADMIN PORTAL ARCHITECTURE                                   │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐    │
│  │                           ADMIN DASHBOARD                                        │    │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │    │
│  │  │ KYB Queue    │ │ Request      │ │ Disputes     │ │ System       │            │    │
│  │  │ (12 pending) │ │ Review (5)   │ │ (3 open)     │ │ Health       │            │    │
│  │  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘            │    │
│  │                                                                                  │    │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │    │
│  │  │ Policy       │ │ Audit        │ │ Analytics    │ │ Settings     │            │    │
│  │  │ Management   │ │ Explorer     │ │ Dashboard    │ │              │            │    │
│  │  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘            │    │
│  └─────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                          │
│  Access: admin.yachaq.com (VPN + MFA required)                                           │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Complete Admin Team Journeys

### JOURNEY A: REQUESTER KYB VERIFICATION

**A new company wants to become a verified requester.**

#### Phase A1: KYB Application Receipt

1. **Application Notification**
   - Admin receives notification: "New KYB application: HealthInsights Corp"
   - Application enters KYB Queue
   - Auto-assigned to Compliance Officer (round-robin)
   - **Audit Receipt #A1**: `KYB_APPLICATION_RECEIVED` - org_id, assigned_to

2. **Initial Review**
   - Compliance Officer opens application
   - Sees submitted documents:
     - Certificate of Incorporation ✓
     - Business License ✓
     - Tax Registration ✓
     - Proof of Address ✓
   - **Audit Receipt #A2**: `KYB_REVIEW_STARTED` - reviewer_id, org_id

#### Phase A2: Document Verification

3. **Document Authenticity Check**
   - Each document verified against issuing authority
   - Certificate of Incorporation → State registry lookup
   - Business License → Local authority verification
   - Tax ID → IRS/Tax authority validation
   - **Audit Receipt #A3**: `DOCUMENTS_VERIFIED` - doc_ids, verification_sources

4. **Background Check Initiation**
   - Third-party background check triggered (e.g., Trulioo, Jumio)
   - Checks include:
     - Company registration status
     - Director/officer backgrounds
     - Litigation history
     - Media adverse mentions
   - **Audit Receipt #A4**: `BACKGROUND_CHECK_INITIATED` - provider, check_id

5. **Sanctions Screening**
   - Automated screening against:
     - OFAC SDN List (US)
     - EU Consolidated List
     - UN Security Council List
     - PEP databases
   - Result: CLEAR
   - **Audit Receipt #A5**: `SANCTIONS_SCREENING_COMPLETED` - result: CLEAR

#### Phase A3: Risk Assessment

6. **Risk Scoring**
   - Compliance Officer assigns risk score:
     - Industry risk: LOW (health research)
     - Geographic risk: LOW (US-based)
     - Size risk: LOW (established company)
     - Purpose risk: LOW (academic research)
     - **Overall**: LOW RISK
   - **Audit Receipt #A6**: `RISK_ASSESSMENT_COMPLETED` - score: LOW

7. **Tier Assignment**
   - Based on risk score and verification level:
     - `UNVERIFIED_INDIVIDUAL (UI)` - Basic, limited access
     - `VERIFIED_INDIVIDUAL (VI)` - ID verified
     - `VERIFIED_ORGANIZATION (VO)` - Full KYB ← **Assigned**
     - `ENTERPRISE (ENT)` - Custom terms
   - **Audit Receipt #A7**: `TIER_ASSIGNED` - tier: VO

#### Phase A4: Approval Decision

8. **Approval Workflow**
   - Compliance Officer recommends: APPROVE
   - Second reviewer required for VO tier
   - Senior Compliance Officer reviews
   - Both approve
   - **Audit Receipt #A8**: `KYB_APPROVED` - approvers: [reviewer1, reviewer2]

9. **Requester Activation**
   - Account status: `PENDING_KYB` → `ACTIVE`
   - Welcome email sent with portal access
   - DUA presented for signature
   - **Audit Receipt #A9**: `REQUESTER_ACTIVATED` - org_id

---

### JOURNEY B: REQUEST REVIEW & APPROVAL

**A requester submits a data request for review.**

#### Phase B1: Request Submission

10. **Request Enters Queue**
    - HealthInsights submits request for sleep-activity research
    - Automated screening runs first (see REQUESTER_USER_JOURNEY.md)
    - If auto-screening passes → Request enters manual review queue
    - **Audit Receipt #B1**: `REQUEST_QUEUED_FOR_REVIEW` - request_id

11. **Assignment**
    - Request assigned to Compliance Officer
    - Priority based on requester tier and request size
    - SLA: 24 hours for VO tier
    - **Audit Receipt #B2**: `REQUEST_ASSIGNED` - reviewer_id, sla_deadline

#### Phase B2: Manual Review

12. **Purpose Review**
    - Reviewer examines stated purpose:
      - "Academic research on sleep-activity correlation"
      - Is it legitimate? ✓
      - Is it specific enough? ✓
      - Any red flags? ✗
    - **Audit Receipt #B3**: `PURPOSE_REVIEWED` - result: ACCEPTABLE

13. **Scope Review**
    - Reviewer examines requested data:
      - Sleep duration (weekly avg) - Appropriate for purpose ✓
      - Step count (weekly avg) - Appropriate for purpose ✓
      - No sensitive categories requested ✓
    - **Audit Receipt #B4**: `SCOPE_REVIEWED` - result: ACCEPTABLE

14. **Eligibility Review**
    - Reviewer examines targeting criteria:
      - Age 18-45 - Not discriminatory ✓
      - 30+ days data - Reasonable ✓
      - Active lifestyle - Related to research ✓
      - No geographic exclusions - Fair ✓
    - **Audit Receipt #B5**: `ELIGIBILITY_REVIEWED` - result: ACCEPTABLE

15. **Compensation Review**
    - Reviewer examines payment terms:
      - 5.00 USD/week - Above minimum (2.00 USD) ✓
      - Uniform pricing - Required ✓
      - Budget adequate for scope ✓
    - **Audit Receipt #B6**: `COMPENSATION_REVIEWED` - result: ACCEPTABLE

#### Phase B3: Decision

16. **Approval Decision**
    - All checks passed
    - Reviewer clicks "Approve Request"
    - Request status: `PENDING_REVIEW` → `PENDING_FUNDING`
    - **Audit Receipt #B7**: `REQUEST_APPROVED` - reviewer_id, request_id

17. **Requester Notification**
    - HealthInsights notified: "Request approved, please fund"
    - Dashboard updated
    - **Audit Receipt #B8**: `REQUESTER_NOTIFIED` - notification_type: APPROVED

---

### JOURNEY C: REQUEST REJECTION

**A problematic request is rejected.**

#### Phase C1: Red Flag Detection

18. **Suspicious Request**
    - New request from "DataHarvest LLC"
    - Purpose: "General consumer behavior analysis"
    - Scope: Location data, browsing history, contacts
    - Eligibility: "All users"
    - **Audit Receipt #C1**: `REQUEST_FLAGGED` - flags: [vague_purpose, sensitive_scope]

19. **Detailed Review**
    - Reviewer identifies issues:
      - ❌ Purpose too vague
      - ❌ Sensitive data categories
      - ❌ No legitimate research justification
      - ❌ Requester has no research credentials
    - **Audit Receipt #C2**: `REVIEW_ISSUES_IDENTIFIED` - issues: [list]

#### Phase C2: Rejection

20. **Rejection Decision**
    - Reviewer clicks "Reject Request"
    - Selects rejection reasons:
      - `VAGUE_PURPOSE`
      - `SENSITIVE_DATA_UNJUSTIFIED`
      - `INSUFFICIENT_CREDENTIALS`
    - **Audit Receipt #C3**: `REQUEST_REJECTED` - reasons: [list]

21. **Requester Notification**
    - DataHarvest LLC notified with rejection reasons
    - Can appeal or submit revised request
    - **Audit Receipt #C4**: `REJECTION_NOTIFIED` - org_id

22. **Escalation (if needed)**
    - If requester appeals
    - Senior Compliance Officer reviews
    - Final decision made
    - **Audit Receipt #C5**: `APPEAL_REVIEWED` - result: UPHELD/OVERTURNED

---

### JOURNEY D: POLICY MANAGEMENT

**Admin team updates platform policies.**

#### Phase D1: Policy Creation

23. **New Policy Proposal**
    - Compliance team identifies need for new policy
    - Example: "Minimum compensation for health data"
    - Policy draft created
    - **Audit Receipt #D1**: `POLICY_DRAFT_CREATED` - policy_id, author

24. **Internal Review**
    - Legal team reviews policy
    - Product team assesses impact
    - Engineering confirms feasibility
    - **Audit Receipt #D2**: `POLICY_INTERNAL_REVIEW` - reviewers: [list]

25. **Approval Workflow**
    - Policy requires C-level approval
    - CEO/COO signs off
    - **Audit Receipt #D3**: `POLICY_APPROVED` - approver, effective_date

#### Phase D2: Policy Deployment

26. **Policy Activation**
    - Policy added to Screening Engine rules
    - Effective date: 2025-01-01
    - Grace period for existing requests: 30 days
    - **Audit Receipt #D4**: `POLICY_ACTIVATED` - policy_id

27. **Stakeholder Notification**
    - All requesters notified of policy change
    - Documentation updated
    - FAQ published
    - **Audit Receipt #D5**: `POLICY_COMMUNICATED` - notification_count

---

### JOURNEY E: DISPUTE RESOLUTION

**A Data Sovereign files a dispute.**

#### Phase E1: Dispute Filing

28. **Dispute Received**
    - Maria files dispute: "Did not receive payment for Week 2"
    - Dispute enters queue
    - Auto-assigned to Support Agent
    - **Audit Receipt #E1**: `DISPUTE_FILED` - dispute_id, ds_pseudonym

29. **Initial Triage**
    - Support Agent reviews:
      - Consent ID: con_maria_001
      - Request ID: req_healthinsights_001
      - Week 2 delivery status: DELIVERED
      - Payment status: PENDING
    - **Audit Receipt #E2**: `DISPUTE_TRIAGED` - category: PAYMENT_DELAY

#### Phase E2: Investigation

30. **Payment Trail Investigation**
    - Support Agent traces payment:
      - Delivery verified: ✓ (timestamp)
      - Settlement instruction created: ✓
      - Payment initiated: ✓
      - Payment confirmation: ✗ (missing)
    - **Audit Receipt #E3**: `INVESTIGATION_STARTED` - dispute_id

31. **Root Cause Identified**
    - Payment rail (Stripe) webhook failed
    - Payment actually completed but not recorded
    - Stripe dashboard confirms: PAID
    - **Audit Receipt #E4**: `ROOT_CAUSE_IDENTIFIED` - cause: WEBHOOK_FAILURE

#### Phase E3: Resolution

32. **Manual Reconciliation**
    - Support Agent manually updates payment status
    - Maria's balance updated
    - **Audit Receipt #E5**: `MANUAL_RECONCILIATION` - amount, reason

33. **Dispute Resolution**
    - Dispute status: `OPEN` → `RESOLVED`
    - Resolution: Payment confirmed, balance updated
    - Maria notified
    - **Audit Receipt #E6**: `DISPUTE_RESOLVED` - resolution_type: PAYMENT_CONFIRMED

34. **Post-Mortem**
    - Engineering notified of webhook issue
    - Monitoring improved
    - **Audit Receipt #E7**: `INCIDENT_REPORTED` - incident_id

---

### JOURNEY F: VIOLATION HANDLING

**A requester violates platform terms.**

#### Phase F1: Violation Detection

35. **Automated Detection**
    - System detects: HealthInsights attempted to export raw data
    - Clean Room output restriction triggered
    - Alert sent to Security Analyst
    - **Audit Receipt #F1**: `VIOLATION_DETECTED` - type: OUTPUT_RESTRICTION_BREACH

36. **Violation Review**
    - Security Analyst reviews:
      - What was attempted: Export of individual-level data
      - Was it blocked: Yes
      - Severity: MEDIUM (attempted, not successful)
    - **Audit Receipt #F2**: `VIOLATION_REVIEWED` - severity: MEDIUM

#### Phase F2: Enforcement

37. **Warning Issued**
    - First offense → Warning
    - HealthInsights notified:
      - "Attempted export of restricted data detected"
      - "This violates DUA Section 4.2"
      - "Repeated violations may result in suspension"
    - **Audit Receipt #F3**: `WARNING_ISSUED` - org_id, violation_type

38. **Reputation Impact**
    - Requester reputation score decreased
    - Internal flag added to account
    - Enhanced monitoring enabled
    - **Audit Receipt #F4**: `REPUTATION_UPDATED` - old_score, new_score

#### Phase F3: Escalation (if repeated)

39. **Repeated Violation**
    - Same requester attempts again
    - Escalated to Compliance Officer
    - **Audit Receipt #F5**: `VIOLATION_ESCALATED` - escalation_level: 2

40. **Suspension Decision**
    - Compliance Officer reviews history
    - Decision: Temporary suspension (30 days)
    - All active requests paused
    - **Audit Receipt #F6**: `ACCOUNT_SUSPENDED` - duration: 30_DAYS

41. **Requester Notification**
    - HealthInsights notified of suspension
    - Appeal process explained
    - **Audit Receipt #F7**: `SUSPENSION_NOTIFIED` - org_id

---

### JOURNEY G: SYSTEM MONITORING

**Admin team monitors platform health.**

#### Phase G1: Dashboard Monitoring

42. **Real-Time Metrics**
    - Platform Admin views dashboard:
      - Active requests: 1,247
      - Active consents: 89,432
      - Pending payments: 234
      - System health: GREEN
    - **Audit Receipt #G1**: `DASHBOARD_ACCESSED` - admin_id

43. **Alert Review**
    - Alerts panel shows:
      - ⚠️ Kafka lag > 1000 messages
      - ⚠️ Payment webhook latency > 5s
      - ✅ All databases healthy
    - **Audit Receipt #G2**: `ALERTS_REVIEWED` - alert_count: 2

#### Phase G2: Incident Response

44. **Incident Detection**
    - Kafka lag alert triggers
    - On-call engineer notified
    - **Audit Receipt #G3**: `INCIDENT_CREATED` - incident_id, severity: P2

45. **Incident Resolution**
    - Root cause: Consumer group rebalancing
    - Fix: Restart affected consumers
    - Lag cleared within 10 minutes
    - **Audit Receipt #G4**: `INCIDENT_RESOLVED` - resolution_time: 10m

---

### JOURNEY H: AUDIT & COMPLIANCE REPORTING

**Generating reports for regulators.**

#### Phase H1: Audit Request

46. **Regulator Request**
    - Data protection authority requests audit
    - Scope: All consents for EU users in Q4 2025
    - **Audit Receipt #H1**: `AUDIT_REQUEST_RECEIVED` - regulator, scope

47. **Report Generation**
    - Compliance Officer generates report:
      - Total EU consents: 12,456
      - Active: 8,234
      - Expired: 4,222
      - Revoked: 0
      - All blockchain-anchored: ✓
    - **Audit Receipt #H2**: `AUDIT_REPORT_GENERATED` - report_id

#### Phase H2: Report Delivery

48. **Report Review**
    - Legal team reviews before submission
    - Redactions applied where needed
    - **Audit Receipt #H3**: `REPORT_REVIEWED` - reviewer_id

49. **Report Submission**
    - Report submitted to regulator
    - Acknowledgment received
    - **Audit Receipt #H4**: `REPORT_SUBMITTED` - submission_id

---

## Admin Action Audit Trail

| Journey | Action Type | Audit Receipts |
|---------|-------------|----------------|
| A | KYB Verification | A1-A9 |
| B | Request Approval | B1-B8 |
| C | Request Rejection | C1-C5 |
| D | Policy Management | D1-D5 |
| E | Dispute Resolution | E1-E7 |
| F | Violation Handling | F1-F7 |
| G | System Monitoring | G1-G4 |
| H | Audit Reporting | H1-H4 |

---

## Admin Access Controls

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              ADMIN ACCESS MATRIX                                         │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  Action                    │ SUPPORT │ COMPLIANCE │ SECURITY │ FINANCE │ ADMIN │        │
│  ─────────────────────────────────────────────────────────────────────────────────────  │
│  View KYB applications     │    ✗    │     ✓      │    ✗     │    ✗    │   ✓   │        │
│  Approve KYB               │    ✗    │     ✓      │    ✗     │    ✗    │   ✓   │        │
│  View requests             │    ✓    │     ✓      │    ✓     │    ✗    │   ✓   │        │
│  Approve requests          │    ✗    │     ✓      │    ✗     │    ✗    │   ✓   │        │
│  Handle disputes           │    ✓    │     ✓      │    ✗     │    ✗    │   ✓   │        │
│  Issue warnings            │    ✗    │     ✓      │    ✓     │    ✗    │   ✓   │        │
│  Suspend accounts          │    ✗    │     ✓      │    ✓     │    ✗    │   ✓   │        │
│  View payments             │    ✗    │     ✗      │    ✗     │    ✓    │   ✓   │        │
│  Manual reconciliation     │    ✗    │     ✗      │    ✗     │    ✓    │   ✓   │        │
│  Manage policies           │    ✗    │     ✓      │    ✗     │    ✗    │   ✓   │        │
│  System configuration      │    ✗    │     ✗      │    ✗     │    ✗    │   ✓   │        │
│  View audit logs           │    ✓    │     ✓      │    ✓     │    ✓    │   ✓   │        │
│  Export audit reports      │    ✗    │     ✓      │    ✗     │    ✗    │   ✓   │        │
│                                                                                          │
│  ❌ NO ADMIN CAN:                                                                        │
│     • Access user raw data (we don't have it)                                            │
│     • Transfer money (we don't hold it)                                                  │
│     • Modify blockchain records (immutable)                                              │
│     • Delete audit trail (append-only)                                                   │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Admin Guarantees

1. **Full Auditability** - Every admin action is logged and blockchain-anchored
2. **Separation of Duties** - No single admin can approve high-risk actions alone
3. **No Data Access** - Admins cannot see user raw data (we never have it)
4. **No Money Access** - Admins cannot transfer funds (we never hold them)
5. **Immutable Trail** - Admin actions cannot be hidden or modified
6. **Role-Based Access** - Minimum necessary permissions per role
7. **MFA Required** - All admin access requires multi-factor authentication

---

## Related Documents
- `TESTING_WORKBENCH_REQUIREMENTS.md` - Data Sovereign journey
- `REQUESTER_USER_JOURNEY.md` - Requester journey
- `DATA_ORCHESTRATION_ARCHITECTURE.md` - Technical architecture

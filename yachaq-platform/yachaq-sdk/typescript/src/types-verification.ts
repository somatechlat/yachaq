/**
 * YACHAQ SDK Verification Types
 * 
 * Types for capsule verification and dispute resolution.
 * Validates: Requirements 352.2, 352.3
 */

// ==================== Capsule Verification ====================

export interface CapsuleData {
  capsuleId: string;
  contractId: string;
  requestId: string;
  encryptedPayload: string; // Base64 encoded
  signature: string;
  headers: Record<string, string>;
  createdAt: string;
  expiresAt: string;
}

export interface CapsuleSchema {
  schemaId: string;
  version: string;
  fields: Record<string, FieldSchema>;
  requiredFields: string[];
}

export interface FieldSchema {
  type: FieldType;
  format?: string;
  nullable: boolean;
  constraints?: Record<string, unknown>;
}

export type FieldType = 
  | 'string' 
  | 'number' 
  | 'boolean' 
  | 'array' 
  | 'object' 
  | 'date' 
  | 'datetime';

export interface HashReceipt {
  receiptId: string;
  capsuleHash: string;
  merkleRoot: string;
  merkleProof: string[];
  blockchainAnchor?: string;
  anchoredAt?: string;
}

export interface SignatureVerificationResult {
  valid: boolean;
  signerId?: string;
  algorithm?: string;
  signedAt?: string;
  errorMessage?: string;
}

export interface SchemaValidationResult {
  valid: boolean;
  violations: SchemaViolation[];
}

export interface SchemaViolation {
  field: string;
  violation: string;
  expected?: string;
  actual?: string;
}

export interface HashReceiptVerificationResult {
  valid: boolean;
  merkleProofValid: boolean;
  blockchainAnchorValid: boolean;
  errorMessage?: string;
}

export interface CompleteVerificationResult {
  valid: boolean;
  signatureResult: SignatureVerificationResult;
  schemaResult: SchemaValidationResult;
  receiptResult: HashReceiptVerificationResult;
}

// ==================== Dispute Resolution ====================

export interface DisputeRequest {
  requestId: string;
  capsuleId: string;
  reason: DisputeReason;
  description: string;
  evidenceIds?: string[];
}

export type DisputeReason = 
  | 'DATA_QUALITY' 
  | 'SCHEMA_MISMATCH' 
  | 'INCOMPLETE_DATA' 
  | 'CONSENT_VIOLATION' 
  | 'OTHER';

export interface DisputeFilingResult {
  success: boolean;
  disputeId?: string;
  status?: string;
  errorMessage?: string;
}

export interface Dispute {
  disputeId: string;
  requestId: string;
  capsuleId: string;
  requesterId: string;
  reason: DisputeReason;
  description: string;
  status: DisputeStatus;
  evidence: Evidence[];
  resolution?: string;
  filedAt: string;
  resolvedAt?: string;
}

export type DisputeStatus = 
  | 'FILED' 
  | 'UNDER_REVIEW' 
  | 'EVIDENCE_REQUESTED' 
  | 'RESOLVED' 
  | 'REJECTED';

export interface Evidence {
  evidenceId: string;
  type: EvidenceType;
  description: string;
  contentHash: string;
  submittedAt: string;
}

export type EvidenceType = 
  | 'DOCUMENT' 
  | 'SCREENSHOT' 
  | 'LOG' 
  | 'CAPSULE_EXPORT' 
  | 'OTHER';

export interface EvidenceSubmission {
  type: EvidenceType;
  description: string;
  content: string; // Base64 encoded
}

export interface EvidenceAddResult {
  success: boolean;
  evidenceId?: string;
  errorMessage?: string;
}

// ==================== Tier & Analytics ====================

export interface TierCapabilities {
  tier: RequesterTier;
  maxBudget: number;
  maxParticipants: number;
  allowedOutputModes: OutputMode[];
  exportAllowed: boolean;
  allowedCategories: string[];
}

export type RequesterTier = 
  | 'COMMUNITY' 
  | 'VERIFIED' 
  | 'ENTERPRISE';

export interface RequestTypeCheck {
  outputMode: OutputMode;
  compensation: number;
  requiredLabels: string[];
  identityReveal: boolean;
}

export interface RestrictionCheckResult {
  allowed: boolean;
  violations: string[];
  warnings: string[];
}

export interface RequesterAnalytics {
  requesterId: string;
  totalRequests: number;
  approvedRequests: number;
  rejectedRequests: number;
  pendingRequests: number;
  totalResponses: number;
  totalSpent: number;
  generatedAt: string;
}

// Re-export OutputMode for convenience
import { OutputMode } from './types';

"""
YACHAQ SDK Data Models

Pydantic models for type-safe API interactions.
Validates: Requirements 352.3
"""

from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field


# ==================== Enums ====================

class OutputMode(str, Enum):
    RAW = "RAW"
    AGGREGATE_ONLY = "AGGREGATE_ONLY"
    VIEW_ONLY = "VIEW_ONLY"
    CLEAN_ROOM = "CLEAN_ROOM"


class RequestStatusType(str, Enum):
    DRAFT = "DRAFT"
    SCREENING = "SCREENING"
    ACTIVE = "ACTIVE"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"


class ScreeningStatus(str, Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    MANUAL_REVIEW = "MANUAL_REVIEW"


class RequesterTier(str, Enum):
    COMMUNITY = "COMMUNITY"
    VERIFIED = "VERIFIED"
    ENTERPRISE = "ENTERPRISE"


class DisputeReason(str, Enum):
    DATA_QUALITY = "DATA_QUALITY"
    SCHEMA_MISMATCH = "SCHEMA_MISMATCH"
    INCOMPLETE_DATA = "INCOMPLETE_DATA"
    CONSENT_VIOLATION = "CONSENT_VIOLATION"
    OTHER = "OTHER"


class DisputeStatus(str, Enum):
    FILED = "FILED"
    UNDER_REVIEW = "UNDER_REVIEW"
    EVIDENCE_REQUESTED = "EVIDENCE_REQUESTED"
    RESOLVED = "RESOLVED"
    REJECTED = "REJECTED"


class RemediationAction(str, Enum):
    MODIFY_CRITERIA = "MODIFY_CRITERIA"
    CHANGE_OUTPUT_MODE = "CHANGE_OUTPUT_MODE"
    REDUCE_SCOPE = "REDUCE_SCOPE"
    ADD_JUSTIFICATION = "ADD_JUSTIFICATION"
    UPGRADE_TIER = "UPGRADE_TIER"


# ==================== Request Management ====================

class TimeWindow(BaseModel):
    start: datetime
    end: datetime


class GeoCriteria(BaseModel):
    precision: str = Field(..., pattern="^(CITY|REGION|COUNTRY)$")
    regions: Optional[List[str]] = None


class RequestConfig(BaseModel):
    template_id: Optional[str] = Field(None, alias="templateId")
    required_labels: List[str] = Field(..., alias="requiredLabels")
    optional_labels: Optional[List[str]] = Field(None, alias="optionalLabels")
    time_window: Optional[TimeWindow] = Field(None, alias="timeWindow")
    geo_criteria: Optional[GeoCriteria] = Field(None, alias="geoCriteria")
    compensation: Decimal
    output_mode: OutputMode = Field(..., alias="outputMode")
    ttl_hours: Optional[int] = Field(None, alias="ttlHours")

    class Config:
        populate_by_name = True


class OdxCriteria(BaseModel):
    required_labels: List[str] = Field(..., alias="requiredLabels")
    optional_labels: Optional[List[str]] = Field(None, alias="optionalLabels")
    time_window: Optional[TimeWindow] = Field(None, alias="timeWindow")
    geo_criteria: Optional[GeoCriteria] = Field(None, alias="geoCriteria")

    class Config:
        populate_by_name = True


class RemediationSuggestion(BaseModel):
    id: str
    title: str
    description: str
    action: RemediationAction


class RequestCreationResult(BaseModel):
    success: bool
    request_id: Optional[str] = Field(None, alias="requestId")
    status: Optional[str] = None
    errors: Optional[List[str]] = None
    suggestions: Optional[List[RemediationSuggestion]] = None

    class Config:
        populate_by_name = True


class RequestTemplate(BaseModel):
    id: str
    name: str
    description: str
    category: str
    default_labels: List[str] = Field(..., alias="defaultLabels")
    optional_labels: List[str] = Field(..., alias="optionalLabels")
    output_mode: OutputMode = Field(..., alias="outputMode")
    default_time_window: Optional[TimeWindow] = Field(None, alias="defaultTimeWindow")
    suggested_compensation: Decimal = Field(..., alias="suggestedCompensation")
    default_ttl_hours: int = Field(..., alias="defaultTtlHours")

    class Config:
        populate_by_name = True


class CriteriaValidationResult(BaseModel):
    valid: bool
    errors: List[str]
    warnings: List[str]
    estimated_cohort_size: int = Field(..., alias="estimatedCohortSize")

    class Config:
        populate_by_name = True


class ResponseStats(BaseModel):
    total_responses: int = Field(..., alias="totalResponses")
    completed_responses: int = Field(..., alias="completedResponses")
    pending_responses: int = Field(..., alias="pendingResponses")
    total_cost: Decimal = Field(..., alias="totalCost")

    class Config:
        populate_by_name = True


class RequestStatus(BaseModel):
    request_id: str = Field(..., alias="requestId")
    status: RequestStatusType
    screening_status: ScreeningStatus = Field(..., alias="screeningStatus")
    created_at: datetime = Field(..., alias="createdAt")
    expires_at: Optional[datetime] = Field(None, alias="expiresAt")
    response_stats: ResponseStats = Field(..., alias="responseStats")

    class Config:
        populate_by_name = True


# ==================== Capsule Verification ====================

class FieldSchema(BaseModel):
    type: str
    format: Optional[str] = None
    nullable: bool
    constraints: Optional[Dict[str, Any]] = None


class CapsuleSchema(BaseModel):
    schema_id: str = Field(..., alias="schemaId")
    version: str
    fields: Dict[str, FieldSchema]
    required_fields: List[str] = Field(..., alias="requiredFields")

    class Config:
        populate_by_name = True


class CapsuleData(BaseModel):
    capsule_id: str = Field(..., alias="capsuleId")
    contract_id: str = Field(..., alias="contractId")
    request_id: str = Field(..., alias="requestId")
    encrypted_payload: str = Field(..., alias="encryptedPayload")  # Base64
    signature: str
    headers: Dict[str, str]
    created_at: datetime = Field(..., alias="createdAt")
    expires_at: datetime = Field(..., alias="expiresAt")

    class Config:
        populate_by_name = True


class HashReceipt(BaseModel):
    receipt_id: str = Field(..., alias="receiptId")
    capsule_hash: str = Field(..., alias="capsuleHash")
    merkle_root: str = Field(..., alias="merkleRoot")
    merkle_proof: List[str] = Field(..., alias="merkleProof")
    blockchain_anchor: Optional[str] = Field(None, alias="blockchainAnchor")
    anchored_at: Optional[datetime] = Field(None, alias="anchoredAt")

    class Config:
        populate_by_name = True


class SignatureVerificationResult(BaseModel):
    valid: bool
    signer_id: Optional[str] = Field(None, alias="signerId")
    algorithm: Optional[str] = None
    signed_at: Optional[datetime] = Field(None, alias="signedAt")
    error_message: Optional[str] = Field(None, alias="errorMessage")

    class Config:
        populate_by_name = True


class SchemaViolation(BaseModel):
    field: str
    violation: str
    expected: Optional[str] = None
    actual: Optional[str] = None


class SchemaValidationResult(BaseModel):
    valid: bool
    violations: List[SchemaViolation]


class HashReceiptVerificationResult(BaseModel):
    valid: bool
    merkle_proof_valid: bool = Field(..., alias="merkleProofValid")
    blockchain_anchor_valid: bool = Field(..., alias="blockchainAnchorValid")
    error_message: Optional[str] = Field(None, alias="errorMessage")

    class Config:
        populate_by_name = True


class CompleteVerificationResult(BaseModel):
    valid: bool
    signature_result: SignatureVerificationResult = Field(..., alias="signatureResult")
    schema_result: SchemaValidationResult = Field(..., alias="schemaResult")
    receipt_result: HashReceiptVerificationResult = Field(..., alias="receiptResult")

    class Config:
        populate_by_name = True


# ==================== Dispute Resolution ====================

class Evidence(BaseModel):
    evidence_id: str = Field(..., alias="evidenceId")
    type: str
    description: str
    content_hash: str = Field(..., alias="contentHash")
    submitted_at: datetime = Field(..., alias="submittedAt")

    class Config:
        populate_by_name = True


class DisputeRequest(BaseModel):
    request_id: str = Field(..., alias="requestId")
    capsule_id: str = Field(..., alias="capsuleId")
    reason: DisputeReason
    description: str
    evidence_ids: Optional[List[str]] = Field(None, alias="evidenceIds")

    class Config:
        populate_by_name = True


class Dispute(BaseModel):
    dispute_id: str = Field(..., alias="disputeId")
    request_id: str = Field(..., alias="requestId")
    capsule_id: str = Field(..., alias="capsuleId")
    requester_id: str = Field(..., alias="requesterId")
    reason: DisputeReason
    description: str
    status: DisputeStatus
    evidence: List[Evidence]
    resolution: Optional[str] = None
    filed_at: datetime = Field(..., alias="filedAt")
    resolved_at: Optional[datetime] = Field(None, alias="resolvedAt")

    class Config:
        populate_by_name = True


# ==================== Tier & Analytics ====================

class TierCapabilities(BaseModel):
    tier: RequesterTier
    max_budget: Decimal = Field(..., alias="maxBudget")
    max_participants: int = Field(..., alias="maxParticipants")
    allowed_output_modes: List[OutputMode] = Field(..., alias="allowedOutputModes")
    export_allowed: bool = Field(..., alias="exportAllowed")
    allowed_categories: List[str] = Field(..., alias="allowedCategories")

    class Config:
        populate_by_name = True


class RestrictionCheckResult(BaseModel):
    allowed: bool
    violations: List[str]
    warnings: List[str]


class RequesterAnalytics(BaseModel):
    requester_id: str = Field(..., alias="requesterId")
    total_requests: int = Field(..., alias="totalRequests")
    approved_requests: int = Field(..., alias="approvedRequests")
    rejected_requests: int = Field(..., alias="rejectedRequests")
    pending_requests: int = Field(..., alias="pendingRequests")
    total_responses: int = Field(..., alias="totalResponses")
    total_spent: Decimal = Field(..., alias="totalSpent")
    generated_at: datetime = Field(..., alias="generatedAt")

    class Config:
        populate_by_name = True

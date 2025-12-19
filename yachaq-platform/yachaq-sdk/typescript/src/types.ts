/**
 * YACHAQ SDK Type Definitions
 * 
 * These types mirror the Java SDK models for cross-language compatibility.
 * Validates: Requirements 352.3
 */

// ==================== Response Wrapper ====================

export interface SDKResponse<T> {
  success: boolean;
  data?: T;
  errorCode?: string;
  errorMessage?: string;
  validationErrors?: string[];
}

// ==================== Authentication ====================

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
}

// ==================== Request Management ====================

export interface RequestConfig {
  templateId?: string;
  requiredLabels: string[];
  optionalLabels?: string[];
  timeWindow?: TimeWindow;
  geoCriteria?: GeoCriteria;
  compensation: number;
  outputMode: OutputMode;
  ttlHours?: number;
}

export interface TimeWindow {
  start: string; // ISO 8601 datetime
  end: string;   // ISO 8601 datetime
}

export interface GeoCriteria {
  precision: 'CITY' | 'REGION' | 'COUNTRY';
  regions?: string[];
}

export interface OdxCriteria {
  requiredLabels: string[];
  optionalLabels?: string[];
  timeWindow?: TimeWindow;
  geoCriteria?: GeoCriteria;
}

export interface RequestCreationResult {
  success: boolean;
  requestId?: string;
  status?: string;
  errors?: string[];
  suggestions?: RemediationSuggestion[];
}

export interface RemediationSuggestion {
  id: string;
  title: string;
  description: string;
  action: RemediationAction;
}

export type RemediationAction = 
  | 'MODIFY_CRITERIA' 
  | 'CHANGE_OUTPUT_MODE' 
  | 'REDUCE_SCOPE' 
  | 'ADD_JUSTIFICATION' 
  | 'UPGRADE_TIER';

export type OutputMode = 
  | 'RAW' 
  | 'AGGREGATE_ONLY' 
  | 'VIEW_ONLY' 
  | 'CLEAN_ROOM';

export interface RequestTemplate {
  id: string;
  name: string;
  description: string;
  category: string;
  defaultLabels: string[];
  optionalLabels: string[];
  outputMode: OutputMode;
  defaultTimeWindow?: TimeWindow;
  suggestedCompensation: number;
  defaultTtlHours: number;
}

export interface CriteriaValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  estimatedCohortSize: number;
}

export interface RequestStatus {
  requestId: string;
  status: RequestStatusType;
  screeningStatus: ScreeningStatus;
  createdAt: string;
  expiresAt?: string;
  responseStats: ResponseStats;
}

export type RequestStatusType = 
  | 'DRAFT' 
  | 'SCREENING' 
  | 'ACTIVE' 
  | 'COMPLETED' 
  | 'CANCELLED';

export type ScreeningStatus = 
  | 'PENDING' 
  | 'APPROVED' 
  | 'REJECTED' 
  | 'MANUAL_REVIEW';

export interface ResponseStats {
  totalResponses: number;
  completedResponses: number;
  pendingResponses: number;
  totalCost: number;
}

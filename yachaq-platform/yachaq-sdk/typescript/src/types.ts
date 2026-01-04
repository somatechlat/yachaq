/**
 * @file Contains all shared type definitions for the YACHAQ Platform TypeScript SDK.
 * @remarks These types mirror the platform's API models to ensure cross-language
 * compatibility and provide strong typing for all SDK interactions.
 * Validates: Requirements 352.3
 */

// ==================== Response Wrapper ====================

/**
 * A generic wrapper for all SDK method responses.
 * @template T The type of the data payload on success.
 */
export interface SDKResponse<T> {
  /** Indicates whether the operation was successful. */
  success: boolean;
  /** The data payload if the operation was successful. */
  data?: T;
  /** A machine-readable error code if the operation failed. */
  errorCode?: string;
  /** A human-readable error message if the operation failed. */
  errorMessage?: string;
  /** A list of validation errors, if applicable. */
  validationErrors?: string[];
}

// ==================== Authentication ====================

/**
 * Represents the authentication tokens returned upon a successful login.
 */
export interface AuthResponse {
  /** The JWT access token for making authenticated API calls. */
  accessToken: string;
  /** The refresh token used to obtain a new access token. */
  refreshToken: string;
  /** The duration in seconds until the access token expires. */
  expiresIn: number;
  /** The type of the token (e.g., 'Bearer'). */
  tokenType: string;
}

// ==================== Request Management ====================

/**
 * Defines the configuration for creating a new data request.
 */
export interface RequestConfig {
  /** An optional ID of a template to base this request on. */
  templateId?: string;
  /** The list of data labels required for this request. */
  requiredLabels: string[];
  /** An optional list of additional data labels. */
  optionalLabels?: string[];
  /** The time window for the data being requested. */
  timeWindow?: TimeWindow;
  /** The geographical criteria for the data being requested. */
  geoCriteria?: GeoCriteria;
  /** The financial compensation offered to each participating Data Subject. */
  compensation: number;
  /** The mode defining how the requested data can be accessed. */
  outputMode: OutputMode;
  /** The Time-To-Live for the request in hours. */
  ttlHours?: number;
}

/**
 * Defines a time range using ISO 8601 formatted strings.
 */
export interface TimeWindow {
  /** The start of the time window (ISO 8601 datetime string). */
  start: string;
  /** The end of the time window (ISO 8601 datetime string). */
  end: string;
}

/**
 * Defines geographical constraints for a data request.
 */
export interface GeoCriteria {
  /** The level of geographical precision required. */
  precision: 'CITY' | 'REGION' | 'COUNTRY';
  /** A list of specific regions (e.g., states, provinces) if precision is REGION. */
  regions?: string[];
}

/**
 * Represents the combined criteria for an ODX (Orchestrated Data Exchange) request.
 */
export interface OdxCriteria {
  /** The list of required data labels. */
  requiredLabels: string[];
  /** An optional list of additional data labels. */
  optionalLabels?: string[];
  /** The time window for the data. */
  timeWindow?: TimeWindow;
  /** The geographical constraints for the data. */
  geoCriteria?: GeoCriteria;
}

/**
 * The result of a data request creation attempt.
 */
export interface RequestCreationResult {
  /** Indicates if the request was successfully created or submitted for screening. */
  success: boolean;
  /** The unique identifier of the newly created request, if successful. */
  requestId?: string;
  /** The initial status of the request. */
  status?: string;
  /** A list of errors that prevented request creation. */
  errors?: string[];
  /** A list of suggestions to remediate a failed request. */
  suggestions?: RemediationSuggestion[];
}

/**
 * A suggestion to fix a data request that failed screening.
 */
export interface RemediationSuggestion {
  /** A unique identifier for the suggestion type. */
  id: string;
  /** A short title for the suggestion. */
  title: string;
  /** A detailed description of the suggested change. */
  description: string;
  /** The type of action required to apply the remediation. */
  action: RemediationAction;
}

/**
 * The type of action required to fix a failed data request.
 */
export type RemediationAction = 
  | 'MODIFY_CRITERIA' 
  | 'CHANGE_OUTPUT_MODE' 
  | 'REDUCE_SCOPE' 
  | 'ADD_JUSTIFICATION' 
  | 'UPGRADE_TIER';

/**
 * Defines the allowed access mode for the data returned by a request.
 */
export type OutputMode = 
  /** Raw data is returned directly to the requester. */
  | 'RAW' 
  /** Only aggregated results are returned; no row-level data. */
  | 'AGGREGATE_ONLY' 
  /** Data can be viewed within a secure environment but not downloaded. */
  | 'VIEW_ONLY' 
  /** Data is processed within a secure clean room environment. */
  | 'CLEAN_ROOM';

/**
 * A pre-defined template for creating common data requests.
 */
export interface RequestTemplate {
  /** The unique identifier for the template. */
  id: string;
  /** The name of the template. */
  name: string;
  /** A description of the template's purpose. */
  description: string;
  /** The category the template belongs to (e.g., 'Healthcare', 'Finance'). */
  category: string;
  /** The default set of required data labels for this template. */
  defaultLabels: string[];
  /** The default set of optional data labels for this template. */
  optionalLabels: string[];
  /** The default output mode for this template. */
  outputMode: OutputMode;
  /** The default time window, if any. */
  defaultTimeWindow?: TimeWindow;
  /** The suggested compensation amount for requests using this template. */
  suggestedCompensation: number;
  /** The default Time-To-Live in hours. */
  defaultTtlHours: number;
}

/**
 * The result of validating a set of data request criteria.
 */
export interface CriteriaValidationResult {
  /** Whether the criteria are valid. */
  valid: boolean;
  /** A list of validation errors. */
  errors: string[];
  /** A list of warnings about the criteria. */
  warnings: string[];
  /** An estimated size of the potential data subject cohort for these criteria. */
  estimatedCohortSize: number;
}

/**
 * Represents the current status and metadata of a data request.
 */
export interface RequestStatus {
  /** The unique identifier of the data request. */
  requestId: string;
  /** The overall lifecycle status of the request. */
  status: RequestStatusType;
  /** The status of the request's screening process. */
  screeningStatus: ScreeningStatus;
  /** The ISO 8601 timestamp when the request was created. */
  createdAt: string;
  /** The ISO 8601 timestamp when the request will expire, if applicable. */
  expiresAt?: string;
  /** Statistics about the responses received for this request. */
  responseStats: ResponseStats;
}

/**
 * The overall lifecycle status of a data request.
 */
export type RequestStatusType = 
  | 'DRAFT' 
  | 'SCREENING' 
  | 'ACTIVE' 
  | 'COMPLETED' 
  | 'CANCELLED';

/**
 * The status of the automated screening process for a data request.
 */
export type ScreeningStatus = 
  | 'PENDING' 
  | 'APPROVED' 
  | 'REJECTED' 
  | 'MANUAL_REVIEW';

/**
 * Statistics about the responses to a data request.
 */
export interface ResponseStats {
  /** The total number of responses received. */
  totalResponses: number;
  /** The number of responses that are considered complete. */
  completedResponses: number;
  /** The number of responses that are still pending. */
  pendingResponses: number;
  /** The total financial cost incurred so far from completed responses. */
  totalCost: number;
}

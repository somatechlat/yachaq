/**
 * @file The main client for the YACHAQ Platform TypeScript SDK.
 * @remarks This file provides a type-safe, asynchronous client for interacting
 * with the YACHAQ Requester API, handling authentication, request management,
 * and data verification.
 * Validates: Requirements 352.1, 352.2, 352.3
 */

import axios, { AxiosInstance, AxiosError } from 'axios';
import {
  SDKResponse,
  AuthResponse,
  RequestConfig,
  RequestCreationResult,
  RequestTemplate,
  OdxCriteria,
  CriteriaValidationResult,
  RequestStatus,
} from './types';
import {
  CapsuleData,
  CapsuleSchema,
  HashReceipt,
  SignatureVerificationResult,
  SchemaValidationResult,
  HashReceiptVerificationResult,
  CompleteVerificationResult,
  DisputeRequest,
  DisputeFilingResult,
  Dispute,
  EvidenceSubmission,
  EvidenceAddResult,
  TierCapabilities,
  RequestTypeCheck,
  RestrictionCheckResult,
  RequesterAnalytics,
} from './types-verification';
import {
  YachaqError,
  AuthenticationError,
  ValidationError,
  NetworkError,
  RateLimitError,
} from './errors';

/**
 * Configuration options for the YachaqClient.
 */
export interface YachaqClientConfig {
  /**
   * The base URL of the YACHAQ API.
   * @defaultValue 'https://api.yachaq.io'
   */
  baseUrl?: string;
  /** The API key used for authentication. Can be used instead of `accessToken`. */
  apiKey?: string;
  /**
   * A pre-existing access token. If provided, `authenticate()` does not need to be called.
   */
  accessToken?: string;
  /**
   * The request timeout in milliseconds.
   * @defaultValue 30000
   */
  timeout?: number;
}

/**
 * The main entry point for interacting with the YACHAQ Requester API.
 */
export class YachaqClient {
  private readonly client: AxiosInstance;
  private accessToken?: string;

  /**
   * Creates an instance of the YachaqClient.
   * @param config - The configuration for the client.
   */
  constructor(config: YachaqClientConfig = {}) {
    const baseURL = config.baseUrl || 'https://api.yachaq.io';
    this.accessToken = config.accessToken;

    this.client = axios.create({
      baseURL,
      timeout: config.timeout || 30000,
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    });

    // Request interceptor to add the Authorization header to every request.
    this.client.interceptors.request.use((reqConfig) => {
      if (this.accessToken) {
        reqConfig.headers.Authorization = `Bearer ${this.accessToken}`;
      }
      return reqConfig;
    });

    // Response interceptor to centralize API error handling.
    this.client.interceptors.response.use(
      (response) => response,
      (error: AxiosError) => this.handleError(error)
    );
  }

  // ==================== Authentication ====================

  /**
   * Sets or updates the access token used for subsequent authenticated requests.
   * @param token - The JWT access token.
   */
  setAccessToken(token: string): void {
    this.accessToken = token;
  }

  /**
   * Authenticates with the YACHAQ API using an API key to obtain an access token.
   * The retrieved token is automatically stored and used for subsequent requests.
   * @param apiKey - The API key for your requester account.
   * @returns A promise that resolves with the authentication response.
   * @throws {AuthenticationError} If authentication fails.
   */
  async authenticate(apiKey: string): Promise<AuthResponse> {
    const response = await this.client.post<SDKResponse<AuthResponse>>(
      '/v1/auth/token',
      { apiKey }
    );
    if (response.data.success && response.data.data) {
      this.accessToken = response.data.data.accessToken;
      return response.data.data;
    }
    throw new AuthenticationError(
      response.data.errorMessage || 'Authentication failed'
    );
  }


  // ==================== Request Management ====================

  /**
   * Creates a new data request on the YACHAQ platform.
   * @remarks Implements Requirement 352.1: Provide programmatic request creation.
   * @param config - The configuration object for the new data request.
   * @returns A promise that resolves with the result of the creation attempt.
   * @throws {ValidationError} If the request configuration is invalid.
   * @throws {YachaqError} For other server-side errors.
   */
  async createRequest(config: RequestConfig): Promise<RequestCreationResult> {
    const response = await this.client.post<SDKResponse<RequestCreationResult>>(
      '/v1/requests',
      config
    );
    return this.unwrap(response.data);
  }

  /**
   * Creates multiple data requests in a single batch operation.
   * @param configs - An array of request configuration objects.
   * @returns A promise that resolves with an array of creation results.
   */
  async createRequestsBatch(configs: RequestConfig[]): Promise<RequestCreationResult[]> {
    const response = await this.client.post<SDKResponse<RequestCreationResult[]>>(
      '/v1/requests/batch',
      configs
    );
    return this.unwrap(response.data);
  }

  /**
   * Retrieves a list of available request templates.
   * @param category - Optional category to filter templates.
   * @returns A promise that resolves with an array of request templates.
   */
  async getTemplates(category?: string): Promise<RequestTemplate[]> {
    const params = category ? { category } : {};
    const response = await this.client.get<SDKResponse<RequestTemplate[]>>(
      '/v1/templates',
      { params }
    );
    return this.unwrap(response.data);
  }

  /**
   * Validates a set of ODX (Orchestrated Data Exchange) criteria without creating a request.
   * This is useful for checking potential cohort size and validity before submission.
   * @param criteria - The ODX criteria to validate.
   * @returns A promise that resolves with the validation result.
   */
  async validateCriteria(criteria: OdxCriteria): Promise<CriteriaValidationResult> {
    const response = await this.client.post<SDKResponse<CriteriaValidationResult>>(
      '/v1/criteria/validate',
      criteria
    );
    return this.unwrap(response.data);
  }

  /**
   * Retrieves the current status of a specific data request.
   * @param requestId - The unique identifier of the request.
   * @returns A promise that resolves with the request's status information.
   */
  async getRequestStatus(requestId: string): Promise<RequestStatus> {
    const response = await this.client.get<SDKResponse<RequestStatus>>(
      `/v1/requests/${requestId}/status`
    );
    return this.unwrap(response.data);
  }

  // ==================== Capsule Verification ====================

  /**
   * Verifies the digital signature of a data capsule to ensure its authenticity.
   * @remarks Implements Requirement 352.2: Provide verification functions.
   * @param capsule - The data capsule to verify.
   * @returns A promise that resolves with the signature verification result.
   */
  async verifySignature(capsule: CapsuleData): Promise<SignatureVerificationResult> {
    const response = await this.client.post<SDKResponse<SignatureVerificationResult>>(
      '/v1/capsules/verify/signature',
      capsule
    );
    return this.unwrap(response.data);
  }

  /**
   * Validates the structure and content of a data capsule against a provided schema.
   * @param capsule - The data capsule to validate.
   * @param schema - The schema to validate against.
   * @returns A promise that resolves with the schema validation result.
   */
  async validateSchema(
    capsule: CapsuleData,
    schema: CapsuleSchema
  ): Promise<SchemaValidationResult> {
    const response = await this.client.post<SDKResponse<SchemaValidationResult>>(
      '/v1/capsules/verify/schema',
      { capsule, schema }
    );
    return this.unwrap(response.data);
  }

  /**
   * Verifies a hash receipt against a data capsule to ensure data integrity.
   * @param capsule - The data capsule to verify.
   * @param receipt - The hash receipt to verify against the capsule.
   * @returns A promise that resolves with the hash receipt verification result.
   */
  async verifyHashReceipt(
    capsule: CapsuleData,
    receipt: HashReceipt
  ): Promise<HashReceiptVerificationResult> {
    const response = await this.client.post<SDKResponse<HashReceiptVerificationResult>>(
      '/v1/capsules/verify/receipt',
      { capsule, receipt }
    );
    return this.unwrap(response.data);
  }

  /**
   * Performs a complete, all-in-one verification of a data capsule, including
   * signature, schema, and hash receipt checks.
   * @param capsule - The data capsule to verify.
   * @param schema - The schema to validate against.
   * @param receipt - The hash receipt to verify against the capsule.
   * @returns A promise that resolves with the complete verification result.
   */
  async verifyComplete(
    capsule: CapsuleData,
    schema: CapsuleSchema,
    receipt: HashReceipt
  ): Promise<CompleteVerificationResult> {
    const response = await this.client.post<SDKResponse<CompleteVerificationResult>>(
      '/v1/capsules/verify/complete',
      { capsule, schema, receipt }
    );
    return this.unwrap(response.data);
  }

  // ==================== Dispute Resolution ====================

  /**
   * Files a new dispute for a data capsule or transaction.
   * @param request - The dispute request details.
   * @returns A promise that resolves with the result of the filing attempt.
   */
  async fileDispute(request: DisputeRequest): Promise<DisputeFilingResult> {
    const response = await this.client.post<SDKResponse<DisputeFilingResult>>(
      '/v1/disputes',
      request
    );
    return this.unwrap(response.data);
  }

  /**
   * Retrieves the details and current status of a specific dispute.
   * @param disputeId - The unique identifier of the dispute.
   * @returns A promise that resolves with the dispute details.
   */
  async getDispute(disputeId: string): Promise<Dispute> {
    const response = await this.client.get<SDKResponse<Dispute>>(
      `/v1/disputes/${disputeId}`
    );
    return this.unwrap(response.data);
  }

  /**
   * Adds evidence to an existing dispute.
   * @param disputeId - The unique identifier of the dispute to add evidence to.
   * @param evidence - The evidence to be submitted.
   * @returns A promise that resolves with the result of the evidence submission.
   */
  async addEvidence(
    disputeId: string,
    evidence: EvidenceSubmission
  ): Promise<EvidenceAddResult> {
    const response = await this.client.post<SDKResponse<EvidenceAddResult>>(
      `/v1/disputes/${disputeId}/evidence`,
      evidence
    );
    return this.unwrap(response.data);
  }

  // ==================== Tier & Analytics ====================

  /**
   * Retrieves the capabilities and limits associated with the requester's current tier.
   * @returns A promise that resolves with the tier capabilities.
   */
  async getTierCapabilities(): Promise<TierCapabilities> {
    const response = await this.client.get<SDKResponse<TierCapabilities>>(
      '/v1/requester/tier'
    );
    return this.unwrap(response.data);
  }

  /**
   * Checks if a given request type and configuration are permitted under the requester's current tier.
   * @param check - The request parameters to check against tier restrictions.
   * @returns A promise that resolves with the result of the restriction check.
   */
  async checkRestrictions(check: RequestTypeCheck): Promise<RestrictionCheckResult> {
    const response = await this.client.post<SDKResponse<RestrictionCheckResult>>(
      '/v1/requester/restrictions/check',
      check
    );
    return this.unwrap(response.data);
  }

  /**
   * Retrieves analytics and usage statistics for the requester account.
   * @returns A promise that resolves with requester analytics data.
   */
  async getAnalytics(): Promise<RequesterAnalytics> {
    const response = await this.client.get<SDKResponse<RequesterAnalytics>>(
      '/v1/requester/analytics'
    );
    return this.unwrap(response.data);
  }

  // ==================== Private Helpers ====================

  /**
   * Unwraps the data from an SDKResponse object, or throws an appropriate error if the request failed.
   * @param response - The SDKResponse to unwrap.
   * @returns The data payload `T` if successful.
   * @throws {ValidationError} If the response indicates validation errors.
   * @throws {YachaqError} For other generic API errors.
   * @private
   */
  private unwrap<T>(response: SDKResponse<T>): T {
    if (response.success && response.data !== undefined) {
      return response.data;
    }
    if (response.validationErrors && response.validationErrors.length > 0) {
      throw new ValidationError(
        response.errorMessage || 'Validation failed',
        response.validationErrors
      );
    }
    throw new YachaqError(
      response.errorMessage || 'Request failed',
      response.errorCode || 'UNKNOWN_ERROR'
    );
  }

  /**
   * Handles errors from the Axios client, converting them into specific SDK error types.
   * @param error - The AxiosError thrown by the interceptor.
   * @throws {AuthenticationError} For 401 status codes.
   * @throws {RateLimitError} For 429 status codes.
   * @throws {YachaqError} For other API-related errors.
   * @throws {NetworkError} For network-level issues.
   * @private
   */
  private handleError(error: AxiosError): never {
    if (error.response) {
      const status = error.response.status;
      const data = error.response.data as SDKResponse<unknown> | undefined;

      if (status === 401) {
        throw new AuthenticationError('Authentication required or token expired');
      }
      if (status === 429) {
        const retryAfter = parseInt(
          error.response.headers['retry-after'] || '60',
          10
        );
        throw new RateLimitError('Rate limit exceeded', retryAfter);
      }
      if (data?.errorMessage) {
        throw new YachaqError(data.errorMessage, data.errorCode || 'API_ERROR');
      }
    }
    throw new NetworkError(error.message || 'Network error occurred');
  }
}

/**
 * YACHAQ Platform SDK Client for TypeScript/JavaScript
 * 
 * Provides a type-safe, async client for the YACHAQ Requester API.
 * 
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

export interface YachaqClientConfig {
  baseUrl?: string;
  apiKey?: string;
  accessToken?: string;
  timeout?: number;
}

export class YachaqClient {
  private readonly client: AxiosInstance;
  private accessToken?: string;

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

    // Request interceptor for auth
    this.client.interceptors.request.use((reqConfig) => {
      if (this.accessToken) {
        reqConfig.headers.Authorization = `Bearer ${this.accessToken}`;
      }
      return reqConfig;
    });

    // Response interceptor for error handling
    this.client.interceptors.response.use(
      (response) => response,
      (error: AxiosError) => this.handleError(error)
    );
  }

  // ==================== Authentication ====================

  /**
   * Sets the access token for authenticated requests.
   */
  setAccessToken(token: string): void {
    this.accessToken = token;
  }

  /**
   * Authenticates using API key and returns an access token.
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
   * Creates a new data request.
   * Requirement 352.1: Provide programmatic request creation.
   */
  async createRequest(config: RequestConfig): Promise<RequestCreationResult> {
    const response = await this.client.post<SDKResponse<RequestCreationResult>>(
      '/v1/requests',
      config
    );
    return this.unwrap(response.data);
  }

  /**
   * Creates multiple requests in batch.
   */
  async createRequestsBatch(configs: RequestConfig[]): Promise<RequestCreationResult[]> {
    const response = await this.client.post<SDKResponse<RequestCreationResult[]>>(
      '/v1/requests/batch',
      configs
    );
    return this.unwrap(response.data);
  }

  /**
   * Gets available request templates.
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
   * Validates ODX criteria without creating a request.
   */
  async validateCriteria(criteria: OdxCriteria): Promise<CriteriaValidationResult> {
    const response = await this.client.post<SDKResponse<CriteriaValidationResult>>(
      '/v1/criteria/validate',
      criteria
    );
    return this.unwrap(response.data);
  }

  /**
   * Gets request status.
   */
  async getRequestStatus(requestId: string): Promise<RequestStatus> {
    const response = await this.client.get<SDKResponse<RequestStatus>>(
      `/v1/requests/${requestId}/status`
    );
    return this.unwrap(response.data);
  }

  // ==================== Capsule Verification ====================

  /**
   * Verifies a capsule's signature.
   * Requirement 352.2: Provide verification functions.
   */
  async verifySignature(capsule: CapsuleData): Promise<SignatureVerificationResult> {
    const response = await this.client.post<SDKResponse<SignatureVerificationResult>>(
      '/v1/capsules/verify/signature',
      capsule
    );
    return this.unwrap(response.data);
  }

  /**
   * Validates a capsule against its schema.
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
   * Verifies hash receipts for a capsule.
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
   * Performs complete verification of a capsule.
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
   * Files a dispute.
   */
  async fileDispute(request: DisputeRequest): Promise<DisputeFilingResult> {
    const response = await this.client.post<SDKResponse<DisputeFilingResult>>(
      '/v1/disputes',
      request
    );
    return this.unwrap(response.data);
  }

  /**
   * Gets dispute details.
   */
  async getDispute(disputeId: string): Promise<Dispute> {
    const response = await this.client.get<SDKResponse<Dispute>>(
      `/v1/disputes/${disputeId}`
    );
    return this.unwrap(response.data);
  }

  /**
   * Adds evidence to a dispute.
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
   * Gets requester tier capabilities.
   */
  async getTierCapabilities(): Promise<TierCapabilities> {
    const response = await this.client.get<SDKResponse<TierCapabilities>>(
      '/v1/requester/tier'
    );
    return this.unwrap(response.data);
  }

  /**
   * Checks tier restrictions for a request type.
   */
  async checkRestrictions(check: RequestTypeCheck): Promise<RestrictionCheckResult> {
    const response = await this.client.post<SDKResponse<RestrictionCheckResult>>(
      '/v1/requester/restrictions/check',
      check
    );
    return this.unwrap(response.data);
  }

  /**
   * Gets requester analytics.
   */
  async getAnalytics(): Promise<RequesterAnalytics> {
    const response = await this.client.get<SDKResponse<RequesterAnalytics>>(
      '/v1/requester/analytics'
    );
    return this.unwrap(response.data);
  }

  // ==================== Private Helpers ====================

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

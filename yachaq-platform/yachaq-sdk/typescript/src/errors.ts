/**
 * YACHAQ SDK Error Classes
 * 
 * Custom error types for better error handling in SDK consumers.
 */

export class YachaqError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly details?: Record<string, unknown>
  ) {
    super(message);
    this.name = 'YachaqError';
  }
}

export class AuthenticationError extends YachaqError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'AUTH_ERROR', details);
    this.name = 'AuthenticationError';
  }
}

export class ValidationError extends YachaqError {
  constructor(
    message: string,
    public readonly validationErrors: string[],
    details?: Record<string, unknown>
  ) {
    super(message, 'VALIDATION_ERROR', details);
    this.name = 'ValidationError';
  }
}

export class TierRestrictionError extends YachaqError {
  constructor(
    message: string,
    public readonly violations: string[],
    details?: Record<string, unknown>
  ) {
    super(message, 'TIER_RESTRICTION', details);
    this.name = 'TierRestrictionError';
  }
}

export class VerificationError extends YachaqError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'VERIFICATION_ERROR', details);
    this.name = 'VerificationError';
  }
}

export class NetworkError extends YachaqError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'NETWORK_ERROR', details);
    this.name = 'NetworkError';
  }
}

export class RateLimitError extends YachaqError {
  constructor(
    message: string,
    public readonly retryAfter: number,
    details?: Record<string, unknown>
  ) {
    super(message, 'RATE_LIMIT', details);
    this.name = 'RateLimitError';
  }
}

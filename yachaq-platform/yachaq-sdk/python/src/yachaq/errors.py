"""
YACHAQ SDK Error Classes

Custom exception types for better error handling.
"""

from typing import Optional, List, Dict, Any


class YachaqError(Exception):
    """Base exception for YACHAQ SDK errors."""

    def __init__(
        self,
        message: str,
        code: str = "UNKNOWN_ERROR",
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message)
        self.code = code
        self.details = details or {}


class AuthenticationError(YachaqError):
    """Raised when authentication fails."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, "AUTH_ERROR", details)


class ValidationError(YachaqError):
    """Raised when request validation fails."""

    def __init__(
        self,
        message: str,
        validation_errors: List[str],
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, "VALIDATION_ERROR", details)
        self.validation_errors = validation_errors


class TierRestrictionError(YachaqError):
    """Raised when a tier restriction is violated."""

    def __init__(
        self,
        message: str,
        violations: List[str],
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, "TIER_RESTRICTION", details)
        self.violations = violations


class VerificationError(YachaqError):
    """Raised when capsule verification fails."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, "VERIFICATION_ERROR", details)


class NetworkError(YachaqError):
    """Raised when a network error occurs."""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, "NETWORK_ERROR", details)


class RateLimitError(YachaqError):
    """Raised when rate limit is exceeded."""

    def __init__(
        self,
        message: str,
        retry_after: int,
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, "RATE_LIMIT", details)
        self.retry_after = retry_after

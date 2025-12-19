"""
YACHAQ Platform SDK for Python

Provides a type-safe client for interacting with the YACHAQ Requester API.

Example:
    >>> from yachaq import YachaqClient
    >>> 
    >>> client = YachaqClient(
    ...     base_url="https://api.yachaq.io",
    ...     api_key="your-api-key"
    ... )
    >>> 
    >>> # Create a request
    >>> result = await client.create_request(
    ...     required_labels=["health:steps", "health:sleep"],
    ...     compensation=5.00,
    ...     output_mode="AGGREGATE_ONLY"
    ... )

Validates: Requirements 352.1, 352.2, 352.3
"""

from .client import YachaqClient
from .models import (
    RequestConfig,
    RequestCreationResult,
    RequestTemplate,
    OdxCriteria,
    CriteriaValidationResult,
    RequestStatus,
    CapsuleData,
    CapsuleSchema,
    HashReceipt,
    SignatureVerificationResult,
    SchemaValidationResult,
    CompleteVerificationResult,
    DisputeRequest,
    Dispute,
    TierCapabilities,
    RequesterAnalytics,
)
from .errors import (
    YachaqError,
    AuthenticationError,
    ValidationError,
    TierRestrictionError,
    VerificationError,
    NetworkError,
    RateLimitError,
)

__version__ = "1.0.0"
__all__ = [
    "YachaqClient",
    "RequestConfig",
    "RequestCreationResult",
    "RequestTemplate",
    "OdxCriteria",
    "CriteriaValidationResult",
    "RequestStatus",
    "CapsuleData",
    "CapsuleSchema",
    "HashReceipt",
    "SignatureVerificationResult",
    "SchemaValidationResult",
    "CompleteVerificationResult",
    "DisputeRequest",
    "Dispute",
    "TierCapabilities",
    "RequesterAnalytics",
    "YachaqError",
    "AuthenticationError",
    "ValidationError",
    "TierRestrictionError",
    "VerificationError",
    "NetworkError",
    "RateLimitError",
]

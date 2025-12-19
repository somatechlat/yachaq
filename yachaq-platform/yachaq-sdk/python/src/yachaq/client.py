"""
YACHAQ Platform SDK Client for Python

Provides an async client for the YACHAQ Requester API.

Validates: Requirements 352.1, 352.2, 352.3
"""

from typing import Optional, List, Dict, Any, TypeVar, Generic
import httpx
from pydantic import BaseModel

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
    HashReceiptVerificationResult,
    CompleteVerificationResult,
    DisputeRequest,
    Dispute,
    TierCapabilities,
    RestrictionCheckResult,
    RequesterAnalytics,
)
from .errors import (
    YachaqError,
    AuthenticationError,
    ValidationError,
    NetworkError,
    RateLimitError,
)


T = TypeVar("T")


class SDKResponse(BaseModel, Generic[T]):
    """Generic response wrapper."""
    success: bool
    data: Optional[T] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    validation_errors: Optional[List[str]] = None


class AuthResponse(BaseModel):
    """Authentication response."""
    access_token: str
    refresh_token: str
    expires_in: int
    token_type: str


class YachaqClient:
    """
    YACHAQ Platform SDK Client.
    
    Provides async methods for interacting with the YACHAQ Requester API.
    
    Example:
        >>> async with YachaqClient(base_url="https://api.yachaq.io") as client:
        ...     await client.authenticate("your-api-key")
        ...     result = await client.create_request(
        ...         RequestConfig(
        ...             required_labels=["health:steps"],
        ...             compensation=5.00,
        ...             output_mode="AGGREGATE_ONLY"
        ...         )
        ...     )
    """

    def __init__(
        self,
        base_url: str = "https://api.yachaq.io",
        api_key: Optional[str] = None,
        access_token: Optional[str] = None,
        timeout: float = 30.0,
    ):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self._access_token = access_token
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=timeout,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )

    async def __aenter__(self) -> "YachaqClient":
        return self

    async def __aexit__(self, *args) -> None:
        await self.close()

    async def close(self) -> None:
        """Close the HTTP client."""
        await self._client.aclose()

    def set_access_token(self, token: str) -> None:
        """Set the access token for authenticated requests."""
        self._access_token = token

    def _get_headers(self) -> Dict[str, str]:
        """Get request headers with authentication."""
        headers = {}
        if self._access_token:
            headers["Authorization"] = f"Bearer {self._access_token}"
        return headers

    # ==================== Authentication ====================

    async def authenticate(self, api_key: Optional[str] = None) -> AuthResponse:
        """
        Authenticate using API key and return an access token.
        """
        key = api_key or self.api_key
        if not key:
            raise AuthenticationError("API key is required")

        response = await self._client.post(
            "/v1/auth/token",
            json={"apiKey": key},
        )
        data = response.json()

        if data.get("success") and data.get("data"):
            auth = AuthResponse(**data["data"])
            self._access_token = auth.access_token
            return auth

        raise AuthenticationError(
            data.get("errorMessage", "Authentication failed")
        )


    # ==================== Request Management ====================

    async def create_request(self, config: RequestConfig) -> RequestCreationResult:
        """
        Create a new data request.
        Requirement 352.1: Provide programmatic request creation.
        """
        response = await self._client.post(
            "/v1/requests",
            json=config.model_dump(by_alias=True, exclude_none=True),
            headers=self._get_headers(),
        )
        return self._handle_response(response, RequestCreationResult)

    async def create_requests_batch(
        self, configs: List[RequestConfig]
    ) -> List[RequestCreationResult]:
        """Create multiple requests in batch."""
        response = await self._client.post(
            "/v1/requests/batch",
            json=[c.model_dump(by_alias=True, exclude_none=True) for c in configs],
            headers=self._get_headers(),
        )
        data = self._handle_response_raw(response)
        return [RequestCreationResult(**r) for r in data]

    async def get_templates(
        self, category: Optional[str] = None
    ) -> List[RequestTemplate]:
        """Get available request templates."""
        params = {"category": category} if category else {}
        response = await self._client.get(
            "/v1/templates",
            params=params,
            headers=self._get_headers(),
        )
        data = self._handle_response_raw(response)
        return [RequestTemplate(**t) for t in data]

    async def validate_criteria(
        self, criteria: OdxCriteria
    ) -> CriteriaValidationResult:
        """Validate ODX criteria without creating a request."""
        response = await self._client.post(
            "/v1/criteria/validate",
            json=criteria.model_dump(by_alias=True, exclude_none=True),
            headers=self._get_headers(),
        )
        return self._handle_response(response, CriteriaValidationResult)

    async def get_request_status(self, request_id: str) -> RequestStatus:
        """Get request status."""
        response = await self._client.get(
            f"/v1/requests/{request_id}/status",
            headers=self._get_headers(),
        )
        return self._handle_response(response, RequestStatus)

    # ==================== Capsule Verification ====================

    async def verify_signature(
        self, capsule: CapsuleData
    ) -> SignatureVerificationResult:
        """
        Verify a capsule's signature.
        Requirement 352.2: Provide verification functions.
        """
        response = await self._client.post(
            "/v1/capsules/verify/signature",
            json=capsule.model_dump(by_alias=True, exclude_none=True),
            headers=self._get_headers(),
        )
        return self._handle_response(response, SignatureVerificationResult)

    async def validate_schema(
        self, capsule: CapsuleData, schema: CapsuleSchema
    ) -> SchemaValidationResult:
        """Validate a capsule against its schema."""
        response = await self._client.post(
            "/v1/capsules/verify/schema",
            json={
                "capsule": capsule.model_dump(by_alias=True, exclude_none=True),
                "schema": schema.model_dump(by_alias=True, exclude_none=True),
            },
            headers=self._get_headers(),
        )
        return self._handle_response(response, SchemaValidationResult)

    async def verify_hash_receipt(
        self, capsule: CapsuleData, receipt: HashReceipt
    ) -> HashReceiptVerificationResult:
        """Verify hash receipts for a capsule."""
        response = await self._client.post(
            "/v1/capsules/verify/receipt",
            json={
                "capsule": capsule.model_dump(by_alias=True, exclude_none=True),
                "receipt": receipt.model_dump(by_alias=True, exclude_none=True),
            },
            headers=self._get_headers(),
        )
        return self._handle_response(response, HashReceiptVerificationResult)

    async def verify_complete(
        self,
        capsule: CapsuleData,
        schema: CapsuleSchema,
        receipt: HashReceipt,
    ) -> CompleteVerificationResult:
        """Perform complete verification of a capsule."""
        response = await self._client.post(
            "/v1/capsules/verify/complete",
            json={
                "capsule": capsule.model_dump(by_alias=True, exclude_none=True),
                "schema": schema.model_dump(by_alias=True, exclude_none=True),
                "receipt": receipt.model_dump(by_alias=True, exclude_none=True),
            },
            headers=self._get_headers(),
        )
        return self._handle_response(response, CompleteVerificationResult)

    # ==================== Dispute Resolution ====================

    async def file_dispute(self, request: DisputeRequest) -> Dict[str, Any]:
        """File a dispute."""
        response = await self._client.post(
            "/v1/disputes",
            json=request.model_dump(by_alias=True, exclude_none=True),
            headers=self._get_headers(),
        )
        return self._handle_response_raw(response)

    async def get_dispute(self, dispute_id: str) -> Dispute:
        """Get dispute details."""
        response = await self._client.get(
            f"/v1/disputes/{dispute_id}",
            headers=self._get_headers(),
        )
        return self._handle_response(response, Dispute)

    # ==================== Tier & Analytics ====================

    async def get_tier_capabilities(self) -> TierCapabilities:
        """Get requester tier capabilities."""
        response = await self._client.get(
            "/v1/requester/tier",
            headers=self._get_headers(),
        )
        return self._handle_response(response, TierCapabilities)

    async def check_restrictions(
        self, output_mode: str, compensation: float, labels: List[str]
    ) -> RestrictionCheckResult:
        """Check tier restrictions for a request type."""
        response = await self._client.post(
            "/v1/requester/restrictions/check",
            json={
                "outputMode": output_mode,
                "compensation": compensation,
                "requiredLabels": labels,
                "identityReveal": False,
            },
            headers=self._get_headers(),
        )
        return self._handle_response(response, RestrictionCheckResult)

    async def get_analytics(self) -> RequesterAnalytics:
        """Get requester analytics."""
        response = await self._client.get(
            "/v1/requester/analytics",
            headers=self._get_headers(),
        )
        return self._handle_response(response, RequesterAnalytics)

    # ==================== Private Helpers ====================

    def _handle_response(self, response: httpx.Response, model_class: type[T]) -> T:
        """Handle API response and return typed model."""
        self._check_status(response)
        data = response.json()

        if data.get("success") and data.get("data"):
            return model_class(**data["data"])

        if data.get("validationErrors"):
            raise ValidationError(
                data.get("errorMessage", "Validation failed"),
                data["validationErrors"],
            )

        raise YachaqError(
            data.get("errorMessage", "Request failed"),
            data.get("errorCode", "UNKNOWN_ERROR"),
        )

    def _handle_response_raw(self, response: httpx.Response) -> Any:
        """Handle API response and return raw data."""
        self._check_status(response)
        data = response.json()

        if data.get("success") and data.get("data") is not None:
            return data["data"]

        raise YachaqError(
            data.get("errorMessage", "Request failed"),
            data.get("errorCode", "UNKNOWN_ERROR"),
        )

    def _check_status(self, response: httpx.Response) -> None:
        """Check HTTP status and raise appropriate errors."""
        if response.status_code == 401:
            raise AuthenticationError("Authentication required or token expired")
        if response.status_code == 429:
            retry_after = int(response.headers.get("Retry-After", "60"))
            raise RateLimitError("Rate limit exceeded", retry_after)
        if response.status_code >= 500:
            raise NetworkError(f"Server error: {response.status_code}")

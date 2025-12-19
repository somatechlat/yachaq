# YACHAQ Platform SDK

Multi-language SDK for the YACHAQ Requester API. Provides type-safe clients for creating data requests, verifying capsules, and managing disputes.

## Supported Languages

| Language | Package | Status |
|----------|---------|--------|
| Java | `com.yachaq:yachaq-sdk` | ✅ Production |
| TypeScript/JavaScript | `@yachaq/sdk` | ✅ Production |
| Python | `yachaq-sdk` | ✅ Production |

## Features

- **Request Creation** - Create and manage data requests programmatically
- **Capsule Verification** - Verify signatures, schemas, and hash receipts
- **Dispute Resolution** - File and manage disputes
- **Tier Management** - Check capabilities and restrictions
- **Analytics** - Access requester analytics

## Quick Start

### Java

```java
import com.yachaq.sdk.YachaqClient;

var client = YachaqClient.builder()
    .baseUrl("https://api.yachaq.io")
    .apiKey("your-api-key")
    .build();

// Authenticate
var auth = client.authenticate();
client.setAccessToken(auth.data().accessToken());

// Create a request
var result = client.createRequest(new RequestConfig(
    null, // templateId
    Set.of("health:steps", "health:sleep"),
    Set.of(),
    new TimeWindow(Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS)),
    null,
    new BigDecimal("5.00"),
    "AGGREGATE_ONLY",
    24
));
```

### TypeScript

```typescript
import { YachaqClient } from '@yachaq/sdk';

const client = new YachaqClient({
  baseUrl: 'https://api.yachaq.io',
});

// Authenticate
await client.authenticate('your-api-key');

// Create a request
const result = await client.createRequest({
  requiredLabels: ['health:steps', 'health:sleep'],
  compensation: 5.00,
  outputMode: 'AGGREGATE_ONLY',
  ttlHours: 24,
});
```

### Python

```python
from yachaq import YachaqClient, RequestConfig, OutputMode

async with YachaqClient(base_url="https://api.yachaq.io") as client:
    # Authenticate
    await client.authenticate("your-api-key")
    
    # Create a request
    result = await client.create_request(
        RequestConfig(
            required_labels=["health:steps", "health:sleep"],
            compensation=5.00,
            output_mode=OutputMode.AGGREGATE_ONLY,
            ttl_hours=24,
        )
    )
```

## API Reference

### Request Management

| Method | Description |
|--------|-------------|
| `createRequest(config)` | Create a new data request |
| `createRequestsBatch(configs)` | Create multiple requests |
| `getTemplates(category?)` | Get available templates |
| `validateCriteria(criteria)` | Validate ODX criteria |
| `getRequestStatus(requestId)` | Get request status |

### Capsule Verification

| Method | Description |
|--------|-------------|
| `verifySignature(capsule)` | Verify capsule signature |
| `validateSchema(capsule, schema)` | Validate against schema |
| `verifyHashReceipt(capsule, receipt)` | Verify hash receipt |
| `verifyComplete(capsule, schema, receipt)` | Complete verification |

### Dispute Resolution

| Method | Description |
|--------|-------------|
| `fileDispute(request)` | File a new dispute |
| `getDispute(disputeId)` | Get dispute details |
| `addEvidence(disputeId, evidence)` | Add evidence |

### Tier & Analytics

| Method | Description |
|--------|-------------|
| `getTierCapabilities()` | Get tier capabilities |
| `checkRestrictions(check)` | Check tier restrictions |
| `getAnalytics()` | Get requester analytics |

## Error Handling

All SDKs provide typed error classes:

- `AuthenticationError` - Authentication failures
- `ValidationError` - Request validation failures
- `TierRestrictionError` - Tier restriction violations
- `VerificationError` - Capsule verification failures
- `NetworkError` - Network/connectivity issues
- `RateLimitError` - Rate limit exceeded

## Requirements

- **Java**: JDK 21+
- **TypeScript**: Node.js 18+
- **Python**: Python 3.9+

## License

MIT License - See LICENSE file for details.

## Support

- Documentation: https://docs.yachaq.io/sdk
- Issues: https://github.com/yachaq/yachaq-sdk/issues
- Email: sdk@yachaq.io

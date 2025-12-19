# PlanVM Operator Semantics Documentation

## Overview

The PlanVM (QueryPlan Virtual Machine) executes data transformation plans in a sandboxed environment.
This document provides complete operator semantics for all allowed operators.

**Requirements Validated:** 356.1, 315.1, 315.2

## Operator Allowlist

The following operators are allowed in QueryPlans. Any operator not in this list is rejected.

| Operator | Name | Description | Privacy Impact |
|----------|------|-------------|----------------|
| SELECT | select | Select specific records | Low (1) |
| FILTER | filter | Filter records by criteria | Low (1) |
| PROJECT | project | Project specific fields | Medium (2) |
| BUCKETIZE | bucketize | Bucket values into ranges | Low (1) |
| AGGREGATE | aggregate | Aggregate values (sum, count, avg) | Low (1) |
| CLUSTER_REF | cluster_ref | Reference cluster ID (not raw content) | Low (1) |
| REDACT | redact | Redact sensitive fields | Negative (-1) |
| SAMPLE | sample | Sample a subset of records | None (0) |
| EXPORT | export | Export results (if allowed) | High (5) |
| PACK_CAPSULE | pack_capsule | Package into time capsule | Medium (2) |

## Operator Specifications

### SELECT

**Purpose:** Select records matching specified criteria.

**Parameters:**
- `criteria` (String): Selection criteria pattern. Use `*` for all records.

**Input:** Map of key-value pairs
**Output:** Filtered map containing only matching entries

**Semantics:**
```
SELECT(data, criteria) → {k: v | k ∈ data.keys() ∧ matches(k, criteria)}
```

**Example:**
```java
// Input: {"user:123": {...}, "user:456": {...}, "system:log": {...}}
// Parameters: {"criteria": "user:"}
// Output: {"user:123": {...}, "user:456": {...}}
```

---

### FILTER

**Purpose:** Filter records by field value predicate.

**Parameters:**
- `field` (String, optional): Field name to filter on
- `value` (Object, optional): Value to match

**Input:** Map of key-value pairs
**Output:** Filtered map containing only matching entries

**Semantics:**
```
FILTER(data, field, value) → {k: v | k ∈ data.keys() ∧ (field = null ∨ k = field) ∧ (value = null ∨ v = value)}
```

---

### PROJECT

**Purpose:** Project only specified fields from the data.

**Parameters:** None (uses step's outputFields)

**Input:** Map of key-value pairs
**Output:** Map containing only allowed and requested fields

**Semantics:**
```
PROJECT(data, outputFields, allowedFields) → {k: v | k ∈ outputFields ∧ k ∈ allowedFields ∧ k ∈ data.keys()}
```

**Security:** Only fields in both `outputFields` AND `allowedFields` are included.

---

### BUCKETIZE

**Purpose:** Convert numeric values into range buckets for privacy.

**Parameters:**
- `field` (String): Field to bucketize
- `bucketSize` (int, default: 10): Size of each bucket

**Input:** Map with numeric field
**Output:** Map with additional `{field}_bucket` containing range string

**Semantics:**
```
BUCKETIZE(data, field, size) → data ∪ {field + "_bucket": floor(value/size)*size + "-" + (floor(value/size)*size + size)}
```

**Example:**
```java
// Input: {"age": 27}
// Parameters: {"field": "age", "bucketSize": 10}
// Output: {"age": 27, "age_bucket": "20-30"}
```

---

### AGGREGATE

**Purpose:** Compute aggregate statistics over data.

**Parameters:**
- `operation` (String): One of "count", "sum", "avg", "min", "max"

**Input:** Map of values
**Output:** Map with aggregate results

**Semantics:**
```
AGGREGATE(data, "count") → {"_aggregate_type": "count", "_aggregate_count": |data|}
AGGREGATE(data, "sum") → {"_aggregate_type": "sum", "_aggregate_sum": Σ(numeric values)}
```

---

### CLUSTER_REF

**Purpose:** Replace raw content with cluster identifiers for privacy.

**Parameters:**
- `field` (String, optional): Specific field to cluster

**Input:** Map of key-value pairs
**Output:** Map with values replaced by cluster references

**Semantics:**
```
CLUSTER_REF(data, field) → {k + "_cluster": "cluster:" + hash(v) | (k, v) ∈ data ∧ (field = null ∨ k = field)}
```

**Privacy:** Raw content is never exposed; only cluster IDs are returned.

---

### REDACT

**Purpose:** Redact sensitive fields from output.

**Parameters:** None (uses step's inputFields)

**Input:** Map of key-value pairs
**Output:** Map with specified fields replaced by "[REDACTED]"

**Semantics:**
```
REDACT(data, fields) → {k: (k ∈ fields ? "[REDACTED]" : v) | (k, v) ∈ data}
```

**Privacy Impact:** Negative (-1) - reduces privacy risk.

---

### SAMPLE

**Purpose:** Randomly sample a subset of records.

**Parameters:**
- `rate` (double, default: 0.1): Sampling rate (0.0 to 1.0)

**Input:** Map of key-value pairs
**Output:** Randomly sampled subset

**Semantics:**
```
SAMPLE(data, rate) → {k: v | (k, v) ∈ data ∧ random() < rate}
```

---

### EXPORT

**Purpose:** Mark data for export (actual export handled by capsule packager).

**Parameters:**
- `format` (String, default: "json"): Export format

**Input:** Map of key-value pairs
**Output:** Map with export metadata added

**Semantics:**
```
EXPORT(data, format) → data ∪ {"_export_requested": true, "_export_format": format}
```

**Security:** Export is only allowed if contract permits it.

---

### PACK_CAPSULE

**Purpose:** Package results into a time capsule with TTL.

**Parameters:**
- `ttl` (int, default: 3600): Time-to-live in seconds

**Input:** Map of key-value pairs
**Output:** Capsule structure with metadata

**Semantics:**
```
PACK_CAPSULE(data, ttl) → {"_capsule_data": data, "_capsule_timestamp": now(), "_capsule_ttl": ttl}
```

**Constraint:** MUST be the last step in any plan.

---

## Execution Constraints

### Resource Limits

| Resource | Default Limit | Maximum Allowed |
|----------|---------------|-----------------|
| CPU Time | 10,000 ms | 60,000 ms |
| Memory | 50 MB | 100 MB |
| Execution Time | 30,000 ms | 120,000 ms |
| Battery | 5% | 10% |

### Network Isolation

All network egress is blocked during plan execution. Any attempt to access network resources throws `SecurityException`.

### Operator Ordering

- `PACK_CAPSULE` must be the last step
- `EXPORT` requires contract permission
- Steps execute sequentially in index order

## Validation Rules

1. Plan must be signed
2. Plan must not be expired
3. All operators must be in allowlist
4. Resource limits must be within bounds
5. Fields must be in allowed set
6. PACK_CAPSULE must be last step

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-19 | Initial documentation |

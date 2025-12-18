# YACHAQ Platform - Reproducible Build Verification

## Overview

This document describes the reproducible build verification procedure for the YACHAQ Platform. Reproducible builds ensure that the same source code always produces identical binary artifacts, enabling third-party verification that the distributed binaries match the published source code.

## Requirements

- **Requirement 333.1**: Use reproducible builds with published binary hashes
- **Requirement 338.5**: Reproducible build verification is documented and repeatable
- **Requirement 357.2**: Support reproducible build verification procedure

## Build Environment

### Required Tools

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 21 | Java compilation |
| Maven | 3.9.x | Build automation |
| Git | 2.x | Source control |

### Environment Variables

```bash
# Ensure consistent timezone
export TZ=UTC

# Ensure consistent locale
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Disable Maven timestamp in manifests
export MAVEN_OPTS="-Dproject.build.outputTimestamp=2024-01-01T00:00:00Z"
```

## Verification Procedure

### Step 1: Clone Source Repository

```bash
git clone https://github.com/yachaq/yachaq-platform.git
cd yachaq-platform
git checkout <release-tag>
```

### Step 2: Verify Source Integrity

```bash
# Verify commit signature (if signed)
git verify-commit HEAD

# Compute source hash
find . -name "*.java" -type f | sort | xargs sha256sum | sha256sum
```

### Step 3: Build from Source

```bash
# Clean build with reproducible settings
mvn clean package -DskipTests \
    -Dproject.build.outputTimestamp=2024-01-01T00:00:00Z \
    -Dmaven.build.timestamp.format=yyyy-MM-dd'T'HH:mm:ss'Z'
```

### Step 4: Compute Binary Hashes

```bash
# Compute hashes for all JAR files
find . -name "*.jar" -path "*/target/*" | sort | while read jar; do
    echo "$(sha256sum "$jar" | cut -d' ' -f1) $jar"
done > build-hashes.txt
```

### Step 5: Compare with Published Hashes

```bash
# Download published hashes
curl -O https://releases.yachaq.io/<version>/hashes.txt

# Compare
diff build-hashes.txt hashes.txt
```

## Automated Verification Tests

The following tests verify reproducible build properties:

### Test: Hash Operations Deterministic
- **Location**: `AcceptanceSecurityGatesTest.ReproducibleBuilds`
- **Validates**: Same input produces same SHA-256 hash

### Test: Signature Verification Consistent
- **Location**: `AcceptanceSecurityGatesTest.ReproducibleBuilds`
- **Validates**: Signature verification produces consistent results

### Test: Contract Serialization Deterministic
- **Location**: `AcceptanceSecurityGatesTest.ReproducibleBuilds`
- **Validates**: Contract canonical bytes are deterministic

### Test: ODX Entry Creation Deterministic
- **Location**: `AcceptanceSecurityGatesTest.ReproducibleBuilds`
- **Validates**: ODX entries with same inputs produce same outputs

## Running Verification Tests

```bash
# Run reproducible build verification tests
mvn test -Dtest="AcceptanceSecurityGatesTest\$ReproducibleBuilds" -pl yachaq-api

# Expected output: All tests pass
```

## Build Artifacts

### Module Artifacts

| Module | Artifact | Description |
|--------|----------|-------------|
| yachaq-core | yachaq-core-1.0.0-SNAPSHOT.jar | Core domain models |
| yachaq-api | yachaq-api-1.0.0-SNAPSHOT.jar | REST/GraphQL API |
| yachaq-node | yachaq-node-1.0.0-SNAPSHOT.jar | Phone-as-Node runtime |
| yachaq-blockchain | yachaq-blockchain-1.0.0-SNAPSHOT.jar | Smart contracts |

### Hash Publication

Binary hashes are published at:
- Release page: `https://github.com/yachaq/yachaq-platform/releases/<version>`
- Hash file: `https://releases.yachaq.io/<version>/hashes.txt`
- Signature: `https://releases.yachaq.io/<version>/hashes.txt.sig`

## Troubleshooting

### Common Issues

1. **Different hashes due to timestamps**
   - Ensure `project.build.outputTimestamp` is set
   - Use UTC timezone

2. **Different hashes due to file ordering**
   - Sort files before hashing
   - Use consistent locale settings

3. **Different hashes due to dependency versions**
   - Use exact dependency versions in pom.xml
   - Verify Maven repository consistency

## Security Considerations

- All release artifacts MUST be signed with verified keys
- Hash files MUST be signed and verifiable
- Build environment MUST be documented and reproducible
- Third-party verification SHOULD be encouraged

## References

- [Reproducible Builds](https://reproducible-builds.org/)
- [Maven Reproducible Builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
- [YACHAQ Security Requirements](./requirements.md#requirement-333)

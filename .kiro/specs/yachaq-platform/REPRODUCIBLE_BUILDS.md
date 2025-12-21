# YACHAQ Platform - Reproducible Build Verification

## Overview

This document describes the reproducible build verification procedure for the YACHAQ Platform. Reproducible builds ensure that the same source code always produces identical binary artifacts, enabling third-party verification that the distributed binaries match the published source code.

## Requirements

- **Requirement 357.1**: Sign all releases with GPG keys
- **Requirement 357.2**: Support reproducible build verification procedure
- **Requirement 357.3**: Publish Software Bill of Materials (SBOM)
- **Requirement 357.4**: Document verification procedure

## Build Environment

### Required Tools

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 21 | Java compilation |
| Maven | 3.9.x | Build automation |
| Git | 2.x | Source control |
| GPG | 2.x | Artifact signing |

### Environment Variables

```bash
# Ensure consistent timezone
export TZ=UTC

# Ensure consistent locale
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF_8

# Reproducible build timestamp (set in pom.xml)
# project.build.outputTimestamp=2024-01-01T00:00:00Z
```

## Release Signing

### GPG Key Setup

```bash
# Generate a new GPG key (if needed)
gpg --full-generate-key

# List keys
gpg --list-secret-keys --keyid-format=long

# Export public key for distribution
gpg --armor --export YOUR_KEY_ID > yachaq-release-key.asc
```

### Signed Release Build

```bash
# Build with signing enabled
mvn clean package -Prelease -DskipTests

# This will:
# 1. Compile all modules
# 2. Generate SHA-256 and SHA-512 checksums
# 3. Sign all artifacts with GPG
# 4. Generate CycloneDX SBOM
```

### SBOM Generation Only

```bash
# Generate SBOM without full release
mvn package -Psbom -DskipTests

# Output: target/yachaq-platform-sbom.json
# Output: target/yachaq-platform-sbom.xml
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

# Verify tag signature
git verify-tag <release-tag>

# Compute source hash
find . -name "*.java" -type f | sort | xargs sha256sum | sha256sum
```

### Step 3: Verify GPG Signature

```bash
# Import YACHAQ release key
gpg --import yachaq-release-key.asc

# Verify artifact signature
gpg --verify yachaq-api-1.0.0.jar.asc yachaq-api-1.0.0.jar
```

### Step 4: Build from Source

```bash
# Clean build with reproducible settings
mvn clean package -DskipTests \
    -Dproject.build.outputTimestamp=2024-01-01T00:00:00Z
```

### Step 5: Compute Binary Hashes

```bash
# Compute hashes for all JAR files
find . -name "*.jar" -path "*/target/*" | sort | while read jar; do
    echo "$(sha256sum "$jar" | cut -d' ' -f1) $jar"
done > build-hashes.txt
```

### Step 6: Compare with Published Hashes

```bash
# Download published hashes
curl -O https://releases.yachaq.io/<version>/hashes.txt

# Verify signature of hash file
gpg --verify hashes.txt.sig hashes.txt

# Compare
diff build-hashes.txt hashes.txt
```

### Step 7: Verify SBOM

```bash
# Download SBOM
curl -O https://releases.yachaq.io/<version>/yachaq-platform-sbom.json

# Verify SBOM signature
gpg --verify yachaq-platform-sbom.json.sig yachaq-platform-sbom.json

# Validate SBOM format (requires cyclonedx-cli)
cyclonedx validate --input-file yachaq-platform-sbom.json
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

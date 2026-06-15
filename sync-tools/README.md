# KopiaKt Upstream Sync Tools

Tools for detecting API changes in Go Kopia and tracking compatibility with the Kotlin implementation.

## Overview

When Go Kopia releases new versions, these tools help identify:
1. Breaking API changes that require Kotlin updates
2. New features that could be implemented
3. Serialization format changes that affect compatibility

## Tools

### 1. `analyze` - API Snapshot Generator

Extracts public interfaces, structs, functions, and constants from Go Kopia source code.

```bash
cd sync-tools
go build -o bin/analyze ./cmd/analyze

# Generate snapshot from Go Kopia source
./bin/analyze -repo /path/to/kopia -output api-v0.17.json
```

**Output**: JSON file containing:
- All public interfaces with methods
- All public structs with fields and tags
- All public functions with signatures
- All exported constants

### 2. `compare` - Change Detector

Compares two API snapshots and identifies breaking changes.

```bash
go build -o bin/compare ./cmd/compare

# Compare versions
./bin/compare -old api-v0.16.json -new api-v0.17.json -output changes.json -markdown
```

**Output**:
- `changes.json` - Detailed change report
- `changes.md` - Human-readable markdown report

**Change Severities**:
- **Breaking**: Removed types/methods, signature changes, constant value changes
- **Warning**: New struct fields (affects serialization), tag changes
- **Info**: New types/functions (optional to implement)

## Sync Process

### Regular Sync Workflow

1. **Generate baseline snapshot** (after initial implementation):
   ```bash
   ./bin/analyze -repo ../kopia-go -output snapshots/baseline.json
   ```

2. **When new Go Kopia version is released**:
   ```bash
   cd ../kopia-go && git fetch && git checkout v0.18.0
   ./bin/analyze -repo ../kopia-go -output snapshots/v0.18.0.json
   ./bin/compare -old snapshots/baseline.json -new snapshots/v0.18.0.json -markdown
   ```

3. **Review changes**:
   - Read `changes.md` for summary
   - Check `KotlinImpacted` paths for affected files
   - Breaking changes MUST be addressed
   - Warnings should be reviewed for serialization impact

4. **Update Kotlin implementation**:
   - Address all breaking changes
   - Update test vectors if needed
   - Run E2E cross-compatibility tests

5. **Update baseline**:
   ```bash
   cp snapshots/v0.18.0.json snapshots/baseline.json
   ```

### CI Integration

Add to CI pipeline to catch API drift early:

```yaml
sync-check:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-go@v5
      with:
        go-version: '1.21'

    - name: Build tools
      run: |
        cd sync-tools
        go build -o bin/analyze ./cmd/analyze
        go build -o bin/compare ./cmd/compare

    - name: Checkout Go Kopia
      run: git clone --depth 1 https://github.com/kopia/kopia.git kopia-go

    - name: Generate snapshot
      run: ./sync-tools/bin/analyze -repo kopia-go -output current.json

    - name: Compare with baseline
      run: |
        ./sync-tools/bin/compare \
          -old sync-tools/snapshots/baseline.json \
          -new current.json \
          -markdown
      # Exits non-zero if breaking changes detected
```

## Go-to-Kotlin Mapping

### Package Mapping

| Go Package | Kotlin Module | Kotlin Package |
|------------|---------------|----------------|
| `repo/blob` | `:core` | `org.kopiaKt.core.blob` |
| `repo/blob/filesystem` | `:storage` | `org.kopiaKt.storage.filesystem` |
| `repo/blob/s3` | `:storage` | `org.kopiaKt.storage.s3` |
| `repo/blob/webdav` | `:storage` | `org.kopiaKt.storage.webdav` |
| `repo/blob/sftp` | `:storage` | `org.kopiaKt.storage.sftp` |
| `repo/content` | `:core` | `org.kopiaKt.core.content` |
| `repo/encryption` | `:core` | `org.kopiaKt.core.encryption` |
| `repo/hashing` | `:core` | `org.kopiaKt.core.hashing` |
| `repo/compression` | `:core` | `org.kopiaKt.core.compression` |
| `repo/splitter` | `:core` | `org.kopiaKt.core.splitter` |
| `repo/object` | `:core` | `org.kopiaKt.core.object` |
| `repo/manifest` | `:core` | `org.kopiaKt.core.manifest` |
| `repo/format` | `:core` | `org.kopiaKt.core.format` |
| `repo` | `:core` | `org.kopiaKt.core.repository` |
| `snapshot` | `:snapshot` | `org.kopiaKt.snapshot` |
| `snapshot/policy` | `:snapshot` | `org.kopiaKt.snapshot.policy` |
| `snapshot/restore` | `:snapshot` | `org.kopiaKt.snapshot.restore` |
| `snapshot/snapshotfs` | `:snapshot` | `org.kopiaKt.snapshot.snapshotfs` |
| `snapshot/snapshotmaintenance` | `:snapshot` | `org.kopiaKt.snapshot.maintenance` |
| `fs`, `fs/localfs` | `:snapshot` | `org.kopiaKt.snapshot.fs` |

### Type Mapping

| Go Type | Kotlin Type | Notes |
|---------|-------------|-------|
| `[]byte` | `ByteArray` | |
| `string` | `String` | |
| `int`, `int64` | `Long` | |
| `int32` | `Int` | |
| `uint32` | `UInt` or `Int` | Depends on usage |
| `float64` | `Double` | |
| `bool` | `Boolean` | |
| `time.Time` | `Instant` | java.time |
| `time.Duration` | `Duration` | java.time or kotlin.time |
| `context.Context` | `CoroutineScope` | For cancellation |
| `error` | `Exception` | Thrown, not returned |
| `io.Reader` | `InputStream` | |
| `io.Writer` | `OutputStream` | |
| `map[K]V` | `Map<K, V>` | |
| `chan T` | `Channel<T>` | kotlinx.coroutines |
| `sync.Mutex` | `Mutex` | kotlinx.coroutines |

### Interface Implementation

Go interfaces are implicit; Kotlin requires explicit implementation.

```go
// Go
type BlobStorage interface {
    GetBlob(ctx context.Context, id blob.ID, offset, length int64) ([]byte, error)
    PutBlob(ctx context.Context, id blob.ID, data gather.Bytes, opts PutOptions) error
}
```

```kotlin
// Kotlin
interface BlobStorage {
    suspend fun getBlob(id: BlobId, offset: Long = 0, length: Long = -1): ByteArray
    suspend fun putBlob(id: BlobId, data: ByteArray, options: PutBlobOptions = PutBlobOptions())
}
```

### Serialization Mapping

| Go | Kotlin |
|----|--------|
| `json.Marshal/Unmarshal` | kotlinx.serialization JSON |
| struct tags `json:"name"` | `@SerialName("name")` |
| `json:"name,omitempty"` | `@EncodeDefault(NEVER)` + nullable |
| `encoding/binary` | DataInputStream/DataOutputStream |
| Protocol Buffers | kotlinx.serialization.protobuf |

## Test Vector Regeneration

When Go algorithms change, regenerate test vectors:

```bash
cd testvectors
go run cmd/generate/main.go

# Copy to Kotlin test resources
cp vectors.json ../core/src/test/resources/
```

## Common Sync Issues

### 1. New Required Field in Struct

**Detection**: Warning about new field in struct

**Solution**:
1. Add field to Kotlin data class
2. Set appropriate default value
3. Test serialization round-trip

### 2. Interface Method Signature Change

**Detection**: Breaking change - method signature modified

**Solution**:
1. Update Kotlin interface
2. Update all implementations
3. Update all call sites
4. Regenerate test vectors if applicable

### 3. New Encryption/Hash Algorithm

**Detection**: Info - new constant/function added

**Solution**:
1. Implement algorithm in Kotlin
2. Add to factory
3. Generate test vectors
4. Verify cross-compatibility

### 4. Serialization Format Change

**Detection**: Warning about struct tag change, or E2E test failures

**Solution**:
1. Check Go source for format details
2. Update Kotlin serializers
3. Test with Go-generated data
4. Test Kotlin-generated data with Go

## Maintaining Test Vectors

Test vectors ensure byte-exact compatibility. Update when:

1. **Algorithm implementation changes**
2. **New algorithm added**
3. **Format version changes**

```bash
# Regenerate all vectors
cd testvectors
go run cmd/generate/main.go

# Run Kotlin tests to verify
cd ..
./gradlew :core:test --tests "*TestVector*"
```

## Version Compatibility Matrix

Track which KopiaKt versions are compatible with which Go Kopia versions:

| KopiaKt Version | Go Kopia Version | Notes |
|-----------------|------------------|-------|
| 0.1.0 | 0.17.x | Initial implementation |

Update this matrix when releasing new versions.

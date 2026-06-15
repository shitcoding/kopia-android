# Go Kopia → KopiaKt Sync Checklist

Use this checklist when syncing KopiaKt with a new Go Kopia release.

## Pre-Sync Preparation

- [ ] Note the current Go Kopia version KopiaKt is compatible with
- [ ] Note the target Go Kopia version to sync to
- [ ] Read Go Kopia release notes for breaking changes
- [ ] Create a new branch for the sync work

## API Analysis

- [ ] Pull latest Go Kopia source
  ```bash
  cd kopia-go
  git fetch origin
  git checkout v0.XX.0
  ```

- [ ] Generate new API snapshot
  ```bash
  cd sync-tools
  ./bin/analyze -repo ../../kopia-go -output snapshots/v0.XX.0.json
  ```

- [ ] Compare with baseline
  ```bash
  ./bin/compare -old snapshots/baseline.json -new snapshots/v0.XX.0.json -markdown
  ```

- [ ] Review `changes.md` for breaking changes
- [ ] Create issues for each breaking change

## Cryptographic Changes

Check for changes in these areas:

### Hashing
- [ ] New hash algorithms added?
- [ ] Hash output size changes?
- [ ] Key derivation context strings changed?

**Go files to check:**
- `repo/hashing/hashing.go`
- `repo/hashing/blake*.go`

**Kotlin files:**
- `core/src/main/kotlin/org/kopiaKt/core/hashing/*Hasher.kt`

### Encryption
- [ ] New encryption algorithms?
- [ ] Nonce derivation changes?
- [ ] Key derivation changes (HKDF purposes)?
- [ ] Authentication tag format changes?

**Go files to check:**
- `repo/encryption/encryption.go`
- `repo/encryption/aes*.go`

**Kotlin files:**
- `core/src/main/kotlin/org/kopiaKt/core/encryption/*Encryptor.kt`
- `core/src/main/kotlin/org/kopiaKt/core/format/KopiaRepositoryJson.kt`

### Key Derivation
- [ ] PBKDF2 iteration count changes?
- [ ] Scrypt parameter changes?
- [ ] HKDF context/purpose changes?

**Go files to check:**
- `repo/encryption/key_derivation*.go`

**Kotlin files:**
- `core/src/main/kotlin/org/kopiaKt/core/crypto/KeyDerivation.kt`

### Compression
- [ ] New compression algorithms?
- [ ] Header ID changes?
- [ ] Default compression level changes?

**Go files to check:**
- `repo/compression/compressor.go`
- `repo/compression/*_compressor.go`

**Kotlin files:**
- `core/src/main/kotlin/org/kopiaKt/core/compression/`

## Storage Format Changes

### Pack Blob Format
- [ ] Postamble format changes?
- [ ] Preamble size changes?
- [ ] CRC algorithm changes?

**Go files to check:**
- `repo/content/content_manager*.go`
- `repo/content/pack*.go`

**Kotlin files:**
- `core/src/main/kotlin/org/kopiaKt/core/pack/`

### Index Format
- [ ] Index version changes?
- [ ] Entry format changes?
- [ ] New index fields?

**Go files to check:**
- `repo/content/index/index*.go`

**Kotlin files:**
- `core/src/main/kotlin/org/kopiaKt/core/index/`
- `core/src/main/kotlin/org/kopiaKt/core/pack/PackIndex*.kt`

### Repository Format
- [ ] Format blob structure changes?
- [ ] Format version bump?
- [ ] New configuration fields?

**Go files to check:**
- `repo/format/format_blob*.go`
- `repo/format/*.go`

**Kotlin files:**
- `core/src/main/kotlin/org/kopiaKt/core/format/`

## Snapshot Layer Changes

### Snapshot Manifest
- [ ] New manifest fields?
- [ ] Field type changes?
- [ ] JSON serialization changes?

**Go files to check:**
- `snapshot/snapshot.go`
- `snapshot/manifest*.go`

**Kotlin files:**
- `snapshot/src/main/kotlin/org/kopiaKt/snapshot/model/SnapshotManifest.kt`

### Directory Manifest
- [ ] Entry format changes?
- [ ] New entry fields?
- [ ] Summary format changes?

**Go files to check:**
- `snapshot/snapshotfs/dir_manifest.go`
- `fs/entry.go`

**Kotlin files:**
- `snapshot/src/main/kotlin/org/kopiaKt/snapshot/model/SnapshotManifest.kt` (DirManifest)

### Policy
- [ ] New policy types?
- [ ] Policy field changes?
- [ ] Default value changes?

**Go files to check:**
- `snapshot/policy/*.go`

**Kotlin files:**
- `snapshot/src/main/kotlin/org/kopiaKt/snapshot/policy/`

## Interface Changes

For each interface change:

- [ ] Update Kotlin interface definition
- [ ] Update all implementations
- [ ] Update all callers
- [ ] Add tests for new methods

### Key Interfaces to Check
- [ ] `BlobStorage` / `BlobReader`
- [ ] `Repository` / `RepositoryWriter`
- [ ] `Entry` / `Directory` / `File`
- [ ] `ContentManager`
- [ ] `ObjectManager`

## Test Vector Regeneration

When crypto/format changes detected:

- [ ] Regenerate hash test vectors
  ```bash
  cd testvectors
  go run cmd/generate/main.go
  ```

- [ ] Copy to test resources
  ```bash
  cp vectors.json ../core/src/test/resources/
  ```

- [ ] Run Kotlin tests
  ```bash
  ./gradlew :core:test --tests "*TestVector*"
  ```

- [ ] Fix any failures

## Cross-Compatibility Testing

- [ ] Build test repository with Go Kopia
  ```bash
  kopia repository create filesystem --path /tmp/test-repo
  kopia snapshot create /path/to/test/files
  ```

- [ ] Open with Kotlin and verify
  ```bash
  ./gradlew :e2e:test --tests "*KotlinToGoCompatibilityTest*" -Pe2e
  ./gradlew :e2e:test --tests "*RepositoryCompatibilityTest*" -Pe2e
  ./gradlew :e2e:test --tests "*GoToKotlinDeterministicRestoreTest*" -Pe2e
  ./gradlew :e2e:test --tests "*AlgorithmIndexMatrixCompatibilityTest*" -Pe2e
  ./gradlew :e2e:test --tests "*EdgeCaseFixtureCrossCompatibilityTest*" -Pe2e
  ```

- [ ] Create repository with Kotlin

- [ ] Open with Go and verify
  ```bash
  kopia repository connect filesystem --path /tmp/kotlin-repo
  kopia snapshot list
  ```

## Storage Backend Changes

Check each backend for changes:

### Filesystem
- [ ] Sharding algorithm changes?
- [ ] File naming changes?

### S3
- [ ] New S3 options?
- [ ] Retry logic changes?

### WebDAV
- [ ] New WebDAV options?
- [ ] Authentication changes?

### SFTP
- [ ] New SFTP options?
- [ ] Key handling changes?

## Documentation Updates

- [ ] Update version compatibility matrix in README
- [ ] Update any changed API documentation
- [ ] Update CHANGELOG with sync details
- [ ] Update baseline snapshot
  ```bash
  cp snapshots/v0.XX.0.json snapshots/baseline.json
  ```

## Final Verification

- [ ] All unit tests pass: `./gradlew test`
- [ ] All E2E tests pass: `./gradlew :e2e:test -Pe2e`
- [ ] Android tests pass: `./gradlew :android:connectedAndroidTest`
- [ ] Code quality passes: `./gradlew ktlintCheck detekt`
- [ ] Create PR with sync changes
- [ ] Tag release after merge

## Post-Sync

- [ ] Update project documentation with new version
- [ ] Announce sync completion
- [ ] Monitor for any compatibility issues

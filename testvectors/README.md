# KopiaKt Test Vectors

This directory contains test vectors generated from the Go Kopia implementation. These vectors ensure byte-exact compatibility between the Go and Kotlin implementations.

## Directory Structure

```
testvectors/
├── vectors.json           # Main test vectors file (JSON format)
├── cmd/generate/          # Go program that generates test vectors
│   └── main.go
├── create_test_repo.sh    # Script to create a minimal Kopia test repository
├── test_repository/       # (Generated) Kopia repository with known content
├── test_data/             # (Generated) Test data files used in repository
└── test_repo_config.json  # (Generated) Repository config for verification
```

## Test Vectors Contents

### 1. Hash Algorithms
- **BLAKE2B-256-128**: BLAKE2B hashed to 256 bits, truncated to 128 bits (default)
- **BLAKE2B-256**: Full 256-bit BLAKE2B output
- **BLAKE3-256**: BLAKE3 with 256-bit output and key derivation
- **BLAKE3-256-128**: BLAKE3 truncated to 128 bits
- **HMAC-SHA256**: Standard HMAC with SHA256

### 2. Key Derivation
- **PBKDF2**: PBKDF2-HMAC-SHA256 with 600,000 iterations (Kopia default)
- **Scrypt**: scrypt-65536-8-1 (Kopia default)
- **HKDF**: HKDF-SHA256 for AES key derivation

### 3. Encryption
- **AES-256-GCM**: AES-256 in GCM mode with various test cases

### 4. Compression Headers
- GZIP, Zstd, LZ4, S2, PGZip, Deflate with header IDs

### 5. Splitter Algorithms
- **Buzhash32**: Content-defined chunking with 32-bit Buzhash
- **RabinKarp64**: Content-defined chunking with 64-bit Rabin-Karp

### 6. Content ID Formation
- How content IDs are formed from hash outputs with prefixes

## Regenerating Test Vectors

To regenerate the test vectors:

```bash
cd cmd/generate
go run main.go
```

This will update `vectors.json` with fresh test vectors.

## Creating Test Repository

To create a minimal Kopia repository for cross-compatibility testing:

```bash
./create_test_repo.sh
```

This requires Go and will:
1. Build Kopia from source
2. Create test data files with known content
3. Initialize a Kopia repository with password `test123`
4. Create a snapshot of the test data

The repository can then be used to verify that KopiaKt can:
- Connect to the repository
- Read the content index
- Restore snapshot contents

## Using Test Vectors in Kotlin

```kotlin
// Load test vectors
val vectors = TestVectorLoader.load()

// Example: Verify BLAKE2B-256-128 implementation
vectors.hash.blake2b256128.forEach { testCase ->
    val result = myBlake2bImplementation.hash(testCase.input, testCase.secretBytes)
    assertThat(result).isEqualTo(testCase.output)
}
```

## Constants from Go Kopia

These constants MUST be replicated exactly in the Kotlin implementation:

| Constant | Value | Notes |
|----------|-------|-------|
| BLAKE3 Key Derivation Context | `"kopia blake3 derived key v1"` | Used when key < 32 bytes |
| BLAKE3 Key Size | 32 bytes | Keys are derived to this length |
| Splitter Window Size | 64 bytes | Rolling hash window |
| PBKDF2 Iterations | 600,000 | Default iterations |
| Scrypt N | 65,536 | CPU/memory cost |
| Scrypt r | 8 | Block size |
| Scrypt p | 1 | Parallelization |
| AES-256-GCM Overhead | 28 bytes | For AES256-GCM-HMAC-SHA256 |

## Compression Header IDs

| Algorithm | Header ID (hex) | Header ID (decimal) |
|-----------|-----------------|---------------------|
| GZIP Default | 0x00001000 | 4096 |
| Zstd Default | 0x00001100 | 4352 |
| Zstd Fastest | 0x00001101 | 4353 |
| S2 Default | 0x00001200 | 4608 |
| LZ4 Default | 0x00001400 | 5120 |
| Deflate Default | 0x00001500 | 5376 |

## Index Blob Format

### Version 1
- Header: 8 bytes
- Entry: 20 bytes fixed

### Version 2
- Header: 17 bytes
- Entry: 16-19 bytes variable
- Supports more packs and content

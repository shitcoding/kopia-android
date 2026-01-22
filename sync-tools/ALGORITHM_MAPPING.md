# Go Kopia ↔ KopiaKt Algorithm Correspondence

This document maps algorithms between Go Kopia and KopiaKt implementations.

## Hashing Algorithms

### BLAKE2B-256-128

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | `golang.org/x/crypto/blake2b` | BouncyCastle `Blake2bDigest` |
| Output Size | 32 bytes, truncated to 16 | 32 bytes, truncated to 16 |
| Key Mode | Built-in keyed BLAKE2b | `Blake2bDigest(key)` constructor |
| Empty Key | Unkeyed mode | Unkeyed mode |
| File | `repo/hashing/blake2b_128.go` | `core/.../crypto/Blake2bHasher.kt` |

### BLAKE2B-256

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | `golang.org/x/crypto/blake2b` | BouncyCastle `Blake2bDigest` |
| Output Size | 32 bytes | 32 bytes |
| Key Mode | Built-in keyed BLAKE2b | `Blake2bDigest(key)` constructor |
| File | `repo/hashing/blake2b_256.go` | `core/.../crypto/Blake2bHasher.kt` |

### BLAKE3-256

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | `lukechampine.com/blake3` | BouncyCastle `Blake3Digest` |
| Output Size | 32 bytes | 32 bytes |
| Key Mode | DeriveKey with context | DeriveKey with context |
| Context | `"kopia blake3 derived key v1"` | `"kopia blake3 derived key v1"` |
| Empty Key | Unkeyed (plain hash) | Unkeyed (plain hash) |
| File | `repo/hashing/blake3_256.go` | `core/.../crypto/Blake3Hasher.kt` |

### HMAC-SHA256-128

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | `crypto/hmac` + `crypto/sha256` | `javax.crypto.Mac` with `HmacSHA256` |
| Output Size | 32 bytes, truncated to 16 | 32 bytes, truncated to 16 |
| File | `repo/hashing/hmac_sha256.go` | `core/.../crypto/HmacSha256Hasher.kt` |

## Key Derivation

### PBKDF2

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | `golang.org/x/crypto/pbkdf2` | BouncyCastle `PKCS5S2ParametersGenerator` |
| Hash | SHA-256 | SHA-256 |
| Iterations | 600,000 (default) | 600,000 (default) |
| Output | Variable length | Variable length |
| Note | Uses raw bytes | Must use raw bytes (not char conversion) |
| File | `repo/encryption/key_derivation_pbkdf2.go` | `core/.../crypto/KeyDerivation.kt` |

### Scrypt

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | `golang.org/x/crypto/scrypt` | BouncyCastle `SCrypt` |
| N | 65536 (2^16) | 65536 |
| r | 8 | 8 |
| p | 1 | 1 |
| File | `repo/encryption/key_derivation_scrypt.go` | `core/.../crypto/KeyDerivation.kt` |

### HKDF

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | `golang.org/x/crypto/hkdf` | BouncyCastle `HKDFBytesGenerator` |
| Hash | SHA-256 | SHA-256 |
| Extract + Expand | Yes | Yes |
| File | `repo/encryption/key_derivation_hkdf.go` | `core/.../crypto/KeyDerivation.kt` |

**HKDF Purposes (info parameter):**
- `"AES"` - Derive AES encryption key from master key
- `"CHECKSUM"` - Derive authentication data
- `"contentEncryptionKey"` - Derive per-content encryption key
- Custom purposes for various derived keys

## Encryption

### AES-256-GCM-HMAC-SHA256

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Cipher | `crypto/aes` + `crypto/cipher` GCM | `javax.crypto.Cipher` AES/GCM/NoPadding |
| Key Size | 256 bits (32 bytes) | 256 bits (32 bytes) |
| Nonce Size | 12 bytes | 12 bytes |
| Tag Size | 16 bytes | 16 bytes |
| Nonce Derivation | HMAC-SHA256 of content ID, truncated | HMAC-SHA256 of content ID, truncated |
| Key Derivation | HKDF from master key | HKDF from master key |
| File | `repo/encryption/aes256_gcm_hmac_sha256.go` | `core/.../crypto/Aes256GcmHmacSha256Encryptor.kt` |

**Nonce Derivation Details:**
1. HMAC-SHA256(nonceKey, contentId.toString().toByteArray())
2. Truncate to 12 bytes (take first 12 bytes)

## Compression

### GZIP

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Library | `compress/gzip` | `java.util.zip.GZIPOutputStream/InputStream` |
| Header ID | 0x1000 (default), 0x1001 (best-speed), 0x1002 (best-compression) | Same |
| Levels | 1 (best-speed) to 9 (best-compression) | Same |
| File | `repo/compression/gzip_compressor.go` | `core/.../compression/GzipCompressor.kt` |

### Deflate

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Library | `compress/flate` | `java.util.zip.Deflater/Inflater` |
| Header ID | 0x1500-0x1502 | Same |
| File | `repo/compression/deflate_compressor.go` | `core/.../compression/DeflateCompressor.kt` |

### Zstd

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Library | `github.com/klauspost/compress/zstd` | zstd-jni |
| Header ID | 0x1100 (default), 0x1101 (fastest), 0x1102 (better), 0x1103 (best) | Same |
| Levels | 1 (fastest), 3 (default), 7 (better), 19 (best) | Same |
| File | `repo/compression/zstd_compressor.go` | `core/.../compression/ZstdCompressor.kt` |

### LZ4

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Library | `github.com/pierrec/lz4/v4` | lz4-java |
| Header ID | 0x1400 | Same |
| Frame Format | LZ4 Frame | LZ4 Frame |
| Note | - | Use manual read loop (available() bug) |
| File | `repo/compression/lz4_compressor.go` | `core/.../compression/Lz4Compressor.kt` |

### S2 (Not Implemented in Kotlin)

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Library | `github.com/klauspost/compress/s2` | Not available (Go-specific) |
| Header ID | 0x1200-0x1203 | Throws UnsupportedOperationException |

## Content Splitting

### Buzhash

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | 32-bit Buzhash rolling hash | 32-bit Buzhash rolling hash |
| Window Size | 64 bytes | 64 bytes |
| Split Condition | `(hash & (avgSize-1)) == 0` | `(hash & (avgSize-1)) == 0` |
| Byte Table | Precomputed 256-entry table | Precomputed 256-entry table |
| Out Table | `table[b] << (windowSize % 32)` | Same formula |
| Min Size | avgSize / 2 | avgSize / 2 |
| Max Size | avgSize * 2 | avgSize * 2 |
| File | `repo/splitter/buzhash32.go` | `core/.../splitter/Buzhash32Splitter.kt` |

### Rabin-Karp

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Algorithm | 64-bit polynomial rolling hash | 64-bit polynomial rolling hash |
| Polynomial | 0x2e3e3e4a305605 (degree 53) | Same |
| Window Size | 64 bytes | 64 bytes |
| Split Condition | `(hash & (avgSize-1)) == 0` | `(hash & (avgSize-1)) == 0` |
| GF(2) Arithmetic | Polynomial multiplication/modulo | Same |
| File | `repo/splitter/rabinkarp64.go` | `core/.../splitter/RabinKarp64Splitter.kt` |

**Note on Rabin-Karp outTable:**
- Build starting with byte value `b`
- Apply `windowSize - 1` shift-and-mod operations
- NOT `b << 8` (common mistake)

### Fixed Size

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Sizes | 128K, 256K, 512K, 1M, 2M, 4M, 8M | Same |
| Split | At exact size boundaries | Same |
| File | `repo/splitter/fixed.go` | `core/.../splitter/FixedSplitter.kt` |

## Pack Index Format

### Version 1

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Header | 8 bytes: version(1) + keySize(1) + entrySize(2) + count(4) | Same |
| Entry Size | 20 bytes fixed | 20 bytes fixed |
| Content ID | First keySize bytes of entry | Same |
| Timestamp | 6 bytes (48-bit Unix millis) | Same |
| Format Version | 1 byte | Same |
| Pack ID Length | 1 byte | Same |
| Pack Offset | 4 bytes (upper 4 bits for offset high bits, lower 28 for length) | Same |
| File | `repo/content/index/index_v1.go` | `core/.../content/index/PackIndexV1.kt` |

### Version 2

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Header | 17 bytes | Same |
| Entry Size | Variable (16-19 bytes) | Same |
| Features | Compression headers, encryption key IDs | Same |
| Format Info Table | Separate section | Same |
| Pack Info Table | Separate section | Same |
| File | `repo/content/index/index_v2.go` | `core/.../content/index/PackIndexV2.kt` |

## Repository Format Blob

| Aspect | Go Implementation | Kotlin Implementation |
|--------|------------------|----------------------|
| Encryption | AES-256-GCM | Same |
| Key Derivation | HKDF from master key | Same |
| HKDF Purposes | "AES", "CHECKSUM" | Same |
| JSON Encoding | Standard library | kotlinx.serialization |
| Byte Array Encoding | Base64 strings | Same (ByteArrayBase64Serializer) |
| File | `repo/format/format_blob.go` | `core/.../format/KopiaRepositoryJson.kt` |

## Serialization Formats

### JSON Field Naming

| Go Pattern | Kotlin Pattern |
|------------|----------------|
| `json:"fieldName"` | `@SerialName("fieldName")` |
| `json:"field,omitempty"` | Nullable field + @EncodeDefault(NEVER) |
| CamelCase fields | Same (Go uses lowerCamelCase) |

### Timestamps

| Go Format | Kotlin Format |
|-----------|---------------|
| RFC3339Nano with timezone | Same via InstantSerializer |
| `"2024-01-15T10:30:00.123456789Z"` | Same |
| `"2024-01-15T10:30:00.123456789-07:00"` | Handled with ZonedDateTime parsing |

### Binary Formats

| Go Encoding | Kotlin Encoding |
|-------------|-----------------|
| `encoding/binary` BigEndian | DataOutputStream / manual byte operations |
| Varint (unsigned LEB128) | Custom varint implementation |
| CRC32 IEEE | `java.util.zip.CRC32` |

## Verification Points

To verify algorithm correspondence, use the test vectors in `testvectors/vectors.json`:

1. **Hashing**: Verify same input produces same hash output
2. **Encryption**: Verify Kotlin can decrypt Go-encrypted data and vice versa
3. **Compression**: Verify Kotlin can decompress Go-compressed data
4. **Splitting**: Verify same input produces same chunk boundaries
5. **Index**: Verify Kotlin can read Go-created indexes
6. **Pack**: Verify Kotlin can read Go-created pack blobs
7. **Format**: Verify Kotlin can open Go-created repositories

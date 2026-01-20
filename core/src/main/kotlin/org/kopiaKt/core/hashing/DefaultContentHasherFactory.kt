package org.kopiaKt.core.hashing

/**
 * Default factory for creating content hashers.
 *
 * Creates hashers that are byte-compatible with the Go Kopia implementation.
 */
class DefaultContentHasherFactory : ContentHasherFactory {

    override fun create(algorithm: HashAlgorithm, secret: ByteArray): ContentHasher {
        return when (algorithm) {
            HashAlgorithm.BLAKE2B_256_128 -> Blake2bHasher(algorithm, secret)
            HashAlgorithm.BLAKE2B_256_256 -> Blake2bHasher(algorithm, secret)
            HashAlgorithm.BLAKE3_256 -> Blake3Hasher(algorithm, secret)
            HashAlgorithm.HMAC_SHA256_128 -> HmacSha256Hasher(algorithm, secret)
        }
    }
}

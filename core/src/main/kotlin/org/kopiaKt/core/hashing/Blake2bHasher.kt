package org.kopiaKt.core.hashing

import org.bouncycastle.crypto.digests.Blake2bDigest

/**
 * BLAKE2B hasher implementation using BouncyCastle.
 *
 * This implementation matches the Go Kopia behavior:
 * - Uses BLAKE2B-256 (256-bit output digest)
 * - Supports keyed hashing via BLAKE2B's built-in key parameter
 * - Truncates output to the specified size
 *
 * @param algorithm The hash algorithm (determines output truncation)
 * @param key The secret key for keyed hashing (can be empty)
 */
class Blake2bHasher(
    override val algorithm: HashAlgorithm,
    private val key: ByteArray
) : ContentHasher {

    init {
        require(algorithm == HashAlgorithm.BLAKE2B_256_128 || algorithm == HashAlgorithm.BLAKE2B_256_256) {
            "Blake2bHasher only supports BLAKE2B algorithms, got: ${algorithm.id}"
        }
    }

    override fun hashContent(data: ByteArray): ByteArray {
        // BLAKE2B-256 produces 256 bits (32 bytes) output
        // Key size can be 0-64 bytes for BLAKE2B
        val digest = if (key.isEmpty()) {
            Blake2bDigest(BLAKE2B_OUTPUT_BITS)
        } else {
            Blake2bDigest(key, BLAKE2B_OUTPUT_BITS / 8, null, null)
        }

        digest.update(data, 0, data.size)

        val fullHash = ByteArray(BLAKE2B_OUTPUT_BITS / 8)
        digest.doFinal(fullHash, 0)

        // Truncate to the algorithm's specified output size
        return if (algorithm.outputSize < fullHash.size) {
            fullHash.copyOf(algorithm.outputSize)
        } else {
            fullHash
        }
    }

    companion object {
        // BLAKE2B-256 output size in bits
        private const val BLAKE2B_OUTPUT_BITS = 256
    }
}

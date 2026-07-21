package org.kopiaKt.core.hashing

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 hasher implementation.
 *
 * Uses standard HMAC-SHA256 from javax.crypto.
 * Can be truncated to 128 bits (16 bytes) for HMAC-SHA256-128.
 *
 * @param algorithm The hash algorithm (determines output truncation)
 * @param key The secret key for HMAC
 */
class HmacSha256Hasher(
    override val algorithm: HashAlgorithm,
    private val key: ByteArray,
) : ContentHasher {

    init {
        require(algorithm == HashAlgorithm.HMAC_SHA256_128) {
            "HmacSha256Hasher only supports HMAC-SHA256-128, got: ${algorithm.id}"
        }
    }

    override fun hashContent(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
        val keySpec = SecretKeySpec(key, HMAC_SHA256_ALGORITHM)
        mac.init(keySpec)

        val fullHash = mac.doFinal(data)

        // Truncate to the algorithm's specified output size
        return if (algorithm.outputSize < fullHash.size) {
            fullHash.copyOf(algorithm.outputSize)
        } else {
            fullHash
        }
    }

    companion object {
        private const val HMAC_SHA256_ALGORITHM = "HmacSHA256"
    }
}

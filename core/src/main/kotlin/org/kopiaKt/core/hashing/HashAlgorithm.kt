package org.kopiaKt.core.hashing

/**
 * Supported hash algorithms for content addressing.
 *
 * These algorithms must match the Go implementation exactly for cross-compatibility.
 */
enum class HashAlgorithm(val id: String, val outputSize: Int) {
    /**
     * BLAKE2B-256 truncated to 128 bits (16 bytes). Default algorithm.
     */
    BLAKE2B_256_128("BLAKE2B-256-128", 16),

    /**
     * Full BLAKE2B-256 (32 bytes).
     */
    BLAKE2B_256_256("BLAKE2B-256-256", 32),

    /**
     * BLAKE3 with 256-bit output (32 bytes).
     */
    BLAKE3_256("BLAKE3-256", 32),

    /**
     * HMAC-SHA256 truncated to 128 bits (16 bytes).
     */
    HMAC_SHA256_128("HMAC-SHA256-128", 16);

    companion object {
        /**
         * Default algorithm used for new repositories.
         */
        val DEFAULT = BLAKE2B_256_128

        /**
         * Finds algorithm by ID string.
         */
        fun fromId(id: String): HashAlgorithm? =
            entries.find { it.id == id }

        /**
         * Finds algorithm by ID string (alias for fromId).
         */
        fun fromString(id: String): HashAlgorithm? = fromId(id)
    }
}

/**
 * Interface for content hashing implementations.
 *
 * Implementations must produce byte-exact output matching the Go implementation
 * for cross-compatibility.
 */
interface ContentHasher {
    /**
     * The hash algorithm used by this hasher.
     */
    val algorithm: HashAlgorithm

    /**
     * Output size in bytes.
     */
    val hashSize: Int
        get() = algorithm.outputSize

    /**
     * Computes the hash of the given data.
     *
     * @param data The data to hash
     * @return The hash output (may be truncated per algorithm)
     */
    fun hashContent(data: ByteArray): ByteArray
}

/**
 * Factory for creating content hashers.
 */
interface ContentHasherFactory {
    /**
     * Creates a hasher with the given secret for HMAC operations.
     *
     * @param algorithm The hash algorithm to use
     * @param secret The secret key for HMAC
     * @return A ContentHasher instance
     */
    fun create(algorithm: HashAlgorithm, secret: ByteArray): ContentHasher
}

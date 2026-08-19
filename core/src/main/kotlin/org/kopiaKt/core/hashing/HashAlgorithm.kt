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
     * Full BLAKE2B-256 (32 bytes), untruncated.
     *
     * The id was `BLAKE2B-256-256` until task-74, which is not an algorithm Go kopia has — Go calls
     * this one `BLAKE2B-256`. A repository created under the old id could not be opened by desktop
     * Kopia at all ("unknown hash function"), which is the compatibility promise this project is
     * built on. Only the NAME was wrong: the digest is the same full keyed BLAKE2b-256, proven by
     * putting `BLAKE2B-256` through the cross-compat matrix in both directions.
     */
    BLAKE2B_256_256("BLAKE2B-256", 32),

    /**
     * BLAKE3 with 256-bit output (32 bytes).
     */
    BLAKE3_256("BLAKE3-256", 32),

    /**
     * HMAC-SHA256 truncated to 128 bits (16 bytes).
     */
    HMAC_SHA256_128("HMAC-SHA256-128", 16),
    ;

    companion object {
        /**
         * Default algorithm used for new repositories.
         */
        val DEFAULT = BLAKE2B_256_128

        /**
         * Finds algorithm by ID string.
         */
        fun fromId(id: String): HashAlgorithm? = entries.find { it.id == id }

        /**
         * Finds algorithm by ID string (alias for fromId).
         */
        fun fromString(id: String): HashAlgorithm? = fromId(id)

        /**
         * Why a repository this build cannot open is being refused, written for the person holding
         * the phone rather than for a stack trace.
         *
         * Go kopia offers eleven hash algorithms and this app implements four. **That is a recorded
         * decision, not an oversight (task-74):** Go's default is `BLAKE2B-256-128`, which is what
         * desktop Kopia creates unless the user goes looking, so the gap is reachable only for
         * someone who deliberately chose a non-default algorithm and then wants that repository on a
         * phone — against seven more byte-exact digests to ship and keep matching Go forever.
         *
         * What the decision does oblige is this message. The alternative — a bare "unknown hash
         * algorithm" — tells a user nothing they can act on, and the thing they can act on is real:
         * the repository is fine, it simply has to be opened with desktop Kopia, or created again
         * with an algorithm both implementations have.
         */
        fun unsupportedMessage(id: String): String = buildString {
            append("This repository uses the hash algorithm \"$id\", which this app does not ")
            append("implement. It supports ${entries.joinToString(", ") { it.id }}. Desktop Kopia ")
            append("offers several others; a repository created with one of those is not damaged, ")
            append("but it can only be opened by Kopia itself. Open it on a desktop, or create the ")
            append("repository with ${DEFAULT.id} — Kopia's own default — to use it here.")
        }
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

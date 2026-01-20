package org.kopiaKt.core.content

/**
 * Represents a content ID, which is a hash-based identifier for content blocks.
 *
 * Content IDs are the primary addressing mechanism in Kopia's content-addressable storage.
 * They consist of an optional single-character prefix ('g' to 'z') followed by a hex-encoded hash.
 *
 * Format (matching Go implementation):
 * - Optional single-character prefix ('g' to 'z')
 * - Followed by hex-encoded hash bytes (up to 32 bytes = 64 hex chars)
 * - Empty string is valid (EmptyID)
 * - If string length is odd and first char is 'g'-'z', it's a prefix
 *
 * Examples:
 * - "" -> EmptyID
 * - "0123456789abcdef" -> no prefix, hash = 0x01234567 89abcdef
 * - "mabcdef12" -> prefix='m', hash = 0xabcdef12
 */
class ContentId private constructor(
    /**
     * The optional prefix character ('g' to 'z'), or null if no prefix.
     */
    val prefix: Char?,
    /**
     * The raw hash bytes.
     */
    val hashBytes: ByteArray
) {
    /**
     * Returns whether this content ID has a prefix.
     */
    val hasPrefix: Boolean
        get() = prefix != null

    /**
     * Returns the string representation matching Go's format.
     */
    override fun toString(): String {
        if (this === Empty || (prefix == null && hashBytes.isEmpty())) {
            return ""
        }
        val hexHash = hashBytes.toHexString()
        return if (prefix != null) "$prefix$hexHash" else hexHash
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentId) return false
        return prefix == other.prefix && hashBytes.contentEquals(other.hashBytes)
    }

    override fun hashCode(): Int {
        var result = prefix?.hashCode() ?: 0
        result = 31 * result + hashBytes.contentHashCode()
        return result
    }

    companion object {
        /**
         * Maximum hash size in bytes (matches Go's hashing.MaxHashSize).
         */
        const val MAX_HASH_SIZE = 32

        /**
         * Minimum valid prefix character.
         */
        const val MIN_PREFIX = 'g'

        /**
         * Maximum valid prefix character.
         */
        const val MAX_PREFIX = 'z'

        /**
         * Empty content ID equivalent to empty string.
         */
        val Empty: ContentId = ContentId(null, ByteArray(0))

        /**
         * Parses a content ID string into a ContentId object.
         *
         * @param s The string to parse
         * @return The parsed ContentId
         * @throws IllegalArgumentException if the string is invalid
         */
        fun parse(s: String): ContentId {
            if (s.isEmpty()) {
                return Empty
            }

            var remaining = s.lowercase()
            var prefix: Char? = null

            // If odd length and first char is in 'g'-'z' range, it's a prefix
            if (remaining.length % 2 == 1) {
                val firstChar = remaining[0]
                if (firstChar in MIN_PREFIX..MAX_PREFIX) {
                    prefix = firstChar
                    remaining = remaining.substring(1)
                } else {
                    throw IllegalArgumentException("invalid content prefix: '$firstChar'")
                }
            }

            // Validate remaining string length
            if (remaining.length > MAX_HASH_SIZE * 2) {
                throw IllegalArgumentException("hash too long: ${remaining.length / 2} bytes, max is $MAX_HASH_SIZE")
            }

            // Parse hex hash
            if (remaining.isEmpty()) {
                throw IllegalArgumentException("id too short: '$s'")
            }

            val hashBytes = try {
                remaining.hexToByteArray()
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid content hash: ${e.message}")
            }

            return ContentId(prefix, hashBytes)
        }

        /**
         * Creates a ContentId from a prefix and hash bytes.
         *
         * @param prefix The optional prefix character ('g' to 'z'), or null
         * @param hash The hash bytes
         * @return The ContentId
         * @throws IllegalArgumentException if the prefix or hash is invalid
         */
        fun fromHash(prefix: Char?, hash: ByteArray): ContentId {
            if (prefix != null && (prefix < MIN_PREFIX || prefix > MAX_PREFIX)) {
                throw IllegalArgumentException("invalid prefix '$prefix', must be between '$MIN_PREFIX' and '$MAX_PREFIX'")
            }
            if (hash.isEmpty()) {
                throw IllegalArgumentException("hash cannot be empty")
            }
            if (hash.size > MAX_HASH_SIZE) {
                throw IllegalArgumentException("hash too long: ${hash.size} bytes, max is $MAX_HASH_SIZE")
            }
            return ContentId(prefix, hash.copyOf())
        }
    }
}

/**
 * Content ID prefix constants used for different content types.
 */
object ContentIdPrefix {
    /** Manifest content prefix. */
    const val MANIFEST = 'm'

    /** Pack blob prefix for regular content. */
    const val PACK_REGULAR = 'p'

    /** Pack blob prefix for special content. */
    const val PACK_SPECIAL = 'q'
}

/**
 * Extension function to convert ByteArray to lowercase hex string.
 */
internal fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

/**
 * Extension function to convert hex string to ByteArray.
 */
internal fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

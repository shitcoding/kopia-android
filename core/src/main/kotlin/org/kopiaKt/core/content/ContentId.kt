package org.kopiaKt.core.content

/**
 * Represents a content ID, which is a hash-based identifier for content blocks.
 *
 * Content IDs are the primary addressing mechanism in Kopia's content-addressable storage.
 * They are computed from the content using HMAC with a secret key.
 */
@JvmInline
value class ContentId(val value: String) {
    init {
        require(value.isNotEmpty()) { "ContentId cannot be empty" }
    }

    /**
     * The prefix character indicates the content type.
     */
    val prefix: Char
        get() = value[0]

    /**
     * Returns whether this is a packed content ID (stored in pack blobs).
     */
    val isPacked: Boolean
        get() = prefix != DIRECT_PREFIX

    override fun toString(): String = value

    companion object {
        /**
         * Prefix for direct content (not stored in packs).
         */
        const val DIRECT_PREFIX = 'x'

        /**
         * Creates a ContentId from raw hash bytes.
         *
         * @param prefix The content type prefix
         * @param hash The hash bytes
         * @return A ContentId with hex-encoded hash
         */
        fun fromBytes(prefix: Char, hash: ByteArray): ContentId {
            require(hash.isNotEmpty()) { "Hash cannot be empty" }
            return ContentId("$prefix${hash.toHexString()}")
        }
    }
}

/**
 * Content ID prefix used for different content types.
 */
object ContentIdPrefix {
    const val MANIFEST = 'm'
    const val PACKED = 'p'
    const val INDEX = 'i'
    const val DIRECTORY = 'd'
    const val REGULAR = 'k'
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

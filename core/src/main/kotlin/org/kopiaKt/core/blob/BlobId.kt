package org.kopiaKt.core.blob

/**
 * Represents a unique identifier for a blob in storage.
 *
 * Blob IDs are string-based identifiers that follow a specific format
 * depending on the blob type (pack blob, index blob, etc.).
 *
 * This matches Go's `blob.ID` type which is a string type alias.
 *
 * Blob ID prefixes:
 * - 'p' prefix: pack blobs (containing regular content)
 * - 'q' prefix: pack blobs (containing special/metadata content)
 * - 'n' prefix: index blobs
 * - 's' prefix: session blobs
 * - 'x' prefix: index shard blobs
 * - No standard prefix: configuration blobs (like kopia.repository)
 */
@JvmInline
value class BlobId(val value: String) {
    init {
        require(value.isNotEmpty()) { "BlobId cannot be empty" }
    }

    /**
     * Returns whether this blob ID has the given prefix.
     */
    fun hasPrefix(prefix: String): Boolean = value.startsWith(prefix)

    /**
     * Returns true if this is a pack blob (p or q prefix).
     */
    val isPackBlob: Boolean
        get() = hasPrefix(PACK_BLOB_PREFIX) || hasPrefix(PACK_SPECIAL_PREFIX)

    /**
     * Returns true if this is an index blob (n prefix).
     */
    val isIndexBlob: Boolean
        get() = hasPrefix(INDEX_BLOB_PREFIX)

    /**
     * Returns true if this is a session blob (s prefix).
     */
    val isSessionBlob: Boolean
        get() = hasPrefix(SESSION_BLOB_PREFIX)

    override fun toString(): String = value

    companion object {
        /**
         * Prefix for pack blobs containing regular content data.
         */
        const val PACK_BLOB_PREFIX = "p"

        /**
         * Prefix for pack blobs containing special/metadata content.
         */
        const val PACK_SPECIAL_PREFIX = "q"

        /**
         * Prefix for index blobs containing content indexes.
         */
        const val INDEX_BLOB_PREFIX = "n"

        /**
         * Prefix for session blobs used for concurrent access tracking.
         */
        const val SESSION_BLOB_PREFIX = "s"

        /**
         * Prefix for index shard blobs.
         */
        const val INDEX_SHARD_PREFIX = "x"

        /**
         * Well-known blob ID for repository format configuration.
         */
        val REPOSITORY_FORMAT = BlobId("kopia.repository")

        /**
         * Well-known blob ID for repository blob configuration.
         */
        val REPOSITORY_BLOB = BlobId("kopia.blobcfg")

        /**
         * Creates a pack blob ID with the given suffix.
         */
        fun packBlob(suffix: String): BlobId = BlobId("$PACK_BLOB_PREFIX$suffix")

        /**
         * Creates a special pack blob ID with the given suffix.
         */
        fun packSpecialBlob(suffix: String): BlobId = BlobId("$PACK_SPECIAL_PREFIX$suffix")

        /**
         * Creates an index blob ID with the given suffix.
         */
        fun indexBlob(suffix: String): BlobId = BlobId("$INDEX_BLOB_PREFIX$suffix")

        /**
         * Creates a session blob ID with the given suffix.
         */
        fun sessionBlob(suffix: String): BlobId = BlobId("$SESSION_BLOB_PREFIX$suffix")
    }
}

package org.kopiaKt.core.blob

/**
 * Represents a unique identifier for a blob in storage.
 *
 * Blob IDs are string-based identifiers that follow a specific format
 * depending on the blob type (pack blob, index blob, etc.).
 */
@JvmInline
value class BlobId(val value: String) {
    init {
        require(value.isNotEmpty()) { "BlobId cannot be empty" }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Prefix for pack blobs containing content data.
         */
        const val PACK_BLOB_PREFIX = "p"

        /**
         * Prefix for index blobs containing content indexes.
         */
        const val INDEX_BLOB_PREFIX = "n"

        /**
         * Prefix for session blobs used for concurrent access tracking.
         */
        const val SESSION_BLOB_PREFIX = "s"

        /**
         * Creates a pack blob ID with the given suffix.
         */
        fun packBlob(suffix: String): BlobId = BlobId("$PACK_BLOB_PREFIX$suffix")

        /**
         * Creates an index blob ID with the given suffix.
         */
        fun indexBlob(suffix: String): BlobId = BlobId("$INDEX_BLOB_PREFIX$suffix")
    }
}

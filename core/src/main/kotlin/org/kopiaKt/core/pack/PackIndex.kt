package org.kopiaKt.core.pack

import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import java.io.Closeable

/**
 * Read-only interface for a pack content index.
 *
 * A pack index maps content IDs to their metadata (location, size, etc.)
 * within pack blobs. Entries are sorted lexicographically by content ID.
 */
interface PackIndex : Closeable {
    /**
     * Returns the approximate number of entries in the index.
     */
    fun approximateCount(): Int

    /**
     * Retrieves information about a specific content ID.
     *
     * @param contentId The content ID to look up
     * @return The content info if found, null otherwise
     */
    fun getInfo(contentId: ContentId): ContentInfo?

    /**
     * Iterates over all entries in the index in sorted order.
     *
     * @param startId Optional starting content ID (inclusive)
     * @param endId Optional ending content ID (exclusive)
     * @return Sequence of content info entries
     */
    fun iterate(
        startId: ContentId? = null,
        endId: ContentId? = null,
    ): Sequence<ContentInfo>

    /**
     * Closes the index and releases any resources.
     */
    override fun close()
}

/**
 * ID range for iterating over content IDs.
 */
data class IdRange(
    val startId: ContentId?,
    val endId: ContentId?,
) {
    companion object {
        /**
         * Range that includes all content IDs.
         */
        val ALL = IdRange(null, null)
    }
}

/**
 * Index version constants.
 */
object IndexVersion {
    const val V1 = 1
    const val V2 = 2
}

/**
 * Factory for opening pack indexes of any version.
 *
 * Automatically detects the index version from the header and
 * returns the appropriate implementation.
 */
object PackIndexFactory {
    /**
     * Opens a pack index from raw data, automatically detecting the version.
     *
     * @param data The raw index data
     * @param v1PerContentOverhead Encryption overhead for V1 indexes (used to compute original length)
     * @return The opened index
     * @throws IllegalArgumentException if the version is unsupported or data is invalid
     */
    fun open(data: ByteArray, v1PerContentOverhead: UInt = 0u): PackIndex {
        if (data.isEmpty()) {
            throw IllegalArgumentException("Index data is empty")
        }

        return when (val version = data[0].toInt() and 0xFF) {
            IndexVersion.V1 -> PackIndexV1.open(data, v1PerContentOverhead)
            IndexVersion.V2 -> PackIndexV2.open(data)
            else -> throw IllegalArgumentException("Unsupported index version: $version")
        }
    }

    /**
     * Builds an index with the specified version.
     *
     * @param entries The content info entries to include
     * @param version The index version to build (V1 or V2)
     * @return The serialized index data
     */
    fun build(entries: List<ContentInfo>, version: Int = IndexVersion.V2): ByteArray = when (version) {
        IndexVersion.V1 -> PackIndexV1.build(entries)
        IndexVersion.V2 -> PackIndexV2.build(entries)
        else -> throw IllegalArgumentException("Unsupported index version: $version")
    }
}

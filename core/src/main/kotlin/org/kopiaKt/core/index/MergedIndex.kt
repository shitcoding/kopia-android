package org.kopiaKt.core.index

import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.pack.PackIndex
import java.io.Closeable

/**
 * A merged view over multiple pack indexes.
 *
 * MergedIndex provides a unified interface to query content across multiple
 * index blobs. When the same content ID exists in multiple indexes, the most
 * recent entry (by timestamp) is returned.
 *
 * This is the primary interface for content lookup in a Kopia repository,
 * as content is typically distributed across many index blobs.
 *
 * Usage:
 * ```
 * val merged = MergedIndex(listOf(index1, index2, index3))
 * val info = merged.getInfo(contentId)
 * merged.close()
 * ```
 *
 * @property indexes The list of indexes to merge
 */
class MergedIndex(
    private val indexes: List<PackIndex>
) : Closeable {

    /**
     * Returns the approximate total count of entries across all indexes.
     *
     * Note: This may include duplicates if the same content ID appears
     * in multiple indexes.
     */
    fun approximateCount(): Int = indexes.sumOf { it.approximateCount() }

    /**
     * Looks up a content ID across all indexes.
     *
     * If the content ID exists in multiple indexes, returns the entry
     * with the highest timestamp (most recent). This implements Kopia's
     * "last writer wins" semantics.
     *
     * @param contentId The content ID to look up
     * @return The content info if found, null otherwise
     */
    fun getInfo(contentId: ContentId): ContentInfo? {
        var best: ContentInfo? = null

        for (index in indexes) {
            val info = index.getInfo(contentId)
            if (info != null) {
                if (best == null || info.timestampSeconds > best.timestampSeconds) {
                    best = info
                }
            }
        }

        return best
    }

    /**
     * Looks up multiple content IDs in a single operation.
     *
     * This is more efficient than calling getInfo multiple times when
     * looking up many content IDs.
     *
     * @param contentIds The content IDs to look up
     * @return Map of content ID to content info (only found entries included)
     */
    fun getInfoBatch(contentIds: Collection<ContentId>): Map<ContentId, ContentInfo> {
        val results = mutableMapOf<ContentId, ContentInfo>()

        for (contentId in contentIds) {
            val info = getInfo(contentId)
            if (info != null) {
                results[contentId] = info
            }
        }

        return results
    }

    /**
     * Iterates over all entries in the merged index.
     *
     * Entries are yielded in sorted order by content ID. When the same
     * content ID exists in multiple indexes, only the most recent entry
     * (by timestamp) is yielded.
     *
     * @param startId Optional starting content ID (inclusive)
     * @param endId Optional ending content ID (exclusive)
     * @return Sequence of deduplicated content info entries
     */
    fun iterate(
        startId: ContentId? = null,
        endId: ContentId? = null
    ): Sequence<ContentInfo> = sequence {
        // Merge-sort across all indexes
        val iterators = indexes.map {
            it.iterate(startId, endId).iterator()
        }.toMutableList()

        // Current entry from each iterator (null if exhausted)
        val currentEntries = iterators.map { if (it.hasNext()) it.next() else null }.toMutableList()

        // Track the last content ID yielded to deduplicate
        var lastYieldedId: ContentId? = null
        var lastYieldedEntry: ContentInfo? = null

        while (true) {
            // Find the minimum content ID across all current entries
            var minIdx = -1
            var minInfo: ContentInfo? = null

            for (i in currentEntries.indices) {
                val entry = currentEntries[i] ?: continue

                if (minInfo == null || entry.contentId.toString() < minInfo.contentId.toString()) {
                    minIdx = i
                    minInfo = entry
                }
            }

            // If no more entries, we're done
            if (minInfo == null) {
                // Yield the last accumulated entry if any
                if (lastYieldedEntry != null) {
                    yield(lastYieldedEntry)
                }
                break
            }

            // Advance the iterator that provided the minimum
            currentEntries[minIdx] = if (iterators[minIdx].hasNext()) {
                iterators[minIdx].next()
            } else {
                null
            }

            // Handle deduplication and timestamp-based selection
            if (lastYieldedId == null || minInfo.contentId != lastYieldedId) {
                // New content ID - yield the previous accumulated entry
                if (lastYieldedEntry != null) {
                    yield(lastYieldedEntry)
                }
                lastYieldedId = minInfo.contentId
                lastYieldedEntry = minInfo
            } else {
                // Same content ID - keep the one with higher timestamp
                if (minInfo.timestampSeconds > lastYieldedEntry!!.timestampSeconds) {
                    lastYieldedEntry = minInfo
                }
            }
        }
    }

    /**
     * Iterates over all entries without deduplication.
     *
     * This is useful for operations that need to see all entries,
     * including duplicates (e.g., for compaction analysis).
     *
     * @return Sequence of all content info entries from all indexes
     */
    fun iterateAll(): Sequence<ContentInfo> = sequence {
        for (index in indexes) {
            yieldAll(index.iterate())
        }
    }

    /**
     * Returns the number of underlying indexes.
     */
    fun indexCount(): Int = indexes.size

    /**
     * Closes all underlying indexes.
     */
    override fun close() {
        for (index in indexes) {
            try {
                index.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    companion object {
        /**
         * Creates an empty merged index.
         */
        fun empty(): MergedIndex = MergedIndex(emptyList())

        /**
         * Creates a merged index from a single pack index.
         */
        fun of(index: PackIndex): MergedIndex = MergedIndex(listOf(index))

        /**
         * Creates a merged index from multiple pack indexes.
         */
        fun of(vararg indexes: PackIndex): MergedIndex = MergedIndex(indexes.toList())
    }
}

/**
 * A builder for creating a MergedIndex incrementally.
 */
class MergedIndexBuilder {
    private val indexes = mutableListOf<PackIndex>()

    /**
     * Adds an index to the builder.
     */
    fun add(index: PackIndex): MergedIndexBuilder {
        indexes.add(index)
        return this
    }

    /**
     * Adds multiple indexes to the builder.
     */
    fun addAll(indexes: Collection<PackIndex>): MergedIndexBuilder {
        this.indexes.addAll(indexes)
        return this
    }

    /**
     * Returns the number of indexes added.
     */
    fun size(): Int = indexes.size

    /**
     * Builds the merged index.
     */
    fun build(): MergedIndex = MergedIndex(indexes.toList())
}

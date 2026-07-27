package org.kopiaKt.core.manifest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Manages JSON manifests in the repository.
 *
 * ManifestManager provides storage for typed JSON data with label-based querying.
 * It is used to store snapshot manifests, policies, and other metadata.
 *
 * Storage format:
 * - Manifests are serialized as JSON
 * - Compressed with GZIP before storage
 * - Written to ContentManager with prefix 'm'
 * - Multiple entries can be stored in a single content block
 *
 * Operations:
 * - put: Store a manifest with labels
 * - get: Retrieve a manifest by ID
 * - getMetadata: Get metadata without deserializing content
 * - find: Query manifests by labels
 * - delete: Mark a manifest for deletion
 * - flush: Persist pending changes to storage
 * - compact: Consolidate manifest content blocks
 * - refresh: Reload committed manifests from storage
 *
 * @property contentManager The underlying content storage
 * @property clock Clock for timestamps (default: system UTC)
 * @property autoCompactionThreshold Number of content blocks before auto-compact (default: 16)
 */
class ManifestManager(
    private val contentManager: ContentManager,
    private val clock: Clock = Clock.systemUTC(),
    private val autoCompactionThreshold: Int = DEFAULT_AUTO_COMPACTION_THRESHOLD,
) {
    private val mutex = Mutex()

    // Pending entries (not yet flushed)
    private val pendingEntries = mutableMapOf<ManifestId, ManifestEntry>()

    // Committed entries (loaded from storage)
    private val committedEntries = mutableMapOf<ManifestId, ManifestEntry>()

    // Content IDs of committed manifest blocks
    private val committedContentIds = mutableSetOf<ContentId>()

    // Whether the most recent load parsed every manifest content block. False if any malformed manifest
    // content was skipped — the manifest view is then partial (a snapshot could be hidden), which a
    // destructive caller (snapshot GC delete) must treat as fail-closed. See [isManifestLoadComplete].
    @Volatile
    private var manifestLoadComplete = true

    // Last revision of content manager when we loaded
    private var lastRevision: Long = -1

    // JSON serializer (internal for inline function access)
    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * Stores a manifest with the given labels.
     *
     * @param labels Labels for the manifest (must include "type")
     * @param payload The data to store (must be serializable)
     * @return The manifest ID
     * @throws IllegalArgumentException if "type" label is missing
     */
    suspend inline fun <reified T> put(labels: Map<String, String>, payload: T): ManifestId {
        require(labels.containsKey(TYPE_LABEL_KEY)) {
            "'$TYPE_LABEL_KEY' label is required"
        }

        val jsonPayload = json.encodeToJsonElement(payload)
        return putInternal(labels, jsonPayload)
    }

    /**
     * Stores a manifest with the given labels using a serializer.
     *
     * @param labels Labels for the manifest (must include "type")
     * @param payload The data to store
     * @param serializer The serializer for the payload type
     * @return The manifest ID
     * @throws IllegalArgumentException if "type" label is missing
     */
    suspend fun <T> putWithSerializer(labels: Map<String, String>, payload: T, serializer: kotlinx.serialization.KSerializer<T>): ManifestId {
        require(labels.containsKey(TYPE_LABEL_KEY)) {
            "'$TYPE_LABEL_KEY' label is required"
        }

        val jsonPayload = json.encodeToJsonElement(serializer, payload)
        return putInternal(labels, jsonPayload)
    }

    @PublishedApi
    internal suspend fun putInternal(labels: Map<String, String>, jsonPayload: JsonElement): ManifestId = mutex.withLock {
        val id = ManifestId.generate()
        val entry = ManifestEntry(
            id = id.value,
            labels = labels.toMap(),
            modTime = Instant.now(clock),
            deleted = false,
            content = jsonPayload,
        )
        pendingEntries[id] = entry
        id
    }

    /**
     * Retrieves a manifest by ID.
     *
     * @param id The manifest ID
     * @return Pair of (payload, metadata)
     * @throws ManifestNotFoundException if manifest doesn't exist
     */
    suspend inline fun <reified T> get(id: ManifestId): Pair<T, EntryMetadata> {
        val (contentJson, metadata) = getEntryContent(id) ?: throw ManifestNotFoundException(id)
        val payload = json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), contentJson)
        return payload to metadata
    }

    /**
     * Retrieves a manifest by ID with a serializer (for cases where reified types can't be used).
     *
     * @param id The manifest ID
     * @param serializer The serializer for the payload type
     * @return Pair of (payload, metadata)
     * @throws ManifestNotFoundException if manifest doesn't exist
     */
    suspend fun <T> getWithSerializer(id: ManifestId, serializer: kotlinx.serialization.KSerializer<T>): Pair<T, EntryMetadata> {
        val (contentJson, metadata) = getEntryContent(id) ?: throw ManifestNotFoundException(id)
        val payload = json.decodeFromJsonElement(serializer, contentJson)
        return payload to metadata
    }

    /**
     * Returns the raw JSON content and metadata for a manifest.
     * Returns null if the manifest doesn't exist or is deleted.
     */
    @PublishedApi
    internal suspend fun getEntryContent(id: ManifestId): Pair<JsonElement, EntryMetadata>? = mutex.withLock {
        // Check pending first
        pendingEntries[id]?.let { entry ->
            if (entry.deleted) return null
            val content = entry.content ?: return null
            return content to entry.toMetadata(id)
        }

        // Check committed
        committedEntries[id]?.let { entry ->
            if (entry.deleted) return null
            val content = entry.content ?: return null
            return content to entry.toMetadata(id)
        }

        null
    }

    internal suspend fun getEntry(id: ManifestId): ManifestEntry? = mutex.withLock {
        // Check pending first
        pendingEntries[id]?.let { entry ->
            if (entry.deleted) return null
            return entry
        }

        // Check committed
        committedEntries[id]?.let { entry ->
            if (entry.deleted) return null
            return entry
        }

        null
    }

    /**
     * Gets metadata for a manifest without deserializing the content.
     *
     * @param id The manifest ID
     * @return The metadata
     * @throws ManifestNotFoundException if manifest doesn't exist
     */
    suspend fun getMetadata(id: ManifestId): EntryMetadata = getMetadataOrNull(id) ?: throw ManifestNotFoundException(id)

    /**
     * Gets metadata for a manifest, or null if not found.
     *
     * @param id The manifest ID
     * @return The metadata, or null if not found
     */
    suspend fun getMetadataOrNull(id: ManifestId): EntryMetadata? = mutex.withLock {
        // Check pending first
        pendingEntries[id]?.let { entry ->
            if (entry.deleted) return null
            return entry.toMetadata(id)
        }

        // Check committed
        committedEntries[id]?.let { entry ->
            if (entry.deleted) return null
            return entry.toMetadata(id)
        }

        null
    }

    /**
     * Finds manifests matching all provided labels.
     *
     * @param labels Labels to match (all must match)
     * @return List of matching metadata, sorted by modification time
     */
    suspend fun find(labels: Map<String, String>): List<EntryMetadata> = mutex.withLock {
        val results = mutableListOf<EntryMetadata>()
        val seenIds = mutableSetOf<ManifestId>()

        // Check pending entries
        for ((id, entry) in pendingEntries) {
            if (!entry.deleted && matchesLabels(entry.labels, labels)) {
                results.add(entry.toMetadata(id))
                seenIds.add(id)
            }
        }

        // Check committed entries (skip if in pending)
        for ((id, entry) in committedEntries) {
            if (id !in seenIds && !entry.deleted && matchesLabels(entry.labels, labels)) {
                results.add(entry.toMetadata(id))
            }
        }

        // Sort by modification time
        results.sortWith(compareBy({ it.modTime }, { it.id.value }))
        results
    }

    /**
     * Marks a manifest for deletion.
     *
     * The deletion is not persisted until flush() is called.
     *
     * @param id The manifest ID to delete
     */
    suspend fun delete(id: ManifestId): Unit = mutex.withLock {
        val entry = ManifestEntry(
            id = id.value,
            labels = emptyMap(),
            modTime = Instant.now(clock),
            deleted = true,
            content = null,
        )
        pendingEntries[id] = entry
    }

    /**
     * Flushes pending entries to storage.
     *
     * This serializes all pending entries to a single manifest content block
     * and writes it to the content manager.
     */
    suspend fun flush(): Unit = mutex.withLock {
        if (pendingEntries.isEmpty()) return

        // Build manifest container
        val container = ManifestContainer(entries = pendingEntries.values.toList())

        // Serialize to JSON
        val jsonData = json.encodeToString(container)

        // Compress with GZIP
        val compressedData = gzipCompress(jsonData.toByteArray(Charsets.UTF_8))

        // Write to content manager with 'm' prefix
        // Data is already GZIP compressed, so don't apply additional compression
        val contentId = contentManager.writeContent(
            data = compressedData,
            prefix = CONTENT_PREFIX,
            compression = CompressionAlgorithm.NONE,
        )

        // Move pending to committed
        for ((id, entry) in pendingEntries) {
            if (entry.deleted) {
                committedEntries.remove(id)
            } else {
                committedEntries[id] = entry
            }
        }
        committedContentIds.add(contentId)
        pendingEntries.clear()

        // Check for auto-compaction
        if (committedContentIds.size >= autoCompactionThreshold) {
            compactInternal()
        }
    }

    /**
     * Reloads committed manifests from storage.
     *
     * Call this after creating a new ManifestManager or after external
     * changes to the repository.
     */
    suspend fun refresh(): Unit = mutex.withLock {
        loadCommittedManifests()
    }

    /**
     * Whether the most recent load parsed every manifest content block (no block was skipped as
     * malformed). A false result means the manifest view is partial — a snapshot may be hidden — so a
     * destructive caller (snapshot GC delete) MUST NOT act on it. See task-9.
     */
    fun isManifestLoadComplete(): Boolean = manifestLoadComplete

    /**
     * Compacts manifest content blocks.
     *
     * This consolidates all committed manifest blocks into a single block,
     * removing deleted entries permanently.
     */
    suspend fun compact(): Unit = mutex.withLock {
        compactInternal()
    }

    private suspend fun compactInternal() {
        if (committedContentIds.size <= 1) return

        // A compacted block records a deletion by ABSENCE: tombstone entries never reach
        // committedEntries (flush drops them, loadCommittedManifests strips them after merging), so the
        // container written below simply omits deleted manifests. That is only sound if EVERY block it
        // supersedes actually goes away. If one survives, the next refresh merges its stale LIVE entry
        // back over the absence and the deleted manifest returns from the dead — undoing a snapshot
        // deletion and re-protecting its content from GC. Compaction is only an optimization, so when
        // any superseded block cannot be tombstoned, decline the whole compaction and leave the old
        // accumulate-forever state, which is safe.
        if (!canTombstoneAll(committedContentIds)) {
            logger.log(
                Level.WARNING,
                "Skipping manifest compaction: a superseded content block cannot be tombstoned by the " +
                    "V1 index writer, and a partial compaction would resurrect deleted manifests",
            )
            return
        }

        // Collect all non-deleted entries
        val entriesToKeep = committedEntries.filterValues { !it.deleted }

        if (entriesToKeep.isEmpty()) {
            deleteSupersededContent(committedContentIds.toList())
            committedContentIds.clear()
            committedEntries.clear()
            return
        }

        // Build new manifest container
        val container = ManifestContainer(entries = entriesToKeep.values.toList())

        // Serialize and compress
        val jsonData = json.encodeToString(container)
        val compressedData = gzipCompress(jsonData.toByteArray(Charsets.UTF_8))

        // Write new content
        // Data is already GZIP compressed, so don't apply additional compression
        val newContentId = contentManager.writeContent(
            data = compressedData,
            prefix = CONTENT_PREFIX,
            compression = CompressionAlgorithm.NONE,
        )

        // Delete the blocks this one supersedes, as Go does. Snapshot GC classifies 'm' content as
        // system content and always keeps it, so without this they accumulate forever, growing the
        // repository and the cost of every refresh. The new block is written FIRST and both the write
        // and the tombstones land in the same flush, so a crash cannot leave the manifests unreadable.
        deleteSupersededContent(committedContentIds.filter { it != newContentId })

        committedContentIds.clear()
        committedContentIds.add(newContentId)

        // Update committed entries to only keep non-deleted
        committedEntries.clear()
        committedEntries.putAll(entriesToKeep.mapKeys { it.key })
    }

    /**
     * Whether every one of [contentIds] could be tombstoned by the index writer.
     *
     * `deleteContent` only QUEUES a tombstone cloned from the current entry; `PackIndexV1.build` then
     * refuses entries carrying a non-zero compression header or encryption key id — and it refuses
     * them at FLUSH, far from here, taking down the whole flush rather than just this cleanup. Content
     * written by this implementation is always content-level uncompressed (production sets
     * defaultCompression = NONE and this class passes NONE explicitly), so this is always true for a
     * Kotlin-only repository; Go DOES write manifest content with zstd, so a shared repository can
     * contain blocks that fail the test.
     */
    private suspend fun canTombstoneAll(contentIds: Collection<ContentId>): Boolean = contentIds.all { id ->
        val info = contentManager.getContentInfo(id)
        info == null || info.compressionHeaderId == 0 && info.encryptionKeyId.toInt() == 0
    }

    /**
     * Tombstones manifest content blocks that a compaction has superseded. Callers MUST have checked
     * [canTombstoneAll] first — a partial tombstoning resurrects deleted manifests (see
     * [compactInternal]).
     */
    private suspend fun deleteSupersededContent(contentIds: List<ContentId>) {
        for (contentId in contentIds) {
            contentManager.deleteContent(contentId)
        }
    }

    private suspend fun loadCommittedManifests() {
        committedEntries.clear()
        committedContentIds.clear()
        // Assume complete until a manifest content block fails to parse below. Note that the content
        // load inside contentManager.refresh() maintains its OWN completeness flag
        // (ContentManager.isIndexLoadComplete) — this flag covers only manifest-content decode failures.
        manifestLoadComplete = true

        // First refresh the content manager to load new indexes
        contentManager.refresh()

        // Iterate all manifest content (prefix 'm')
        contentManager.iterateContents(CONTENT_PREFIX) { contentId ->
            try {
                val compressedData = contentManager.getContent(contentId)
                val jsonData = gzipDecompress(compressedData)
                val jsonString = jsonData.toString(Charsets.UTF_8)
                val container = json.decodeFromString<ManifestContainer>(jsonString)

                committedContentIds.add(contentId)

                // Merge entries (newer entries override older ones)
                for (entry in container.entries) {
                    val manifestId = ManifestId(entry.id)
                    val existing = committedEntries[manifestId]

                    // Keep the newer entry by modification time
                    if (existing == null || entry.modTime.isAfter(existing.modTime)) {
                        committedEntries[manifestId] = entry
                    }
                }
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                // Skip malformed manifest content so one bad blob can't fail the whole load, but log
                // it AND mark the load incomplete — silently dropping it hides data loss (a hidden
                // snapshot manifest makes its content look unreferenced to GC). isManifestLoadComplete()
                // now returns false so a destructive GC delete run fails closed. (Go keeps the skip
                // non-fatal, gated by KOPIA_IGNORE_MALFORMED_MANIFEST_CONTENTS.)
                manifestLoadComplete = false
                logger.log(
                    Level.WARNING,
                    "Skipping malformed manifest content $contentId: ${e.message}",
                    e,
                )
            }
        }

        // Remove deleted entries after merging all content
        val deletedIds = committedEntries.filterValues { it.deleted }.keys.toSet()
        for (id in deletedIds) {
            committedEntries.remove(id)
        }
    }

    // === Private Helpers ===

    private fun matchesLabels(entryLabels: Map<String, String>, queryLabels: Map<String, String>): Boolean {
        for ((key, value) in queryLabels) {
            if (entryLabels[key] != value) {
                return false
            }
        }
        return true
    }

    private fun ManifestEntry.toMetadata(id: ManifestId): EntryMetadata {
        val contentLength = content?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
        return EntryMetadata(
            id = id,
            length = contentLength,
            labels = labels.toMap(),
            modTime = modTime,
        )
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(data)
        }
        return baos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(data)
        return GZIPInputStream(bais).use { gzip ->
            gzip.readBytes()
        }
    }

    /**
     * Returns the number of committed content blocks being tracked.
     * For testing purposes only.
     */
    internal suspend fun getCommittedContentCount(): Int = mutex.withLock {
        committedContentIds.size
    }

    companion object {
        private val logger = Logger.getLogger(ManifestManager::class.java.name)

        const val CONTENT_PREFIX = 'm'
        const val TYPE_LABEL_KEY = "type"
        const val DEFAULT_AUTO_COMPACTION_THRESHOLD = 16

        /**
         * Deduplicates entries by a label, keeping the latest for each value.
         *
         * @param entries List of entries to deduplicate
         * @param label The label to deduplicate by
         * @return Deduplicated list, sorted by modification time
         */
        fun dedupeByLabel(entries: List<EntryMetadata>, label: String): List<EntryMetadata> {
            val byLabel = mutableMapOf<String, EntryMetadata>()

            for (entry in entries) {
                val labelValue = entry.labels[label] ?: ""
                val existing = byLabel[labelValue]
                if (existing == null || isLaterThan(entry, existing)) {
                    byLabel[labelValue] = entry
                }
            }

            return byLabel.values
                .sortedWith(compareBy({ it.modTime }, { it.id.value }))
        }

        /**
         * Returns the latest entry from a list.
         *
         * @param entries List of entries
         * @return The latest entry, or null if empty
         */
        fun pickLatest(entries: List<EntryMetadata>): EntryMetadata? = entries.maxWithOrNull(
            Comparator { a, b ->
                when {
                    a.modTime != b.modTime -> a.modTime.compareTo(b.modTime)
                    else -> a.id.value.compareTo(b.id.value)
                }
            },
        )

        /**
         * Returns true if a is later than b by modification time,
         * using ID as tiebreaker.
         */
        private fun isLaterThan(a: EntryMetadata, b: EntryMetadata?): Boolean {
            if (b == null) return true
            if (a.modTime.isAfter(b.modTime)) return true
            if (a.modTime.isBefore(b.modTime)) return false
            return a.id.value > b.id.value
        }
    }
}

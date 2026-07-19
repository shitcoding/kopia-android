package org.kopiaKt.snapshot.maintenance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.DirectRepositoryWriter
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.snapshotfs.isDirectoryId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Statistics from a snapshot garbage collection run.
 *
 * Go type: snapshotgc.Stats
 */
data class SnapshotGCStats(
    /**
     * Number of contents that are not referenced by any snapshot.
     */
    val unreferencedContentCount: Int = 0,

    /**
     * Total size of unreferenced contents.
     */
    val unreferencedContentSize: Long = 0,

    /**
     * Number of contents that were marked as deleted.
     */
    val deletedContentCount: Int = 0,

    /**
     * Total size of deleted contents.
     */
    val deletedContentSize: Long = 0,

    /**
     * Number of unreferenced contents that are too recent to delete.
     */
    val unreferencedRecentContentCount: Int = 0,

    /**
     * Total size of recent unreferenced contents.
     */
    val unreferencedRecentContentSize: Long = 0,

    /**
     * Number of contents that are in use by snapshots.
     */
    val inUseContentCount: Int = 0,

    /**
     * Total size of in-use contents.
     */
    val inUseContentSize: Long = 0,

    /**
     * Number of system (manifest) contents that are in use.
     */
    val inUseSystemContentCount: Int = 0,

    /**
     * Total size of system contents.
     */
    val inUseSystemContentSize: Long = 0,

    /**
     * Number of contents that were recovered (undeleted).
     */
    val recoveredContentCount: Int = 0,

    /**
     * Total size of recovered contents.
     */
    val recoveredContentSize: Long = 0
)

/**
 * Options for garbage collection.
 */
data class GCOptions(
    /**
     * Whether to actually delete unreferenced content.
     * If false, only reports what would be deleted (dry run).
     *
     * NOTE: Phase 2 (content sweep/delete) is not implemented, so delete=true currently throws
     * [UnsupportedOperationException] from [SnapshotGC.run] rather than silently no-op'ing. See task-9.
     */
    val delete: Boolean = false,

    /**
     * Safety parameters for GC timing.
     */
    val safety: SafetyParameters = SafetyParameters.Default,

    /**
     * Progress callback for GC operations.
     */
    val onProgress: ((GCProgress) -> Unit)? = null
)

/**
 * Progress information during GC.
 */
data class GCProgress(
    val phase: String,
    val processedSnapshots: Int = 0,
    val totalSnapshots: Int = 0,
    val processedContents: Int = 0,
    val inUseContents: Int = 0
)

/**
 * Performs garbage collection on snapshot content.
 *
 * The GC algorithm has two phases:
 * 1. Build a set of all content IDs that are in use by walking all snapshot trees
 * 2. Iterate all content and mark/delete those not in the in-use set
 *
 * Go implementation: snapshot/snapshotgc/gc.go
 */
class SnapshotGC(
    private val repository: DirectRepository
) {
    /**
     * Runs garbage collection.
     *
     * @param options GC options
     * @return Statistics about the GC run
     */
    suspend fun run(options: GCOptions = GCOptions()): SnapshotGCStats {
        // Phase 2 (content sweep/delete) is not implemented: iterateContentByPrefix is a stub, so no
        // content is ever reclaimed. Fail loudly rather than let a caller believe delete=true freed
        // storage — it would silently no-op while the repository keeps growing. Dry runs (delete=false)
        // still execute Phase 1. See backlog task-9 (Phase 2 needs new ContentManager soft-delete APIs).
        if (options.delete) {
            throw UnsupportedOperationException(
                "GC content deletion (Phase 2) is not yet implemented; run with delete=false. " +
                    "Deleting snapshot manifests currently reclaims no content."
            )
        }
        return withContext(Dispatchers.Default) {
            runGC(options)
        }
    }

    private suspend fun runGC(options: GCOptions): SnapshotGCStats {
        // Phase 1: Build in-use content set
        options.onProgress?.invoke(GCProgress("Loading snapshots", 0, 0))
        val inUseSet = buildInUseSet(options.onProgress)

        try {
            // Phase 2: Find and mark unreferenced content
            options.onProgress?.invoke(
                GCProgress(
                    "Scanning content",
                    inUseContents = inUseSet.size().toInt()
                )
            )

            return findAndProcessUnreferencedContent(inUseSet, options)
        } finally {
            inUseSet.close()
        }
    }

    /**
     * Phase 1 of GC: walks every snapshot tree and builds the set of content IDs that are still
     * referenced ("in use"). The caller owns the returned set and MUST close it.
     *
     * Exposed (internal) so the in-use set can be asserted directly in tests. Phase 2 is still a
     * stub, so an under-collected in-use set here is otherwise invisible through [run] (all stats
     * are zero). Correctly collecting the in-use set is the whole safety property of GC — deleting
     * anything not in this set would drop live data. See task-9.
     *
     * ⚠ FAILS OPEN by design (dry-run only). [loadAllSnapshots], [collectInUseContent] and
     * [walkObjectTree] SKIP snapshots/objects they cannot read or parse (unreadable manifest, missing
     * pack blob, malformed directory JSON, or a `DirEntry.type=UNKNOWN` over a prefix-less directory),
     * so a transient/partial read error under-collects the in-use set instead of aborting. That is
     * acceptable while GC never deletes (Phase 2 is a stub and [run] throws on `delete=true`), because
     * a dry run should report best-effort rather than abort. It is a HARD PREREQUISITE that Phase 2
     * (real deletion) make this path FAIL CLOSED — any snapshot-load / object-read / parse failure
     * during the in-use walk must abort the delete run before removing anything. See task-9.
     */
    internal suspend fun buildInUseSet(
        onProgress: ((GCProgress) -> Unit)? = null
    ): InUseContentSet {
        val inUseSet = InUseContentSetFactory.create()
        // The caller owns the returned set, but if Phase 1 throws mid-build we must close it here —
        // otherwise the set leaks (it is Closeable and may become disk-backed). This restores the
        // close-on-failure guarantee the previous inline try/finally in runGC had.
        try {
            val snapshots = loadAllSnapshots()
            val totalSnapshots = snapshots.size

            onProgress?.invoke(GCProgress("Walking snapshot trees", 0, totalSnapshots))

            var processedSnapshots = 0
            for (snapshot in snapshots) {
                collectInUseContent(snapshot, inUseSet)
                processedSnapshots++
                onProgress?.invoke(
                    GCProgress(
                        "Walking snapshot trees",
                        processedSnapshots,
                        totalSnapshots,
                        inUseContents = inUseSet.size().toInt()
                    )
                )
            }

            return inUseSet
        } catch (t: Throwable) {
            inUseSet.close()
            throw t
        }
    }

    private suspend fun loadAllSnapshots(): List<SnapshotManifest> {
        val manifests = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
        )

        return manifests.mapNotNull { metadata ->
            try {
                repository.getManifest(metadata.id, SnapshotManifest.serializer()).first
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                // Skip invalid manifests
                null
            }
        }
    }

    private suspend fun collectInUseContent(snapshot: SnapshotManifest, inUseSet: InUseContentSet) {
        val rootEntry = snapshot.rootEntry ?: return
        val rootObjectIdStr = rootEntry.objectId ?: return

        val rootObjectId = try {
            ObjectId.parse(rootObjectIdStr)
        } catch (e: Exception) {
            return
        }

        // Walk the snapshot tree and collect all content IDs. The snapshot root is a directory; trust
        // the manifest's declared entry type (with an object-id-prefix fallback) to decide recursion.
        walkObjectTree(rootObjectId, isDirectory(rootEntry, rootObjectId), inUseSet)
    }

    private suspend fun walkObjectTree(
        objectId: ObjectId,
        isDirectory: Boolean,
        inUseSet: InUseContentSet
    ) {
        // Get all content IDs backing this object
        val contentIds = try {
            repository.verifyObject(objectId)
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            // Object not found or corrupted (fails open — see buildInUseSet: dry-run only)
            return
        }

        // Add all content IDs to the in-use set
        for (contentId in contentIds) {
            inUseSet.add(contentId)
        }

        // Only directories have child entries to recurse into.
        if (!isDirectory) {
            return
        }

        val dirManifest = try {
            val data = repository.readObject(objectId)
            DirManifest.fromJson(data.decodeToString())
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            // Unreadable / non-directory JSON (fails open — see buildInUseSet: dry-run only)
            return
        }

        // ponytail: no visited-set, so a subtree shared across N incremental snapshots is re-walked
        // N times (correctness is fine — set semantics, content is hash-addressed/acyclic — but it is
        // O(references) not O(unique objects)). Thread a HashSet<ObjectId> visited-set through the walk
        // if Phase-1 cost matters on-device.
        for (entry in dirManifest.entries) {
            val childOid = entry.objectId ?: continue
            val childObjectId = try {
                ObjectId.parse(childOid)
            } catch (e: Exception) {
                continue
            }
            walkObjectTree(childObjectId, isDirectory(entry, childObjectId), inUseSet)
        }
    }

    /**
     * Whether [entry] refers to a directory object. Traversal keys on the manifest's declared
     * [DirEntry.type] first, because production directory objects are written WITHOUT the 'k' content
     * prefix, so [isDirectoryId] alone would miss them and under-collect the in-use set — a data-loss
     * hazard once GC deletes. The [isDirectoryId] fallback still catches a directory declared with an
     * UNKNOWN type but a 'k'-prefixed / indirect object id. The union never under-collects: a false
     * positive merely tries to parse a non-directory as a manifest, which fails and stops harmlessly
     * (that object's own content was already recorded). See task-9.
     */
    private fun isDirectory(entry: DirEntry, objectId: ObjectId): Boolean =
        entry.type == EntryType.DIRECTORY || isDirectoryId(objectId)

    private suspend fun findAndProcessUnreferencedContent(
        inUseSet: InUseContentSet,
        options: GCOptions
    ): SnapshotGCStats {
        val now = Instant.now()
        val minAge = options.safety.minContentAgeSubjectToGC

        val unreferencedCount = AtomicInteger(0)
        val unreferencedSize = AtomicLong(0)
        val deletedCount = AtomicInteger(0)
        val deletedSize = AtomicLong(0)
        val recentCount = AtomicInteger(0)
        val recentSize = AtomicLong(0)
        val inUseCount = AtomicInteger(0)
        val inUseSize = AtomicLong(0)
        val systemCount = AtomicInteger(0)
        val systemSize = AtomicLong(0)
        val recoveredCount = AtomicInteger(0)
        val recoveredSize = AtomicLong(0)

        // Note: In a full implementation, we would need a method on ContentManager
        // to iterate all content including deleted. For now, we iterate known prefixes.
        // This is a simplified version - the real Go implementation uses
        // rep.ContentReader().IterateContents() with IncludeDeleted option.

        // Process manifest content (prefix 'm')
        iterateContentByPrefix('m') { info ->
            val contentSize = info.originalLength.toLong()
            systemCount.incrementAndGet()
            systemSize.addAndGet(contentSize)
        }

        // Process regular content (no prefix - but we check common prefixes)
        for (prefix in listOf(null, 'k', 'p', 'x')) {
            val prefixChar = prefix ?: continue // Skip null for now
            iterateContentByPrefix(prefixChar) { info ->
                processContent(
                    info, inUseSet, options, now, minAge,
                    unreferencedCount, unreferencedSize,
                    deletedCount, deletedSize,
                    recentCount, recentSize,
                    inUseCount, inUseSize,
                    recoveredCount, recoveredSize
                )
            }
        }

        return SnapshotGCStats(
            unreferencedContentCount = unreferencedCount.get(),
            unreferencedContentSize = unreferencedSize.get(),
            deletedContentCount = deletedCount.get(),
            deletedContentSize = deletedSize.get(),
            unreferencedRecentContentCount = recentCount.get(),
            unreferencedRecentContentSize = recentSize.get(),
            inUseContentCount = inUseCount.get(),
            inUseContentSize = inUseSize.get(),
            inUseSystemContentCount = systemCount.get(),
            inUseSystemContentSize = systemSize.get(),
            recoveredContentCount = recoveredCount.get(),
            recoveredContentSize = recoveredSize.get()
        )
    }

    private suspend fun iterateContentByPrefix(prefix: Char, callback: suspend (ContentInfo) -> Unit) {
        // Note: This requires access to ContentManager's iterateContents
        // In the actual implementation, we would call:
        // contentManager.iterateContentsWithInfo(prefix, includeDeleted = true, callback)

        // For now, this is a placeholder that would need ContentManager enhancement
        // to support iterating with full ContentInfo including deleted flag
    }

    private suspend fun processContent(
        info: ContentInfo,
        inUseSet: InUseContentSet,
        options: GCOptions,
        now: Instant,
        minAge: Duration,
        unreferencedCount: AtomicInteger,
        unreferencedSize: AtomicLong,
        deletedCount: AtomicInteger,
        deletedSize: AtomicLong,
        recentCount: AtomicInteger,
        recentSize: AtomicLong,
        inUseCount: AtomicInteger,
        inUseSize: AtomicLong,
        recoveredCount: AtomicInteger,
        recoveredSize: AtomicLong
    ) {
        val contentId = info.contentId
        val contentSize = info.originalLength.toLong()
        val contentTime = Instant.ofEpochSecond(info.timestampSeconds)

        if (inUseSet.contains(contentId)) {
            // Content is in use
            inUseCount.incrementAndGet()
            inUseSize.addAndGet(contentSize)

            // If content was previously marked as deleted, recover it
            // Note: This requires ContentManager to track deleted flag
            // and provide an undelete method
        } else {
            // Content is not in use
            unreferencedCount.incrementAndGet()
            unreferencedSize.addAndGet(contentSize)

            // Check if content is too recent to delete
            val age = Duration.between(contentTime, now)
            if (age < minAge) {
                recentCount.incrementAndGet()
                recentSize.addAndGet(contentSize)
            } else if (options.delete) {
                // Delete the content
                // Note: This requires ContentManager.deleteContent method
                deletedCount.incrementAndGet()
                deletedSize.addAndGet(contentSize)
            }
        }
    }

    companion object {
        /**
         * Content ID prefix for manifest content.
         */
        const val MANIFEST_CONTENT_PREFIX = 'm'
    }
}

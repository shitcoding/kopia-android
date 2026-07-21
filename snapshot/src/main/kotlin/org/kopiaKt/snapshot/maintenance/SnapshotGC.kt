package org.kopiaKt.snapshot.maintenance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     *
     * Always 0 today: Go's in-GC self-heal (undelete referenced-but-deleted content) is not performed
     * here. Such content instead trips the fail-closed walk and aborts a delete run. See [run]. (task-9)
     */
    val recoveredContentCount: Int = 0,

    /**
     * Total size of recovered contents.
     */
    val recoveredContentSize: Long = 0,
)

/**
 * Options for garbage collection.
 */
data class GCOptions(
    /**
     * Whether to actually delete unreferenced content.
     * If false, only reports what would be deleted (dry run).
     */
    val delete: Boolean = false,

    /**
     * Safety parameters for GC timing.
     */
    val safety: SafetyParameters = SafetyParameters.Default,

    /**
     * Progress callback for GC operations.
     */
    val onProgress: ((GCProgress) -> Unit)? = null,
)

/**
 * Progress information during GC.
 */
data class GCProgress(
    val phase: String,
    val processedSnapshots: Int = 0,
    val totalSnapshots: Int = 0,
    val processedContents: Int = 0,
    val inUseContents: Int = 0,
)

/**
 * Performs garbage collection on snapshot content.
 *
 * The GC algorithm has two phases:
 * 1. Build a set of all content IDs that are in use by walking all snapshot trees.
 * 2. Iterate all content and delete those not in the in-use set (soft-delete tombstones).
 *
 * Go implementation: snapshot/snapshotgc/gc.go
 *
 * @param repository the repository to garbage-collect. A delete run ([GCOptions.delete] == true)
 *   requires this to be a [DirectRepositoryWriter] (deletion writes tombstones and flushes).
 * @param maxDirectoryManifestSize the largest object (bytes) GC will load and parse as a directory
 *   manifest. Guards against OOM when a corrupt/lying `DirEntry(type=DIRECTORY)` points at a huge file
 *   object. A real directory manifest is far below this cap. See [readDirectoryManifest].
 */
class SnapshotGC(
    private val repository: DirectRepository,
    private val maxDirectoryManifestSize: Long = MAX_DIRECTORY_MANIFEST_SIZE,
) {
    /**
     * Runs garbage collection.
     *
     * A delete run reclaims unreferenced content by soft-deleting it (Go-compatible tombstones) and
     * flushing. It requires a writable [DirectRepositoryWriter] and builds the in-use set FAIL CLOSED:
     * any snapshot/object that cannot be loaded, verified, or parsed aborts the run before anything is
     * deleted, because a partial in-use set would drop live data. A dry run (`delete == false`) only
     * reads, is best-effort (fail open), and never mutates the repository.
     *
     * ⚠ CONCURRENCY CONTRACT (delete runs): the caller MUST guarantee that NO backup / write session
     * runs against the repository for the duration of a delete run. A delete run builds ONE point-in-time
     * in-use set and then tombstones content missing from it. If a concurrent backup de-duplicates against
     * old content `C` (reusing it WITHOUT rewriting it, so `C` keeps its old timestamp) after GC captured
     * its view but before `C` is tombstoned, GC will soft-delete `C` while the new snapshot references it —
     * that snapshot then fails to restore. This is the standard GC-vs-backup race; the fix is exclusive
     * maintenance, NOT more logic here. Go additionally hardens this with `requireTwoGCCycles` +
     * `MarginBetweenSnapshotGC` (a tombstone is only physically dropped after a second cycle confirms it,
     * giving the recovery/undelete pass time to revive re-referenced content) — neither the two-cycle
     * margin nor the recovery pass is implemented here yet. Until they are, the ON-DEVICE wiring (task-14)
     * MUST serialize backup and delete-GC (e.g. a repository maintenance lock / unique WorkManager work).
     * There is no production caller today (`MaintenanceRunner` still refuses `gcDelete=true`), so this
     * primitive is enabled + tested but not yet reachable from a concurrent path. See task-9 / task-14.
     *
     * Note on recovery: Go additionally un-deletes content that is referenced but marked deleted (a
     * self-heal for an abnormal state). Here such content instead makes the fail-closed walk abort
     * (`verifyObject` cannot resolve a tombstoned content), which is safe — GC refuses rather than
     * proceeds on a corrupt state. Active in-GC recovery is deferred (needs a verifyObject-with-deleted
     * variant); the `undeleteContent` primitive exists for a future recovery pass. See task-9.
     *
     * @param options GC options
     * @return Statistics about the GC run
     */
    suspend fun run(options: GCOptions = GCOptions()): SnapshotGCStats {
        val writer = if (options.delete) {
            // Fail fast (before the whole Phase-1 walk) if this repository cannot be written to. A
            // read-only-opened DirectRepositoryImpl still implements DirectRepositoryWriter, so the cast
            // alone would only surface the failure at the first deleteContent's checkWritable().
            check(!repository.clientOptions().readOnly) {
                "GC content deletion requires a writable repository, but it was opened read-only."
            }
            repository as? DirectRepositoryWriter
                ?: throw IllegalStateException(
                    "GC content deletion requires a DirectRepositoryWriter.",
                )
        } else {
            null
        }
        return withContext(Dispatchers.Default) {
            if (writer != null) {
                // Fail closed on a PARTIAL repository view before deleting anything. Reload the index +
                // manifest state and refuse to delete if any index blob or manifest content was skipped
                // as unreadable: a hidden snapshot would make its still-referenced content look
                // unreferenced and get tombstoned. buildInUseSet's per-object fail-closed walk cannot
                // catch this — a hidden snapshot is simply absent from findManifests. See task-9.
                repository.refresh()
                check(repository.lastLoadWasComplete()) {
                    "Refusing to run delete-GC: the repository index/manifest load was incomplete " +
                        "(an unreadable index blob or malformed manifest content was skipped). A partial " +
                        "view could tombstone live data. Repair or fully load the repository first."
                }
            }
            runGC(options, writer)
        }
    }

    private suspend fun runGC(options: GCOptions, writer: DirectRepositoryWriter?): SnapshotGCStats {
        // Capture a single reference instant at the START of the run (before Phase 1), matching Go's
        // maintenanceStartTime — every content's age is measured against it, and measuring from run start
        // (not Phase-2 start) is strictly more conservative about what is "too recent" to delete. Uses the
        // repository's injected clock.
        val now = repository.time()

        // Phase 1: Build in-use content set. Fail closed only when we are going to delete.
        options.onProgress?.invoke(GCProgress("Loading snapshots", 0, 0))
        val inUseSet = buildInUseSet(options.onProgress, failClosed = options.delete)

        try {
            // Phase 2: Find and (optionally) delete unreferenced content.
            options.onProgress?.invoke(
                GCProgress(
                    "Scanning content",
                    inUseContents = inUseSet.size().toInt(),
                ),
            )

            val stats = findAndProcessUnreferencedContent(inUseSet, writer, options, now)

            // Persist tombstones written during the sweep. Harmless no-op when nothing was deleted
            // (flushIndex returns early on an empty written set).
            writer?.flush()

            return stats
        } finally {
            inUseSet.close()
        }
    }

    /**
     * Phase 1 of GC: walks every snapshot tree and builds the set of content IDs that are still
     * referenced ("in use"). The caller owns the returned set and MUST close it.
     *
     * Exposed (internal) so the in-use set can be asserted directly in tests. Correctly collecting the
     * in-use set is the whole safety property of GC — deleting anything not in this set would drop live
     * data. See task-9.
     *
     * @param failClosed when true (a delete run), any snapshot-load / object-read / parse failure during
     *   the walk is RETHROWN so the caller aborts before deleting — a partial in-use set could drop live
     *   data. When false (a dry run), such failures are SKIPPED (best-effort), so a transient/partial read
     *   error under-collects the in-use set instead of aborting. `CancellationException` is always
     *   propagated regardless of this flag (never swallow coroutine cancellation).
     */
    internal suspend fun buildInUseSet(
        onProgress: ((GCProgress) -> Unit)? = null,
        failClosed: Boolean = false,
    ): InUseContentSet {
        val inUseSet = InUseContentSetFactory.create()
        // The caller owns the returned set, but if Phase 1 throws mid-build we must close it here —
        // otherwise the set leaks (it is Closeable and may become disk-backed).
        try {
            val snapshots = loadAllSnapshots(failClosed)
            val totalSnapshots = snapshots.size

            onProgress?.invoke(GCProgress("Walking snapshot trees", 0, totalSnapshots))

            var processedSnapshots = 0
            for (snapshot in snapshots) {
                collectInUseContent(snapshot, inUseSet, failClosed)
                processedSnapshots++
                onProgress?.invoke(
                    GCProgress(
                        "Walking snapshot trees",
                        processedSnapshots,
                        totalSnapshots,
                        inUseContents = inUseSet.size().toInt(),
                    ),
                )
            }

            return inUseSet
        } catch (t: Throwable) {
            inUseSet.close()
            throw t
        }
    }

    private suspend fun loadAllSnapshots(failClosed: Boolean): List<SnapshotManifest> {
        val manifests = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
        )

        return manifests.mapNotNull { metadata ->
            try {
                repository.getManifest(metadata.id, SnapshotManifest.serializer()).first
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                // Deletion must not proceed on a partially-loaded snapshot set (a snapshot we cannot read
                // may reference live content). A dry run skips the unreadable manifest.
                if (failClosed) throw e
                null
            }
        }
    }

    private suspend fun collectInUseContent(
        snapshot: SnapshotManifest,
        inUseSet: InUseContentSet,
        failClosed: Boolean,
    ) {
        val rootEntry = snapshot.rootEntry
        val rootObjectIdStr = rootEntry?.objectId
        if (rootEntry == null || rootObjectIdStr == null) {
            // No root reference => the snapshot references no tree. That is expected for an explicitly
            // INCOMPLETE snapshot (an interrupted backup). For a COMPLETE snapshot it is suspicious — a
            // dropped/renamed root field (schema drift) could hide a live tree — so a delete run aborts
            // rather than treat that tree as unreferenced. A dry run skips it (best-effort).
            if (failClosed && snapshot.incompleteReason == null) {
                throw IllegalStateException(
                    "snapshot ${snapshot.id} has no usable root entry; refusing delete-GC over a " +
                        "possibly-incomplete/drifted view",
                )
            }
            return
        }

        val rootObjectId = try {
            ObjectId.parse(rootObjectIdStr)
        } catch (e: Exception) {
            // An unparseable root reference means we cannot enumerate this snapshot's live tree.
            if (failClosed) throw e
            return
        }

        // Walk the snapshot tree and collect all content IDs. The snapshot root is a directory; trust
        // the manifest's declared entry type (with an object-id-prefix fallback) to decide recursion.
        walkObjectTree(rootObjectId, isDirectory(rootEntry, rootObjectId), inUseSet, failClosed)
    }

    private suspend fun walkObjectTree(
        objectId: ObjectId,
        isDirectory: Boolean,
        inUseSet: InUseContentSet,
        failClosed: Boolean,
    ) {
        // Get all content IDs backing this object
        val contentIds = try {
            repository.verifyObject(objectId)
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            // Object not found or corrupted. A delete run must abort (we cannot trust the in-use set);
            // a dry run fails open and skips it.
            if (failClosed) throw e
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
            readDirectoryManifest(objectId)
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            // Unreadable / oversized / non-directory JSON. Delete run aborts; dry run fails open.
            if (failClosed) throw e
            return
        }

        // We decided this object IS a directory; if the parsed stream type disagrees, we mis-identified
        // a non-directory (or read foreign JSON). A delete run must abort rather than under-collect that
        // object's (unknown) real children. (A genuine directory always carries the directory stream
        // type, so this never fires for real directories — no false aborts.)
        if (failClosed && !dirManifest.isValidDirectoryStream()) {
            throw IllegalStateException(
                "object $objectId was treated as a directory but its stream type is " +
                    "'${dirManifest.streamType}'; refusing delete-GC",
            )
        }

        // ponytail: no visited-set, so a subtree shared across N incremental snapshots is re-walked
        // N times (correctness is fine — set semantics, content is hash-addressed/acyclic — but it is
        // O(references) not O(unique objects)). Thread a HashSet<ObjectId> visited-set through the walk
        // if Phase-1 cost matters on-device.
        for (entry in dirManifest.entries) {
            val childOid = entry.objectId
            if (childOid == null) {
                // Our writer never emits a null object id (empty files use "" => ObjectId.Empty). In a
                // delete run a null child obj is schema drift/corruption that could hide a real child's
                // live subtree, so abort. A dry run skips it.
                if (failClosed) {
                    throw IllegalStateException(
                        "directory $objectId has child entry '${entry.name}' with no object id; " +
                            "refusing delete-GC",
                    )
                }
                continue
            }
            val childObjectId = try {
                ObjectId.parse(childOid)
            } catch (e: Exception) {
                if (failClosed) throw e
                continue
            }
            walkObjectTree(childObjectId, isDirectory(entry, childObjectId), inUseSet, failClosed)
        }
    }

    /**
     * Reads and parses [objectId] as a directory manifest, guarding against OOM.
     *
     * A corrupt or lying `DirEntry(type=DIRECTORY)` can point at a huge file object; loading it whole
     * into RAM to parse as JSON would OOM GC on-device. [org.kopiaKt.core.`object`.ObjectReader.length]
     * is cheap for large (INDIRECT) objects — it reads only the seek table — so we cap the object size
     * BEFORE reading the bytes. This fully covers the realistic hazard: a huge backed-up file is always
     * split into an indirect object, so its length is known without loading it. A real directory manifest
     * is far below [maxDirectoryManifestSize].
     *
     * ponytail: for a DIRECT object, length() must load the single content block to measure it — but a
     * direct object is bounded by the content-block max (a few MB), so it cannot OOM UNLESS it carries
     * object-level ('Z') compression that decompresses to a huge size (a crafted compression bomb). That
     * residual is a systemic decompression-limit concern shared with the restore path (which also
     * decompresses 'Z' objects), not specific to GC; a bounded-decompression primitive would close it
     * everywhere at once. Deferred.
     *
     * Throws if the object exceeds the cap or is not valid directory JSON; the caller's fail-closed /
     * fail-open policy decides whether that aborts the run or skips the object.
     */
    private suspend fun readDirectoryManifest(objectId: ObjectId): DirManifest {
        val reader = repository.openObject(objectId)
        try {
            val length = reader.length()
            require(length <= maxDirectoryManifestSize) {
                "object $objectId under a directory entry is $length bytes, exceeding the " +
                    "$maxDirectoryManifestSize-byte directory-manifest cap; refusing to load it"
            }
            return DirManifest.fromJson(reader.read().decodeToString())
        } finally {
            reader.close()
        }
    }

    /**
     * Whether [entry] refers to a directory object (i.e. whether GC must recurse into it).
     *
     * Recursion is intentionally over-inclusive — it NEVER under-collects the in-use set, which would be
     * a data-loss hazard once GC deletes. It recurses when ANY of the following holds:
     * - the declared [DirEntry.type] is DIRECTORY (the normal case);
     * - the declared type is UNKNOWN, i.e. AMBIGUOUS (a corrupt/older/unrecognized entry). Production
     *   directory objects are written WITHOUT the 'k' content prefix, so an UNKNOWN-typed entry over a
     *   prefix-less directory would otherwise be treated as a file and its whole subtree dropped. We
     *   attempt to parse it as a directory instead: if it IS one, its children are collected; if it is
     *   not, [readDirectoryManifest] fails and the caller aborts (delete run) or skips (dry run);
     * - [isDirectoryId] is true (a 'k'-prefixed / indirect directory object id), even if the type field
     *   somehow says otherwise.
     *
     * A false positive is harmless: parsing a non-directory as a manifest fails and stops (that object's
     * own content was already recorded). A false NEGATIVE would drop a live subtree — hence UNKNOWN is
     * treated as "recurse". See task-9.
     */
    private fun isDirectory(entry: DirEntry, objectId: ObjectId): Boolean = entry.type == EntryType.DIRECTORY ||
        entry.type == EntryType.UNKNOWN ||
        isDirectoryId(objectId)

    /**
     * Phase 2: iterate ALL content (including tombstones) and classify each item. Manifest ('m') content
     * is always kept; content referenced by a live snapshot tree is kept; unreferenced content old enough
     * to be safe is reclaimed (soft-deleted) when [writer] is non-null (a delete run).
     *
     * Mirrors Go's snapshotgc classification loop. Stats sizes use `packedLength` (as Go does). The
     * iteration is over ALL content ids, not a fixed prefix set — file data lives under the empty prefix,
     * so a by-prefix scan would miss it entirely (the previous stub's bug). See task-9.
     */
    private suspend fun findAndProcessUnreferencedContent(
        inUseSet: InUseContentSet,
        writer: DirectRepositoryWriter?,
        options: GCOptions,
        now: Instant,
    ): SnapshotGCStats {
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

        repository.iterateContentInfos(includeDeleted = true) { info ->
            processContent(
                info, inUseSet, writer, now, minAge,
                unreferencedCount, unreferencedSize,
                deletedCount, deletedSize,
                recentCount, recentSize,
                inUseCount, inUseSize,
                systemCount, systemSize,
            )
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
            // recoveredContent* stays 0 — see SnapshotGCStats.recoveredContentCount.
        )
    }

    @Suppress("LongParameterList")
    private suspend fun processContent(
        info: ContentInfo,
        inUseSet: InUseContentSet,
        writer: DirectRepositoryWriter?,
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
        systemCount: AtomicInteger,
        systemSize: AtomicLong,
    ) {
        val contentId = info.contentId
        val size = info.packedLength.toLong()

        // Branch 1: manifest/system content is always kept, never GC'd (Go keeps `manifest.ContentPrefix`).
        if (contentId.prefix == MANIFEST_CONTENT_PREFIX) {
            systemCount.incrementAndGet()
            systemSize.addAndGet(size)
            return
        }

        // Branch 2: content referenced by a live snapshot tree is kept.
        if (inUseSet.contains(contentId)) {
            inUseCount.incrementAndGet()
            inUseSize.addAndGet(size)
            return
        }

        // Not referenced. Content already reclaimed (a tombstone) has nothing left to do — skip it so it
        // is not re-counted as reclaimable on every run (we have no index compaction to drop tombstones).
        if (info.deleted) {
            return
        }

        // Branch 3: unreferenced but too recent to delete. Protects content a concurrent snapshot may
        // still be attaching (Go's MinContentAgeSubjectToGC grace).
        val age = Duration.between(Instant.ofEpochSecond(info.timestampSeconds), now)
        if (age < minAge) {
            recentCount.incrementAndGet()
            recentSize.addAndGet(size)
            return
        }

        // Branch 4: unreferenced and old enough — reclaim it. A dry run (writer == null) only counts.
        unreferencedCount.incrementAndGet()
        unreferencedSize.addAndGet(size)
        if (writer != null) {
            writer.deleteContent(contentId)
            deletedCount.incrementAndGet()
            deletedSize.addAndGet(size)
        }
    }

    companion object {
        /**
         * Content ID prefix for manifest content.
         */
        const val MANIFEST_CONTENT_PREFIX = 'm'

        /**
         * Default cap on the size of an object GC will load as a directory manifest (128 MiB).
         *
         * ponytail: a generous ceiling that separates any realistic on-device directory manifest from a
         * raw file mislabeled `type=DIRECTORY` (the OOM hazard). A genuinely enormous legit directory
         * (> cap) would make a delete run abort fail-closed rather than OOM; a streaming DirManifest
         * parser would remove the cap entirely.
         */
        const val MAX_DIRECTORY_MANIFEST_SIZE: Long = 128L * 1024 * 1024
    }
}

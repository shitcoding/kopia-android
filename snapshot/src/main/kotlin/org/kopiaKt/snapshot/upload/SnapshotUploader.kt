package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.CancellationException
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.IgnoreFS
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SnapshotStats
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Options for snapshot upload.
 */
data class UploadOptions(
    /**
     * Description for the snapshot.
     */
    val description: String = "",

    /**
     * Tags to attach to the snapshot manifest.
     */
    val tags: Map<String, String> = emptyMap(),

    /**
     * Number of parallel file uploads.
     */
    val parallelUploads: Int = Runtime.getRuntime().availableProcessors(),

    /**
     * Percentage of files to force re-hash even when metadata matches (0-100).
     * Used for validation and detecting bit-rot.
     */
    val forceHashPercentage: Int = 0,

    /**
     * If true, fail immediately on the first error.
     */
    val failFast: Boolean = false,
)

/**
 * Result of a snapshot upload operation.
 */
data class UploadResult(
    /**
     * The manifest ID of the created snapshot.
     */
    val manifestId: ManifestId,

    /**
     * The snapshot manifest.
     */
    val manifest: SnapshotManifest,

    /**
     * Statistics from the upload.
     */
    val stats: SnapshotStats,

    /**
     * Whether the snapshot is incomplete (due to errors or cancellation).
     */
    val incomplete: Boolean = false,

    /**
     * Reason for incomplete snapshot, if applicable.
     */
    val incompleteReason: String? = null,
)

/**
 * Creates backup snapshots by uploading directory trees to the repository.
 *
 * Handles the complete snapshot lifecycle:
 * 1. Apply ignore rules from policy
 * 2. Find previous snapshot for caching
 * 3. Walk directory tree and upload entries
 * 4. Create and save snapshot manifest
 *
 * Go type: snapshotfs.Uploader
 */
class SnapshotUploader(
    private val writer: RepositoryWriter,
    private val source: SourceInfo,
    private val policy: Policy = Policy(),
    private val progress: UploadProgress = NullUploadProgress(),
) {
    private val currentWalker = AtomicReference<TreeWalker?>(null)

    // Sticky so a cancel that arrives before the walker is installed (during findPreviousSnapshot / while
    // the caller is still opening the repo writer) is not lost: upload() re-checks this right after
    // creating the walker. Without it, cancel() only reaches the (possibly not-yet-created) walker and a
    // pre-walk cancel would let the entire tree walk run to completion.
    private val cancelled = AtomicBoolean(false)

    /**
     * Cancels the current upload operation. Cooperative: the walk stops at its next entry boundary.
     * Safe to call before [upload] starts -- the request is remembered and applied once the walker exists.
     */
    fun cancel() {
        cancelled.set(true)
        currentWalker.get()?.cancel()
    }

    /**
     * Uploads a directory tree and creates a snapshot.
     *
     * @param rootDir The root directory to backup
     * @param options Upload options
     * @return The result containing manifest ID and stats
     */
    suspend fun upload(
        rootDir: Directory,
        options: UploadOptions = UploadOptions(),
    ): UploadResult {
        val startTime = Instant.now()
        progress.uploadStarted()

        try {
            // Apply ignore rules from policy
            val filteredDir = applyIgnoreRules(rootDir)

            // Find previous snapshot for caching
            val previousSnapshot = findPreviousSnapshot()
            val previousRootManifest = previousSnapshot?.let { loadRootManifest(it) }

            // Create the file uploader
            val fileUploader = FileUploader(
                writer = writer,
                progress = progress,
                compressionPolicy = policy.compressionPolicy,
                splitterPolicy = policy.splitterPolicy,
                forceHashPercentage = options.forceHashPercentage,
            )

            // Create and register the tree walker
            val walker = TreeWalker(
                processor = fileUploader,
                progress = progress,
                errorPolicy = policy.errorHandlingPolicy,
                parallelism = options.parallelUploads,
                failFast = options.failFast,
            )
            currentWalker.set(walker)
            // Apply a cancel that landed before the walker existed (see [cancelled]).
            if (cancelled.get()) walker.cancel()

            // The walk no longer unwinds when it is cancelled or hits a failFast error: it drains,
            // writing each directory's partial manifest on the way out, and hands back a real root.
            //
            // What that buys TODAY is that the partial tree stays reachable: a cancelled snapshot
            // used to be saved with rootEntry = null, so everything it had uploaded was unreferenced
            // and GC-eligible, and the retry re-uploaded it. Now it is referenced, retention's
            // incomplete rules keep it, and the retry dedups against it. Reading that tree back as a
            // base -- resuming rather than restarting -- needs findPreviousSnapshot to stop skipping
            // incomplete manifests, which is phase 3.2's multi-manifest work, not this.
            val rootEntry: DirEntry? = walker.walk(filteredDir, previousRootManifest)
            val incompleteReason: String? = walker.incompleteReason()

            val endTime = Instant.now()

            // Build statistics from progress counters
            val counters = if (progress is CountingUploadProgress) {
                progress.snapshot()
            } else {
                UploadCounters()
            }

            val stats = SnapshotStats(
                totalFileSize = counters.totalHashedBytes + counters.totalCachedBytes,
                totalFileCount = counters.totalHashedFiles + counters.totalCachedFiles,
                cachedFiles = counters.totalCachedFiles,
                nonCachedFiles = counters.totalHashedFiles,
                excludedFileCount = counters.totalExcludedFiles,
                excludedDirCount = counters.totalExcludedDirs,
                // From the tree that was actually written, not from the progress reporter: a
                // caller using NullUploadProgress would otherwise save a snapshot whose directory
                // summaries record failures while its stats claim none.
                ignoredErrorCount = rootEntry?.dirSummary?.ignoredErrorCount ?: counters.ignoredErrorCount,
                errorCount = rootEntry?.dirSummary?.fatalErrorCount ?: counters.fatalErrorCount,
            )

            // Create the snapshot manifest
            val snapshotId = ManifestId.generate().value
            val manifest = SnapshotManifest(
                id = snapshotId,
                source = source,
                description = options.description,
                startTime = startTime,
                endTime = endTime,
                stats = stats,
                incompleteReason = incompleteReason,
                rootEntry = rootEntry,
                tags = options.tags,
            )

            // Save the manifest
            val labels = ManifestLabels.forSnapshot(source)
            val manifestId = writer.putManifest(labels, manifest, SnapshotManifest.serializer())

            // Flush to ensure everything is persisted
            writer.flush()

            progress.uploadFinished()

            return UploadResult(
                manifestId = manifestId,
                manifest = manifest,
                stats = stats,
                incomplete = incompleteReason != null,
                incompleteReason = incompleteReason,
            )
        } finally {
            currentWalker.set(null)
        }
    }

    /**
     * Applies ignore rules from the files policy to the directory.
     */
    private fun applyIgnoreRules(dir: Directory): Directory = IgnoreFS.wrap(dir, policy.filesPolicy)

    /**
     * Finds the most recent COMPLETE previous snapshot for the same source.
     *
     * Completeness is the whole point. A cancelled run saves an incomplete manifest whose
     * `rootEntry` is null; taking simply the newest manifest handed that one back, `loadRootManifest`
     * then returned null, and the next backup re-hashed the entire tree — so cancelling a backup
     * used to make the retry as expensive as the first run, exactly when the user least wants that.
     *
     * Go additionally returns the newer incomplete manifests and merges their entries per directory
     * (`snapshot/manager.go` FindPreviousManifests). That needs a multi-manifest walk API and lands
     * with the checkpoint work in phase 3; latest-complete is what makes today's behaviour correct.
     */
    private suspend fun findPreviousSnapshot(): SnapshotManifest? {
        val labels = ManifestLabels.forSnapshot(source)

        val candidates = writer.findManifests(labels).sortedByDescending { it.modTime }
        for (candidate in candidates) {
            val manifest = try {
                writer.getManifest(candidate.id, SnapshotManifest.serializer()).first
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                // Unreadable: skip it rather than give up on reuse entirely (Go does the same).
                java.util.logging.Logger.getLogger(SnapshotUploader::class.java.name)
                    .log(java.util.logging.Level.WARNING, "skipping unreadable snapshot manifest ${candidate.id}", e)
                continue
            }
            if (manifest.incompleteReason == null && manifest.rootEntry != null) {
                return manifest
            }
        }
        return null
    }

    /**
     * Loads the root directory manifest from a previous snapshot.
     */
    private suspend fun loadRootManifest(snapshot: SnapshotManifest): DirManifest? {
        val rootObjectId = snapshot.rootEntry?.objectId ?: return null

        return try {
            val data = writer.readObject(ObjectId.parse(rootObjectId))
            kotlinx.serialization.json.Json.decodeFromString(
                DirManifest.serializer(),
                data.toString(Charsets.UTF_8),
            )
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            // If we can't load it, proceed without manifest caching
            null
        }
    }
}

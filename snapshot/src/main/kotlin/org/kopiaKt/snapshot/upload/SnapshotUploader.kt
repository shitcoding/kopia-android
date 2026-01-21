package org.kopiaKt.snapshot.upload

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
import org.kopiaKt.snapshot.policy.CompressionPolicy
import org.kopiaKt.snapshot.policy.ErrorHandlingPolicy
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.SplitterPolicy
import java.time.Instant
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
    val failFast: Boolean = false
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
    val incompleteReason: String? = null
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
    private val progress: UploadProgress = NullUploadProgress()
) {
    private val currentWalker = AtomicReference<TreeWalker?>(null)

    /**
     * Cancels the current upload operation.
     */
    fun cancel() {
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
        options: UploadOptions = UploadOptions()
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
                forceHashPercentage = options.forceHashPercentage
            )

            // Create and register the tree walker
            val walker = TreeWalker(
                processor = fileUploader,
                progress = progress,
                errorPolicy = createErrorPolicy(options),
                parallelism = options.parallelUploads
            )
            currentWalker.set(walker)

            var rootEntry: DirEntry? = null
            var incompleteReason: String? = null

            try {
                // Walk the tree and upload
                rootEntry = walker.walk(filteredDir, previousRootManifest)
            } catch (e: TreeWalker.CancelledException) {
                incompleteReason = "canceled"
            } catch (e: TreeWalker.FatalErrorException) {
                incompleteReason = "error: ${e.message}"
            }

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
                ignoredErrorCount = counters.ignoredErrorCount,
                errorCount = counters.fatalErrorCount
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
                tags = options.tags
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
                incompleteReason = incompleteReason
            )

        } finally {
            currentWalker.set(null)
        }
    }

    /**
     * Applies ignore rules from the files policy to the directory.
     */
    private fun applyIgnoreRules(dir: Directory): Directory {
        return IgnoreFS.wrap(dir, policy.filesPolicy)
    }

    /**
     * Finds the most recent previous snapshot for the same source.
     */
    private suspend fun findPreviousSnapshot(): SnapshotManifest? {
        val labels = ManifestLabels.forSnapshot(source)

        val manifests = writer.findManifests(labels)
        if (manifests.isEmpty()) return null

        // Find the most recent complete snapshot
        val mostRecent = manifests
            .maxByOrNull { it.modTime }
            ?: return null

        return try {
            val (manifest, _) = writer.getManifest(mostRecent.id, SnapshotManifest.serializer())
            manifest
        } catch (e: Exception) {
            // If we can't load it, proceed without caching
            null
        }
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
                data.toString(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            // If we can't load it, proceed without manifest caching
            null
        }
    }

    /**
     * Creates error handling policy from upload options and policy.
     */
    private fun createErrorPolicy(options: UploadOptions): ErrorHandlingPolicy {
        return policy.errorHandlingPolicy.copy()
    }
}


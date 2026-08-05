package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

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

    /**
     * How often a partial tree is written into the repository so an interrupted run can resume.
     *
     * Go's `DefaultCheckpointInterval` (`upload.go`). Values below
     * [MIN_CHECKPOINT_INTERVAL] are raised to it: `delay()` returns immediately for a zero or
     * negative duration, which would spin a tight loop writing checkpoints as fast as the
     * repository accepts them.
     */
    val checkpointInterval: Duration = DEFAULT_CHECKPOINT_INTERVAL,
) {
    companion object {
        /** Go: `DefaultCheckpointInterval = 45 * time.Minute`. */
        val DEFAULT_CHECKPOINT_INTERVAL: Duration = Duration.ofMinutes(45)

        /** Floor for [checkpointInterval], so a misconfigured zero cannot busy-loop the checkpointer. */
        val MIN_CHECKPOINT_INTERVAL: Duration = Duration.ofMillis(50)
    }

    /** [checkpointInterval] clamped to [MIN_CHECKPOINT_INTERVAL]. */
    val effectiveCheckpointInterval: Duration
        get() = if (checkpointInterval < MIN_CHECKPOINT_INTERVAL) MIN_CHECKPOINT_INTERVAL else checkpointInterval
}

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

    /** Object id of the last checkpointed root, so an interval that uploaded nothing writes nothing. */
    private val lastCheckpointRoot = AtomicReference<String?>(null)

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
    ): UploadResult = coroutineScope {
        val startTime = Instant.now()
        progress.uploadStarted()

        val checkpointRegistry = CheckpointRegistry()
        var checkpointJob: Job? = null
        var estimateJob: Job? = null

        try {
            // Apply ignore rules from policy
            val filteredDir = applyIgnoreRules(rootDir)

            // Trees the walk may reuse: latest complete, then the checkpoints of any interrupted
            // run that followed it.
            val previousRootManifests = loadRootManifests(findPreviousManifests())

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

            // Started before the walk, stopped before the snapshot is saved (below).
            checkpointJob = launch {
                periodicallyCheckpoint(checkpointRegistry, options, startTime)
            }
            estimateJob = launchEstimate(filteredDir)

            // The walk does not unwind when it is cancelled or hits a failFast error: it drains,
            // writing each directory's partial manifest on the way out, and hands back a real root
            // (phase 3.1). Phase 3.2 is the other half — those partial trees, and the checkpoints
            // written while the run was alive, are now READ BACK by findPreviousManifests, so an
            // interrupted backup resumes instead of starting over.
            val rootEntry: DirEntry? = walker.walk(filteredDir, previousRootManifests, checkpointRegistry)
            val incompleteReason: String? = walker.incompleteReason()

            // Stop checkpointing before the snapshot is saved, as Go's `defer cancelCheckpointer()`
            // does. Not because a late checkpoint would be misread — its start time is pinned to
            // startTime-1ns, so it still sorts older than this run and findPreviousManifests still
            // excludes it — but because it would go on writing objects and flushing through a
            // `writer` the caller is about to close, racing the one write that must not fail.
            checkpointJob?.cancelAndJoin()
            checkpointJob = null

            // Go's `defer estimationCtl.Cancel(); estimationCtl.Wait()`. Cancel, not join: on a tree
            // large enough for the estimate to still be running, waiting for it would hold the
            // finished backup open for a second full walk that can no longer tell anyone anything.
            estimateJob?.cancel()
            estimateJob = null

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

            UploadResult(
                manifestId = manifestId,
                manifest = manifest,
                stats = stats,
                incomplete = incompleteReason != null,
                incompleteReason = incompleteReason,
            )
        } finally {
            checkpointJob?.cancel()
            estimateJob?.cancel()
            currentWalker.set(null)
        }
    }

    /**
     * Counts what this backup is about to do, alongside the backup doing it — or declines to.
     *
     * Until this existed, `estimatedDataSize` had no production caller at all: it was declared,
     * overridden and stored, and every progress bar that reads `estimatedBytes` — the notification,
     * the Tasks screen, the progress sheet — had no denominator and stayed indeterminate. A
     * multi-hour first backup showed a spinner for hours.
     *
     * Declined in two cases, both of which Go also checks:
     * - nobody is listening (`NullUploadProgress`), so the walk would cost a full extra enumeration
     *   and report to no one (`upload.go`: `!u.Progress.Enabled()`);
     * - the tree cannot be iterated twice (`estimate.go:126`). Nothing implements that today, but
     *   the guard is not cosmetic: a single-pass directory read by both the estimator and the walk
     *   at once would have entries consumed out from under the walk, which is data loss, not a bad
     *   progress bar.
     */
    private fun CoroutineScope.launchEstimate(filteredDir: Directory): Job? {
        if (!progress.enabled() || !filteredDir.supportsMultipleIterations()) return null
        return launch { estimateDataSize(filteredDir) }
    }

    /**
     * Reported ONCE, when the count is finished, exactly as Go's estimator calls back once
     * (`upload_estimator.go`). Feeding the running subtotal instead would make the denominator grow
     * under the numerator, and the bar would walk backwards while the backup made progress.
     *
     * The estimator reads metadata only — it never opens a file — so alongside a real upload it
     * costs one extra enumeration per directory and finishes long before the transfer does.
     *
     * `Throwable`, not `Exception`: this promises to cost the bar and never the backup, and it is a
     * child of `upload`'s scope, so anything escaping here would cancel the walk. The estimator
     * recurses directly rather than through dispatched children, so a pathologically deep tree can
     * reach it with a `StackOverflowError` — which is exactly the kind of thing that must not take
     * a running backup down with it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun estimateDataSize(filteredDir: Directory) {
        try {
            // The walk's OWN filtered root, and therefore its own ignore rules, its own loaded
            // .kopiaignore files, and no second interpretation of the policy: what is estimated
            // cannot drift from what is uploaded, because it is the same object.
            //
            // Passing the raw root and the policy instead — which is what Go does — would also turn
            // on the estimator's excluded-entry accounting, and that walks every excluded top-level
            // subtree in full. A user excludes a folder because it is huge; counting it anyway, on
            // every backup, over SAF, is the opposite of what they asked for.
            val estimate = SnapshotEstimator.estimate(filteredDir)
            progress.estimatedDataSize(estimate.totalFiles.toLong(), estimate.totalBytes)
        } catch (e: CancellationException) {
            throw e // the backup finished first; there is nothing left to estimate for
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "could not estimate the backup size; progress stays indeterminate", e)
        }
    }

    /**
     * Writes the half-built tree into the repository every [UploadOptions.checkpointInterval].
     *
     * This is what turns an interrupted backup from "restarts" into "resumes". Kotlin used to flush
     * exactly once, at the end of [upload]; on Android the abrupt stop — process death, swipe-away,
     * the 6h foreground-service cap — is the *common* case, and every one of them threw away the
     * whole run and left its pack blobs referenced by nothing.
     *
     * Go: `periodicallyCheckpoint` / `checkpointRoot` (`upload.go`).
     */
    private suspend fun periodicallyCheckpoint(
        registry: CheckpointRegistry,
        options: UploadOptions,
        startTime: Instant,
    ) {
        while (true) {
            delay(options.effectiveCheckpointInterval.toMillis())
            checkpointRoot(registry, startTime)
        }
    }

    /**
     * Saves one checkpoint: the current partial tree plus an incomplete snapshot manifest naming it.
     *
     * The manifest is dated one nanosecond BEFORE the run's own start time, exactly as Go does, so
     * it sorts older than the snapshot it belongs to and the retention pass that follows a finished
     * run reaps it. Without that a completed backup would leave its own checkpoints behind forever.
     *
     * A failure here is logged and swallowed: the upload is still making progress, the next
     * checkpoint will retry, and a lost checkpoint costs one interval of resumability. (Go instead
     * cancels the whole upload; on a handset that trades a recoverable stall for a lost backup.)
     */
    private suspend fun checkpointRoot(
        registry: CheckpointRegistry,
        startTime: Instant,
    ) {
        try {
            val builder = DirManifestBuilder()
            registry.runCheckpoints(builder).forEach {
                logger.log(Level.WARNING, "error while checkpointing", it)
            }

            // No entry means the walk has not produced anything worth referencing yet. More than one
            // is structurally impossible — only the root directory registers with this registry —
            // but Go treats it as an error rather than a checkpoint, and so does this.
            val entries = builder.build(startTime, incompleteReason = TreeWalker.CHECKPOINT_REASON).entries
            if (entries.size > 1) {
                logger.log(Level.WARNING, "checkpoint produced ${entries.size} roots; skipping it")
                return
            }
            val rootEntry = entries.singleOrNull() ?: return

            // Directory manifests are content-addressed, so an unchanged root means the walk has
            // uploaded nothing since the last checkpoint — for a mostly-cached backup that is most
            // of them. Saving another identical manifest and forcing another pack/index flush would
            // just fragment the repository the phone never maintains.
            if (rootEntry.objectId != null && rootEntry.objectId == lastCheckpointRoot.get()) return

            val manifest = SnapshotManifest(
                id = ManifestId.generate().value,
                source = source,
                // Neither the description nor the caller's tags: Go's checkpoint prototype carries
                // only the source and start time (plus its own CheckpointLabels, normally empty), and
                // a checkpoint that answered a tag query would surface a partial tree as if it were
                // one of the user's real snapshots.
                startTime = startTime.minusNanos(1),
                endTime = Instant.now(),
                incompleteReason = TreeWalker.CHECKPOINT_REASON,
                rootEntry = rootEntry,
            )
            writer.putManifest(ManifestLabels.forSnapshot(source), manifest, SnapshotManifest.serializer())

            // The flush is the point of the whole exercise: it commits the pending pack blob and its
            // index, so everything the tree above references survives the process dying.
            writer.flush()
            lastCheckpointRoot.set(rootEntry.objectId)
        } catch (e: CancellationException) {
            throw e // the checkpointer is being stopped; do not report that as a failure
        } catch (e: Exception) {
            logger.log(Level.WARNING, "checkpoint failed; the upload continues", e)
        }
    }

    /**
     * Applies ignore rules from the files policy to the directory.
     */
    private fun applyIgnoreRules(dir: Directory): Directory = IgnoreFS.wrap(dir, policy.filesPolicy)

    /**
     * The trees the next walk may reuse: the latest COMPLETE snapshot, then the incomplete ones
     * newer than it. Go's `snapshot.FindPreviousManifests` (`snapshot/manager.go`).
     *
     * Completeness is why the complete one leads. A cancelled run saves an incomplete manifest;
     * taking simply the newest manifest handed that one back and the next backup re-hashed
     * everything the older complete snapshot already had.
     *
     * The newer incompletes are what make a resume a resume. They are the checkpoints of a run that
     * was interrupted: they hold entries for work the interrupted run had already uploaded, which
     * the last complete snapshot cannot possibly have — the file may not have existed then. Order
     * matters and is Go's: a directory's entries are searched complete-first, so an unchanged file
     * resolves against the settled snapshot and only a *changed* one falls through to the
     * checkpoint that uploaded it. (Go leaves the incompletes in listing order and never sorts
     * them; newest-first here is only for determinism, and cannot change which entry is reused —
     * candidates are gated on metadata, and equal metadata means equal content.)
     *
     * Only the newest [MAX_PREVIOUS_INCOMPLETE] incompletes are considered, which Go does not do.
     * Retention keeps every incomplete under four hours old, and one interrupted run leaves one per
     * checkpoint interval, so a phone that keeps losing its backup to doze can easily present a few
     * dozen — and each one costs an extra directory-manifest read per directory, over SAF or the
     * network. The newest carry the most content, so the tail is where the least is lost.
     */
    private suspend fun findPreviousManifests(): List<SnapshotManifest> {
        val labels = ManifestLabels.forSnapshot(source)

        val manifests = writer.findManifests(labels).mapNotNull { candidate ->
            try {
                writer.getManifest(candidate.id, SnapshotManifest.serializer()).first
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                // Unreadable: skip it rather than give up on reuse entirely (Go does the same).
                logger.log(Level.WARNING, "skipping unreadable snapshot manifest ${candidate.id}", e)
                null
            }
        }.filter { it.rootEntry != null }

        val latestComplete = manifests
            .filter { it.incompleteReason == null }
            .maxByOrNull { it.startTime }

        val newerIncompletes = manifests
            .filter { it.incompleteReason != null }
            .filter { latestComplete == null || it.startTime.isAfter(latestComplete.startTime) }
            .sortedByDescending { it.startTime }
            .take(MAX_PREVIOUS_INCOMPLETE)

        return listOfNotNull(latestComplete) + newerIncompletes
    }

    /**
     * Loads the root directory manifest of each previous snapshot, dropping the ones that cannot be
     * read — a previous tree that will not load only costs a re-hash, never correctness.
     *
     * Duplicates are removed by object id: an interrupted run's checkpoint often references exactly
     * the root the previous complete snapshot did (nothing had changed yet), and walking the same
     * tree twice per directory would double every cache lookup for no gain.
     */
    private suspend fun loadRootManifests(snapshots: List<SnapshotManifest>): List<DirManifest> = snapshots
        .mapNotNull { it.rootEntry?.objectId }
        .distinct()
        .mapNotNull { loadRootManifest(it) }

    private suspend fun loadRootManifest(rootObjectId: String): DirManifest? = try {
        val data = writer.readObject(ObjectId.parse(rootObjectId))
        // DirManifest.fromJson, not a strict decode: a Go-written root carries fields this model does
        // not name and writes `"entries": null` for an empty directory, and refusing either would
        // silently disable reuse against every snapshot desktop Kopia made.
        DirManifest.fromJson(data.toString(Charsets.UTF_8))
    } catch (e: CancellationException) {
        throw e // never swallow coroutine cancellation
    } catch (e: Exception) {
        // If we can't load it, proceed without manifest caching
        null
    }

    private companion object {
        private val logger: Logger = Logger.getLogger(SnapshotUploader::class.java.name)

        /** See [findPreviousManifests]: a cap on read amplification, not on correctness. */
        private const val MAX_PREVIOUS_INCOMPLETE = 8
    }
}

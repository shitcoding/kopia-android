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
import org.kopiaKt.snapshot.fs.EntryType
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
import java.util.concurrent.atomic.AtomicLong
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

    /**
     * The interval to use *right now*, consulted before every checkpoint rather than captured once.
     *
     * Exists because a run's circumstances can change while it runs: on Android a backup that loses
     * its foreground service becomes killable within minutes instead of hours, and everything since
     * the last checkpoint is what a kill throws away (task-60). Defaults to the fixed
     * [checkpointInterval], so a caller that has nothing to vary passes nothing.
     */
    val checkpointIntervalNow: () -> Duration = { checkpointInterval },

    /**
     * Bytes hashed since the last checkpoint that also force one, whatever the timer says. Zero or
     * negative turns the byte trigger off and leaves the timer alone.
     *
     * A deliberate divergence from Go, which checkpoints on elapsed time only. Go's default is
     * 45 minutes and Android's is 5, but a Nothing Phone (2) hashes 1.44 GiB in about 50 seconds --
     * measured -- so *no* phone-sized backup ever reached its first checkpoint and the resume
     * machinery task-30.16 built served almost none of the runs it exists for. What a kill throws
     * away is bytes, not minutes, and only a byte trigger engages on exactly the runs worth
     * protecting: a source too small to reach the threshold re-runs in less time than the checkpoint
     * would have saved. See task-64.
     *
     * **Granularity: whole files.** Bytes arrive from `FileUploader`'s single
     * `progress.hashedBytes(file.size)` call, made once per file after that file is uploaded, so the
     * count does not advance *within* a file. A source of many files -- the phone case this was
     * filed for -- is covered; a source that is one very large file is not, and there the timer
     * remains the only trigger for the mid-file checkpoints task-30.16 built. Moving the report into
     * the streaming loop would change what a *failed* file contributes to
     * `SnapshotStats.totalFileSize`, which is a Go-parity question (task-62) and deliberately not
     * bundled in here.
     */
    val checkpointAfterBytes: Long = DEFAULT_CHECKPOINT_AFTER_BYTES,
) {
    companion object {
        /** Go: `DefaultCheckpointInterval = 45 * time.Minute`. */
        val DEFAULT_CHECKPOINT_INTERVAL: Duration = Duration.ofMinutes(45)

        /** Floor for [checkpointInterval], so a misconfigured zero cannot busy-loop the checkpointer. */
        val MIN_CHECKPOINT_INTERVAL: Duration = Duration.ofMillis(50)

        /**
         * How often the checkpoint loop wakes to look at the byte counter.
         *
         * The loop cannot sleep the whole interval or the byte trigger would never be observed. A
         * quarter second is short enough that a 512 MiB threshold is hit within a fraction of the
         * ~18 seconds it takes to reach, and one atomic read four times a second is nothing beside
         * the upload it watches. A [checkpointInterval] shorter than this is used unchanged.
         */
        val CHECKPOINT_POLL_INTERVAL: Duration = Duration.ofMillis(250)

        /**
         * Default for [checkpointAfterBytes], chosen against the measurement rather than picked.
         *
         * At the ~28 MB/s this app achieved on a Nothing Phone (2) when the number was chosen,
         * 512 MiB is about 18 seconds of re-hashing, while a 1.4 GB backup that used to checkpoint
         * zero times now checkpoints twice. Anything a run below the threshold would have saved is
         * smaller than the re-run it replaces.
         *
         * Read 18 seconds as a typical cost, not a bound: because [checkpointAfterBytes] counts
         * whole files, a source containing one file larger than this passes the threshold without
         * the trigger noticing.
         */
        const val DEFAULT_CHECKPOINT_AFTER_BYTES: Long = 512L * 1024 * 1024
    }

    /** [checkpointIntervalNow] clamped to [MIN_CHECKPOINT_INTERVAL]. */
    fun effectiveCheckpointInterval(): Duration = checkpointIntervalNow()
        .let { if (it < MIN_CHECKPOINT_INTERVAL) MIN_CHECKPOINT_INTERVAL else it }
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

    /**
     * True when nothing had changed and the source's policy asked for no snapshot to be written, so
     * [manifestId] and [manifest] are the PREVIOUS snapshot — the one that still describes the
     * source — rather than one this run created.
     */
    val identicalToPrevious: Boolean = false,
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
            val filteredDir = applyIgnoreRules(rootDir, reportExclusions = true)

            // Trees the walk may reuse: latest complete, then the checkpoints of any interrupted
            // run that followed it.
            val previousManifests = findPreviousManifests()
            val previousRootManifests = loadRootManifests(previousManifests)

            // Everything that reports bytes goes through the tally, so the byte-triggered
            // checkpoint below works whatever reporter the caller supplied.
            val hashed = HashedByteTally(progress)

            // Create the file uploader
            val fileUploader = FileUploader(
                writer = writer,
                progress = hashed,
                compressionPolicy = policy.compressionPolicy,
                splitterPolicy = policy.splitterPolicy,
                forceHashPercentage = options.forceHashPercentage,
            )

            // Create and register the tree walker
            val walker = TreeWalker(
                processor = fileUploader,
                progress = hashed,
                errorPolicy = policy.errorHandlingPolicy,
                parallelism = options.parallelUploads,
                failFast = options.failFast,
            )
            currentWalker.set(walker)
            // Apply a cancel that landed before the walker existed (see [cancelled]).
            if (cancelled.get()) walker.cancel()

            // Started before the walk, stopped before the snapshot is saved (below).
            checkpointJob = launch {
                periodicallyCheckpoint(checkpointRegistry, options, startTime, hashed)
            }
            // Its OWN wrapper, silent: same rules, same result, no double-counted exclusions.
            estimateJob = launchEstimate(applyIgnoreRules(rootDir))

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

            // Nothing changed, and the source's policy says not to record that as a snapshot.
            val unchanged = identicalPrevious(previousManifests, rootEntry, incompleteReason)
            if (unchanged != null) {
                // Go logs this too. "Why is there no snapshot from today" has to be answerable, and
                // a silent skip is indistinguishable from a backup that never ran.
                logger.log(Level.INFO, "not saving a snapshot: nothing has changed since ${unchanged.id}")
                writer.flush()
                progress.uploadFinished()
                return@coroutineScope UploadResult(
                    manifestId = ManifestId(unchanged.id),
                    manifest = unchanged,
                    stats = stats,
                    identicalToPrevious = true,
                )
            }

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
            // A wrapper built from the SAME rules as the walk's, but its own instance and silent
            // (see applyIgnoreRules): sharing the walk's would double-count every exclusion, since
            // both walk the tree at once.
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
        hashed: HashedByteTally,
    ) {
        var waited: Duration = Duration.ZERO
        var bytesAtLastCheckpoint = hashed.total.get()

        while (true) {
            val interval = options.effectiveCheckpointInterval()
            // Sleeping the whole interval would make the byte trigger unobservable, so the loop wakes
            // often enough to notice it. A quarter-second poll that reads one atomic costs nothing
            // next to the upload it is watching, and `minOf` leaves a shorter interval untouched.
            val step = minOf(interval, UploadOptions.CHECKPOINT_POLL_INTERVAL)
            delay(step.toMillis())
            waited = waited.plus(step)

            val bytes = hashed.total.get()
            // The zero guard is load-bearing: without it `bytes - bytesAtLastCheckpoint >= 0` is true
            // on the very first poll and a disabled trigger would checkpoint continuously.
            val dueByBytes = options.checkpointAfterBytes > 0 &&
                bytes - bytesAtLastCheckpoint >= options.checkpointAfterBytes
            if (waited >= interval || dueByBytes) {
                // INFO, and deliberately at the TRIGGER rather than only on a written checkpoint:
                // this is the line that distinguishes "no checkpoint fired" from "one fired and had
                // nothing new to write", which produced identical evidence for three sessions of
                // task-76. FINE would be invisible -- Android's default configuration reports
                // isLoggable(FINE) == false, on exactly the device where this is needed.
                val trigger = if (dueByBytes) "$bytes bytes hashed" else "$waited elapsed"
                logger.log(Level.INFO, "checkpointing after $trigger")
                checkpointRoot(registry, startTime)
                waited = Duration.ZERO
                bytesAtLastCheckpoint = bytes
            }
        }
    }

    /**
     * Counts bytes hashed, so the checkpointer has a progress signal it owns.
     *
     * It cannot read [progress] instead: a caller may pass any [UploadProgress] and the default is
     * [NullUploadProgress], which counts nothing -- so keying the byte trigger on
     * `progress is CountingUploadProgress` would make a checkpoint the user asked for depend on an
     * unrelated constructor argument, and fail silently for every caller that passes none.
     */
    private class HashedByteTally(private val delegate: UploadProgress) : UploadProgress by delegate {
        val total = AtomicLong(0)

        override fun hashedBytes(numBytes: Long) {
            total.addAndGet(numBytes)
            delegate.hashedBytes(numBytes)
        }
    }

    /**
     * Saves one checkpoint: the current partial tree plus an incomplete snapshot manifest naming it.
     *
     * The manifest is dated one nanosecond BEFORE the run's own start time -- which is NOT what Go
     * does, despite what this comment used to claim: Go's `checkpointRoot` copies the prototype
     * manifest and sets only `RootEntry`, `EndTime`, `IncompleteReason` and `Tags`
     * (`snapshot/upload/upload.go:513-545`), leaving `StartTime` exactly equal to the run's. The
     * offset here is a deliberate local choice and a safe one -- strictly older sorts before the
     * snapshot it belongs to rather than tying with it -- so
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
            logger.log(Level.INFO, "checkpoint written, root ${rootEntry.objectId}")
        } catch (e: CancellationException) {
            throw e // the checkpointer is being stopped; do not report that as a failure
        } catch (e: Exception) {
            logger.log(Level.WARNING, "checkpoint failed; the upload continues", e)
        }
    }

    /**
     * The previous snapshot this run turned out to be an exact copy of, or null to save normally.
     *
     * Go's `command_snapshot_create.go`: when `RetentionPolicy.IgnoreIdenticalSnapshots` is on and
     * the new root object id matches the previous snapshot's, the manifest is simply not written.
     * Kotlin modelled the policy field and then always wrote the manifest anyway, so turning it on
     * did nothing — and on a phone backing up a photo folder that changes once a week, that is six
     * snapshots of nothing per week, each of which retention then has to reason about.
     *
     * Two tightenings on Go, both in the direction of never losing a record:
     * - only a COMPLETE run may be skipped. Go tests the root id whatever the run's state; an
     *   interrupted run's tree differs anyway (every directory it wrote is stamped incomplete), but
     *   "we decided not to record that your backup was interrupted" is not a sentence this should
     *   ever be able to produce.
     * - compared against the latest COMPLETE snapshot only. Go compares against `previous[0]`, which
     *   is the newest incomplete one when no complete snapshot exists. That is **reachable**, not
     *   theoretical: `TreeWalker` reads `incompleteReason()` once when it builds the root manifest
     *   and `upload` reads it again after the walk returns, and `cancel()` arrives from another
     *   thread — so a cancel landing between those two reads (a window that spans the root
     *   manifest's upload, a network round trip) saves a manifest marked "canceled" whose root is
     *   byte-identical to a complete run's. Go has the same two-read shape, so such manifests can
     *   also arrive from a desktop sharing this repository. Without this guard, a source whose first
     *   backup was cancelled in that window would match forever and never get a complete snapshot,
     *   while every run reported success.
     *
     * Retention is a third, smaller divergence: Go returns before `ApplyRetentionPolicy` on this
     * path, while `BackupSession` runs retention on every path out of a backup (task-30.17). Harmless
     * — the manifest set is unchanged, so retention recomputes the same answer — and it still reaps
     * stale incompletes from earlier runs.
     */
    private fun identicalPrevious(
        previousManifests: List<SnapshotManifest>,
        rootEntry: DirEntry?,
        incompleteReason: String?,
    ): SnapshotManifest? {
        if (policy.retentionPolicy.ignoreIdenticalSnapshots != true) return null
        if (incompleteReason != null) return null
        val rootObjectId = rootEntry?.objectId ?: return null

        return previousManifests
            .firstOrNull { it.incompleteReason == null }
            ?.takeIf { it.rootEntry?.objectId == rootObjectId }
    }

    /**
     * Applies ignore rules from the files policy to the directory.
     *
     * [reportExclusions] is off for the estimator's wrapper. The estimator walks the same tree at
     * the same time (task-30.20), so one shared reporting wrapper would count every excluded entry
     * twice and the Tasks screen would say the backup skipped twice what it did. Go draws the same
     * line with its `reportIgnoreStats` argument.
     */
    private fun applyIgnoreRules(dir: Directory, reportExclusions: Boolean = false): Directory = IgnoreFS.wrap(
        dir,
        policy.filesPolicy,
        onIgnored = if (!reportExclusions) {
            null
        } else {
            { entry, path ->
                // Until this existed, IgnoreFS dropped entries silently and "Excluded Files" /
                // "Excluded Directories" read 0 on every backup that ever ran -- including the ones
                // whose whole point was that they excluded something.
                if (entry.type == EntryType.DIRECTORY) {
                    progress.excludedDir(path)
                } else {
                    progress.excludedFile(path, entry.size)
                }
            }
        },
    )

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
     *
     * **`endTime` breaks the ties, and has to.** Every checkpoint of one run is dated
     * `startTime − 1ns` — the same instant, deliberately — so ordering by `startTime` alone leaves
     * them all equal and `take` keeps eight of them in whatever order the manifests happened to be
     * listed in. That was harmless while the timer was the only trigger and a phone-sized run
     * produced none; task-64's byte trigger makes more than eight per run ordinary for a large
     * source, and picking arbitrarily could then discard the very newest — the one carrying the most
     * reusable content, which is exactly what the sentence above promises not to lose. `endTime` is
     * `Instant.now()` at each checkpoint, so it distinguishes them. Costs nothing but re-hashing
     * when it goes wrong; found in review of task-64.
     */
    private suspend fun findPreviousManifests(): List<SnapshotManifest> {
        val labels = ManifestLabels.forSnapshot(source)

        val manifests = writer.findManifests(labels).mapNotNull { candidate ->
            try {
                // Patch in the REAL manifest id, as SnapshotManager.listSnapshots does. The body's
                // own `id` is a separate value the uploader generated before the manifest was ever
                // stored, so it never matches the one putManifest assigned -- and anything that
                // hands this manifest back to a caller as "the snapshot that describes the source"
                // would be naming a manifest that does not exist.
                writer.getManifest(candidate.id, SnapshotManifest.serializer()).first
                    .copy(id = candidate.id.value)
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
            .sortedWith(compareByDescending<SnapshotManifest> { it.startTime }.thenByDescending { it.endTime })
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

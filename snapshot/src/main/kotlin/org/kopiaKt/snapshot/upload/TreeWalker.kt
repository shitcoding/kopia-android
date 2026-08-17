package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.ErrorEntry
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.Symlink
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.policy.ErrorHandlingPolicy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import org.kopiaKt.snapshot.model.EntryType as ModelEntryType

/**
 * Callback interface for processing filesystem entries during tree walking.
 *
 * The implementation handles the actual upload/hashing of entries and returns
 * the resulting DirEntry with objectId populated.
 */
interface EntryProcessor {
    /**
     * Processes a file entry: hashes, compresses, encrypts, and uploads.
     *
     * @param file The file to process
     * @param relativePath The relative path from the snapshot root
     * @param previousEntries Candidate entries from previous snapshots, most authoritative first;
     *   the first one whose metadata still matches the file on disk may be reused
     * @param checkpointRegistry Registry to publish the partially-written object to, so a
     *   checkpoint taken mid-file keeps the bytes uploaded so far referenced by the tree
     * @return DirEntry with objectId populated
     */
    suspend fun processFile(
        file: File,
        relativePath: String,
        previousEntries: List<DirEntry>,
        checkpointRegistry: CheckpointRegistry = CheckpointRegistry(),
    ): DirEntry

    /**
     * Processes a symlink entry: reads target and stores.
     *
     * @param symlink The symlink to process
     * @param relativePath The relative path from the snapshot root
     * @param previousEntries Candidate entries from previous snapshots, most authoritative first
     * @return DirEntry with objectId populated
     */
    suspend fun processSymlink(
        symlink: Symlink,
        relativePath: String,
        previousEntries: List<DirEntry>,
    ): DirEntry

    /**
     * Uploads a directory manifest and returns its objectId.
     *
     * @param manifest The directory manifest to upload
     * @return The objectId of the uploaded manifest
     */
    suspend fun uploadDirectoryManifest(manifest: DirManifest): String

    /**
     * Loads a previously-written directory manifest so its entries can be reused.
     *
     * Returning null simply disables reuse for that subtree, so a failure here costs time, never
     * correctness.
     *
     * @param objectId The objectId of a directory manifest from the previous snapshot
     * @return The manifest, or null if it cannot be read
     */
    suspend fun loadDirManifest(objectId: String): DirManifest?
}

/**
 * Walks a directory tree and processes entries in parallel.
 *
 * Implements the core tree-walking algorithm from Go Kopia, handling:
 * - Parallel file processing with configurable concurrency
 * - Directory recursion with manifest building
 * - Error handling based on policy
 * - Cancellation support
 *
 * Go type: snapshotfs.Uploader (tree walking portion)
 */
class TreeWalker(
    private val processor: EntryProcessor,
    private val progress: UploadProgress,
    private val errorPolicy: ErrorHandlingPolicy = ErrorHandlingPolicy(),
    private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    /**
     * Stop at the first non-ignored error instead of recording it and carrying on.
     *
     * Off by default, matching Go: one unreadable entry out of ten thousand should cost that entry,
     * not the whole backup. The error is still recorded against the snapshot either way, so the run
     * can be reported as "completed with N errors" rather than quietly succeeding.
     */
    private val failFast: Boolean = false,
) {
    private val semaphore = Semaphore(parallelism)
    private val cancelled = AtomicBoolean(false)

    /** Set when failFast stops the walk, so the reason names the entry rather than saying "canceled". */
    @Volatile
    private var failFastReason: String? = null

    /**
     * A directory whose contents could not be listed. Carried up one level so the parent records it
     * against itself, which is where Go puts it; at the root there is no parent and it aborts.
     */
    class DirectoryReadException(
        val path: String,
        cause: Throwable,
    ) : Exception("Unable to read directory $path", cause)

    /**
     * Cancels the current walk operation.
     */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Why the tree is incomplete, or null while it is still whole.
     *
     * Go's `Uploader.incompleteReason()`: this is stamped onto every directory manifest written
     * after the walk stops, and the caller puts it on the snapshot.
     *
     * A user cancel is "canceled", the string Go writes. failFast names the entry that stopped the
     * run instead, which is a **deliberate divergence** — Go's `reportErrorAndMaybeCancel` just
     * calls `Cancel()`, so Go says "canceled" there too. Nothing keys on the exact text: every
     * consumer, in Kotlin and in desktop Go alike, only asks whether a reason is present. Naming the
     * entry costs nothing and is the difference between "something went wrong" and a place to look.
     * Concurrent failFast errors can therefore stamp different reasons within one run.
     */
    fun incompleteReason(): String? = if (cancelled.get()) failFastReason ?: "canceled" else null

    /**
     * Checks if the walk has been cancelled.
     */
    fun isCancelled(): Boolean = cancelled.get()

    /**
     * Walks a directory tree and returns the root DirEntry with objectId.
     *
     * @param rootDir The root directory to walk
     * @param previousManifests Previous snapshot trees to reuse entries from, most authoritative
     *   first (Go: the latest complete snapshot, then the checkpoints of any interrupted run)
     * @param checkpointRegistry Registry the root directory registers itself with, so a periodic
     *   checkpointer can write the half-built tree into the repository while the walk is running.
     *   The default is a private registry nobody polls, i.e. no checkpointing.
     * @return The root DirEntry with objectId pointing to the directory manifest
     */
    suspend fun walk(
        rootDir: Directory,
        previousManifests: List<DirManifest> = emptyList(),
        checkpointRegistry: CheckpointRegistry = CheckpointRegistry(),
    ): DirEntry = walkDirectory(rootDir, "", previousManifests, checkpointRegistry)

    /**
     * Recursively walks a directory.
     *
     * @param dir The directory to walk
     * @param relativePath The relative path from root (empty for root)
     * @param previousManifests Previous manifests for caching comparison, most authoritative first
     * @param parentRegistry The registry this directory registers its own checkpoint callback with
     * @return DirEntry for the directory with objectId
     */
    private suspend fun walkDirectory(
        dir: Directory,
        relativePath: String,
        previousManifests: List<DirManifest>,
        parentRegistry: CheckpointRegistry,
    ): DirEntry {
        // Deliberately no cancellation check here. Go does not unwind on cancel: every directory
        // level swallows it and still builds and writes what it has (upload.go:1183), so an
        // already-cancelled level writes an empty-but-real manifest rather than vanishing. Throwing
        // would take the DirManifestBuilder with it, which is the state a resume reads.
        val dirPath = if (relativePath.isEmpty()) dir.name else relativePath
        progress.startedDirectory(dirPath)

        val builder = DirManifestBuilder()

        // Candidates per name, in manifest order — Go's findCachedEntry walks prevDirs in order and
        // takes the first whose metadata matches the entry on disk. The union matters: a file the
        // interrupted run never reached is absent from its checkpoint but still present in the last
        // complete snapshot, and must stay reusable.
        val previousEntries: Map<String, List<DirEntry>> = previousManifests
            .flatMap { it.entries }
            .groupBy { it.name }

        // ONLY the listing is a directory-read failure. Go draws exactly this line: processSingle
        // maps `dirReadError` to record-and-continue and lets everything else return "unable to
        // process directory", which aborts the whole upload (upload.go:896-899, 1301). Wrapping
        // whatever escaped the walk below would relabel a REPOSITORY-side failure -- a subdirectory
        // whose manifest could not be written -- as "this folder could not be read", and the parent
        // would record it, carry on, and save a snapshot marked COMPLETE that is silently missing
        // that whole subtree. Both reviewers found that; it predates the drain conversion.
        val entries = mutableListOf<Entry>()
        try {
            val iterator = dir.iterate()
            try {
                while (true) {
                    val entry = iterator.next() ?: break
                    entries.add(entry)
                }
            } finally {
                iterator.close()
            }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            // Go: "always fail if the top level directory can't be read, otherwise a meaningless,
            // empty snapshot is created that can't be restored" (upload.go). A child's read failure
            // is recorded by the PARENT (see processEntry) and contributes no entry at all, so this
            // rethrows either way rather than uploading an empty manifest for the directory.
            throw DirectoryReadException(dirPath, e)
        }

        // Children register their in-flight state here; this directory registers with its parent.
        // Together the two make one registry per tree level, so a checkpoint that starts at the root
        // recurses down exactly the branch the walk is currently in and writes a real partial tree.
        val childRegistry = CheckpointRegistry()
        parentRegistry.addCheckpointCallback(dir.name) { checkpointDirectory(dir, builder, childRegistry) }

        // Deregistered only once this directory's OWN manifest has been written — Go's
        // `defer removeCheckpointCallback` fires at function exit, after WriteDirManifest
        // (upload.go:1162,1181). Dropping it any earlier opens a window in which the directory is
        // in neither place: its parent has not recorded it yet and its callback is gone, so a
        // checkpoint taken then omits the whole finished subtree while `flush()` commits its
        // content — orphaning everything it uploaded until some later checkpoint picks it up.
        // Writing the manifest is a network round trip, so that window was the wide one.
        try {
            // Anything escaping here propagates unwrapped, all the way out of the upload.
            coroutineScope {
                val jobs = entries.map { entry ->
                    async {
                        processEntry(
                            entry = entry,
                            parentPath = relativePath,
                            previousEntries = previousEntries[entry.name].orEmpty(),
                            builder = builder,
                            checkpointRegistry = childRegistry,
                        )
                    }
                }
                jobs.awaitAll()
            }

            progress.finishedDirectory(dirPath)

            // Every directory written after a cancel says so, exactly as Go stamps u.incompleteReason()
            // onto each one. A partial directory that claimed to be complete would let the next run
            // reuse it wholesale and silently lose whatever had not been walked yet.
            val manifest = builder.build(dir.modTime, incompleteReason = incompleteReason())
            val objectId = processor.uploadDirectoryManifest(manifest)

            return DirEntry(
                name = dir.name,
                type = ModelEntryType.DIRECTORY,
                permissions = dir.mode,
                modTime = dir.modTime,
                userId = dir.owner.userId,
                groupId = dir.owner.groupId,
                objectId = objectId,
                dirSummary = manifest.summary,
            )
        } finally {
            parentRegistry.removeCheckpointCallback(dir.name)
        }
    }

    /**
     * Writes the directory as it stands right now and returns an entry pointing at it.
     *
     * Called from the periodic checkpointer while the walk is still running, so it clones the
     * builder rather than sharing it: the walk keeps adding entries, and a manifest built from a
     * builder mutating underneath it would be internally inconsistent. Children in flight publish
     * themselves into the clone through their own registry, so one call at the root writes the
     * whole branch the walk is currently inside.
     */
    private suspend fun checkpointDirectory(
        dir: Directory,
        builder: DirManifestBuilder,
        childRegistry: CheckpointRegistry,
    ): DirEntry {
        val checkpointBuilder = builder.clone()
        // Logged, not propagated: one child that could not be checkpointed costs that subtree a
        // interval of resumability, and failing the whole checkpoint would cost every other child
        // the same. Silence would hide a subtree that has quietly stopped being resumable at all.
        childRegistry.runCheckpoints(checkpointBuilder).forEach {
            logger.log(Level.WARNING, "error checkpointing a child of ${dir.name}", it)
        }
        val manifest = checkpointBuilder.build(dir.modTime, incompleteReason = CHECKPOINT_REASON)
        return DirEntry(
            name = dir.name,
            type = ModelEntryType.DIRECTORY,
            permissions = dir.mode,
            modTime = dir.modTime,
            userId = dir.owner.userId,
            groupId = dir.owner.groupId,
            objectId = processor.uploadDirectoryManifest(manifest),
            dirSummary = manifest.summary,
        )
    }

    /**
     * Hashes and uploads a single file, reporting progress around it.
     */
    private suspend fun processFileEntry(
        entry: File,
        entryPath: String,
        previousEntries: List<DirEntry>,
        checkpointRegistry: CheckpointRegistry,
    ): DirEntry? = semaphore.withPermit {
        progress.hashingFile(entryPath)
        try {
            val result = processor.processFile(entry, entryPath, previousEntries, checkpointRegistry)
            // No finishedHashingFile here: the walker does not know whether the file was hashed or
            // reused, and reporting it for EVERY file made a cache hit increment both counters --
            // so a second backup of an unchanged source claimed twice the files it had (task-62).
            // The uploader reports it, on the path that actually hashes, which is where Go reports it.
            progress.finishedFile(entryPath, null)
            result
        } catch (e: CancellationException) {
            throw e // a cancelled file is not a failed file
        } catch (e: Exception) {
            progress.finishedFile(entryPath, e)
            throw e
        }
    }

    /**
     * Processes a single entry (file, directory, symlink, or error).
     */
    private suspend fun processEntry(
        entry: Entry,
        parentPath: String,
        previousEntries: List<DirEntry>,
        builder: DirManifestBuilder,
        checkpointRegistry: CheckpointRegistry,
    ) {
        // Once cancelled, every remaining entry is simply skipped and the level builds what it has.
        // This is Go's processDirectoryEntries returning errCanceled for the caller to swallow.
        if (cancelled.get()) {
            return
        }

        val entryPath = joinPath(parentPath, entry.name)

        try {
            val dirEntry = when (entry) {
                is ErrorEntry -> {
                    handleError(entryPath, entry.error, builder, isDirectory = false)
                    null
                }

                is Directory -> {
                    // Recurse carrying EVERY previous version of this subdirectory, not just one.
                    // Without this, only root-level files ever had a previous entry and everything
                    // deeper was re-read and re-hashed on every backup — over SAF that is one
                    // ContentResolver stream per file. And with checkpoints in play, an interrupted
                    // run's partial copy of a directory and the last complete copy each hold
                    // entries the other does not; dropping either re-hashes that half of the
                    // subtree. Go: uniqueChildDirectories.
                    val previousDirManifests = previousEntries
                        .filter { it.type == ModelEntryType.DIRECTORY }
                        .mapNotNull { it.objectId?.takeIf(String::isNotEmpty) }
                        .distinct()
                        .mapNotNull { processor.loadDirManifest(it) }
                    walkDirectory(entry, entryPath, previousDirManifests, checkpointRegistry)
                }

                is File -> processFileEntry(entry, entryPath, previousEntries, checkpointRegistry)

                is Symlink -> {
                    processor.processSymlink(entry, entryPath, previousEntries)
                }

                else -> {
                    // Unknown entry type - skip
                    null
                }
            }

            dirEntry?.let { builder.addEntry(it) }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            handleEntryFailure(entry, entryPath, e, builder)
        }
    }

    /**
     * Decides what a failed entry means, the way Go does.
     *
     * A source-side read failure is recorded and the walk carries on. Anything else escaping a
     * *subdirectory* is a repository-side failure — writing its manifest, say — and aborts, because
     * a snapshot that claims to be complete while a whole subtree is missing is worse than no
     * snapshot at all.
     */
    private fun handleEntryFailure(
        entry: Entry,
        entryPath: String,
        error: Exception,
        builder: DirManifestBuilder,
    ) {
        if (error is DirectoryReadException) {
            // Recorded here, in the parent, and the child gets no entry of its own -- a phantom
            // empty directory would restore as data loss dressed up as data.
            handleError(error.path, error.cause ?: error, builder, isDirectory = true)
            return
        }
        if (entry.type == EntryType.DIRECTORY) {
            throw error
        }
        handleError(entryPath, error, builder, isDirectory = false)
    }

    /**
     * Handles an error according to the error policy.
     */
    private fun handleError(
        path: String,
        error: Throwable,
        builder: DirManifestBuilder,
        isDirectory: Boolean,
    ) {
        val isIgnored = if (isDirectory) {
            errorPolicy.ignoreDirectoryErrors ?: false
        } else {
            errorPolicy.ignoreFileErrors ?: false
        }

        progress.error(path, error, isIgnored)
        builder.addFailedEntry(path, isIgnored, error)

        // Recorded, and the walk continues -- the entry counts against the snapshot's
        // fatalErrorCount and the caller decides what to make of that.
        //
        // failFast stops the walk the same way a user's cancel does: Go's reportErrorAndMaybeCancel
        // calls u.Cancel() rather than returning an error, so the tree still drains and still writes
        // its partial manifests. Throwing here would have unwound past every builder and thrown away
        // the work already uploaded -- for a run that failed, which is precisely when the next
        // attempt most wants to skip re-doing it.
        if (failFast && !isIgnored) {
            failFastReason = "error: $path"
            cancel()
        }
    }

    companion object {
        /**
         * Go's `IncompleteReasonCheckpoint`. Stamped on every directory manifest a checkpoint
         * writes, so nothing can mistake a half-walked directory for a finished one.
         */
        const val CHECKPOINT_REASON = "checkpoint"

        private val logger: Logger = Logger.getLogger(TreeWalker::class.java.name)

        private fun joinPath(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent/$child"
    }
}

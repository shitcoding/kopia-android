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
     * @param previousEntry Optional previous entry for caching comparison
     * @return DirEntry with objectId populated
     */
    suspend fun processFile(
        file: File,
        relativePath: String,
        previousEntry: DirEntry?,
    ): DirEntry

    /**
     * Processes a symlink entry: reads target and stores.
     *
     * @param symlink The symlink to process
     * @param relativePath The relative path from the snapshot root
     * @param previousEntry Optional previous entry for caching comparison
     * @return DirEntry with objectId populated
     */
    suspend fun processSymlink(
        symlink: Symlink,
        relativePath: String,
        previousEntry: DirEntry?,
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

    /**
     * Exception thrown when an upload operation is cancelled.
     */
    class CancelledException : Exception("Upload cancelled")

    /**
     * Exception thrown when a fatal error occurs and failFast is enabled.
     */
    class FatalErrorException(message: String, cause: Throwable) : Exception(message, cause)

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
     * Checks if the walk has been cancelled.
     */
    fun isCancelled(): Boolean = cancelled.get()

    /**
     * Walks a directory tree and returns the root DirEntry with objectId.
     *
     * @param rootDir The root directory to walk
     * @param previousManifest Optional previous snapshot for caching
     * @return The root DirEntry with objectId pointing to the directory manifest
     */
    suspend fun walk(
        rootDir: Directory,
        previousManifest: DirManifest? = null,
    ): DirEntry = walkDirectory(rootDir, "", previousManifest)

    /**
     * Recursively walks a directory.
     *
     * @param dir The directory to walk
     * @param relativePath The relative path from root (empty for root)
     * @param previousManifest Previous manifest for caching comparison
     * @return DirEntry for the directory with objectId
     */
    private suspend fun walkDirectory(
        dir: Directory,
        relativePath: String,
        previousManifest: DirManifest?,
    ): DirEntry {
        if (cancelled.get()) {
            throw CancelledException()
        }

        val dirPath = if (relativePath.isEmpty()) dir.name else relativePath
        progress.startedDirectory(dirPath)

        val builder = DirManifestBuilder()

        try {
            // Build previous entries lookup map
            val previousEntries = previousManifest?.entries
                ?.associateBy { it.name }
                ?: emptyMap()

            // Collect all entries first
            val entries = mutableListOf<Entry>()
            val iterator = dir.iterate()
            try {
                while (true) {
                    val entry = iterator.next() ?: break
                    entries.add(entry)
                }
            } finally {
                iterator.close()
            }

            // Process entries in parallel
            coroutineScope {
                val jobs = entries.map { entry ->
                    async {
                        processEntry(
                            entry = entry,
                            parentPath = relativePath,
                            previousEntry = previousEntries[entry.name],
                            builder = builder,
                        )
                    }
                }
                jobs.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: CancelledException) {
            throw e
        } catch (e: FatalErrorException) {
            throw e
        } catch (e: Exception) {
            // Go: "always fail if the top level directory can't be read, otherwise a meaningless,
            // empty snapshot is created that can't be restored" (upload.go). A child's read failure
            // is recorded by the PARENT (see processEntry) and contributes no entry at all, so this
            // rethrows either way rather than uploading an empty manifest for the directory.
            throw DirectoryReadException(dirPath, e)
        }

        progress.finishedDirectory(dirPath)

        // Build and upload the directory manifest
        val manifest = builder.build(dir.modTime, incompleteReason = null)
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
    }

    /**
     * Hashes and uploads a single file, reporting progress around it.
     */
    private suspend fun processFileEntry(
        entry: File,
        entryPath: String,
        previousEntry: DirEntry?,
    ): DirEntry? = semaphore.withPermit {
        progress.hashingFile(entryPath)
        try {
            val result = processor.processFile(entry, entryPath, previousEntry)
            progress.finishedHashingFile(entryPath, entry.size)
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
        previousEntry: DirEntry?,
        builder: DirManifestBuilder,
    ) {
        if (cancelled.get()) {
            throw CancelledException()
        }

        val entryPath = joinPath(parentPath, entry.name)

        try {
            val dirEntry = when (entry) {
                is ErrorEntry -> {
                    handleError(entryPath, entry.error, builder, isDirectory = false)
                    null
                }

                is Directory -> {
                    // Recurse into the subdirectory, carrying its previous manifest so the entries
                    // below the root can be reused too. Without this, only root-level files ever had
                    // a previousEntry and every file deeper in the tree was re-read and re-hashed on
                    // every backup — over SAF that is one ContentResolver stream per file.
                    val previousDirManifest = previousEntry
                        ?.takeIf { it.type == ModelEntryType.DIRECTORY }
                        ?.objectId
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { processor.loadDirManifest(it) }
                    walkDirectory(entry, entryPath, previousDirManifest)
                }

                is File -> processFileEntry(entry, entryPath, previousEntry)

                is Symlink -> {
                    processor.processSymlink(entry, entryPath, previousEntry)
                }

                else -> {
                    // Unknown entry type - skip
                    null
                }
            }

            dirEntry?.let { builder.addEntry(it) }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: CancelledException) {
            throw e
        } catch (e: FatalErrorException) {
            throw e
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
        // fatalErrorCount and the caller decides what to make of that. Only failFast unwinds.
        if (failFast && !isIgnored) {
            throw FatalErrorException("Fatal error at $path", error)
        }
    }

    companion object {
        private fun joinPath(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent/$child"
    }
}

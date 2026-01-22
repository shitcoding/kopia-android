package org.kopiaKt.snapshot.restore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.Symlink
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.snapshotfs.RepositoryDirectory
import org.kopiaKt.snapshot.snapshotfs.RepositoryFile
import org.kopiaKt.snapshot.snapshotfs.RepositorySymlink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import org.kopiaKt.snapshot.model.EntryType as SnapshotEntryType

/**
 * Options for restore operations.
 *
 * Go type: restore.Options
 */
data class RestoreOptions(
    /**
     * Number of parallel restore workers.
     * 0 means use the number of available processors.
     */
    val parallel: Int = 0,

    /**
     * If true, skip files that already exist with matching metadata.
     */
    val incremental: Boolean = false,

    /**
     * If true, delete files in the target that don't exist in the snapshot.
     */
    val deleteExtra: Boolean = false,

    /**
     * If true, ignore errors and continue restoring other files.
     */
    val ignoreErrors: Boolean = false
)

/**
 * Restores snapshots from a repository to an output destination.
 *
 * This class handles parallel restoration with support for:
 * - Incremental restore (skip unchanged files)
 * - Delete extra files (sync mode)
 * - Configurable parallelism
 * - Progress reporting
 * - Error handling (ignore or fail)
 * - Cancellation support
 *
 * Go type: restore.Entry function and copier struct
 */
class SnapshotRestorer(
    private val output: RestoreOutput,
    private val options: RestoreOptions = RestoreOptions(),
    private val progress: RestoreProgress = NullRestoreProgress
) {
    private val cancelled = AtomicBoolean(false)

    /**
     * Cancel the ongoing restore operation.
     */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Restores a snapshot entry tree to the output.
     *
     * @param rootEntry The root entry (usually from snapshotRoot())
     * @return Restore statistics
     */
    suspend fun restore(rootEntry: Entry): RestoreStats = coroutineScope {
        cancelled.set(false)

        val numWorkers = if (options.parallel > 0) options.parallel
        else Runtime.getRuntime().availableProcessors()

        val effectiveWorkers = if (!output.parallelizable()) 1 else numWorkers

        val copier = Copier(
            output = output,
            progress = progress,
            incremental = options.incremental,
            deleteExtra = options.deleteExtra,
            ignoreErrors = options.ignoreErrors,
            cancelled = cancelled,
            semaphore = Semaphore(effectiveWorkers)
        )

        try {
            copier.copyEntry(rootEntry, "")
            output.close()
        } catch (e: Exception) {
            output.close()
            throw e
        }

        progress.snapshot()
    }

    /**
     * Restores a single file from a snapshot to the specified path.
     *
     * @param fileEntry The file entry to restore
     * @param targetPath The target file path
     * @return Restore statistics
     */
    suspend fun restoreFile(fileEntry: File, targetPath: Path): RestoreStats {
        cancelled.set(false)

        val dirEntry = getDirEntry(fileEntry)

        progress.fileEnqueued(fileEntry.size)

        if (options.incremental && output.fileExists("", dirEntry)) {
            progress.fileSkipped(fileEntry.size)
            return progress.snapshot()
        }

        val inputStream = fileEntry.open()
        try {
            output.writeFile(
                relativePath = "",
                entry = dirEntry,
                reader = inputStream,
                progressCallback = { bytesWritten ->
                    progress.fileProgress(bytesWritten)
                }
            )
            progress.fileRestored()
        } finally {
            inputStream.close()
        }

        return progress.snapshot()
    }

    /**
     * Internal copier that handles the restore logic.
     */
    private class Copier(
        private val output: RestoreOutput,
        private val progress: RestoreProgress,
        private val incremental: Boolean,
        private val deleteExtra: Boolean,
        private val ignoreErrors: Boolean,
        private val cancelled: AtomicBoolean,
        private val semaphore: Semaphore
    ) {
        /**
         * Copy an entry to the output.
         *
         * @param entry The entry to copy
         * @param targetPath Path relative to output root
         */
        suspend fun copyEntry(entry: Entry, targetPath: String) {
            if (cancelled.get()) {
                return
            }

            try {
                // Check for incremental skip
                if (incremental) {
                    when (entry) {
                        is File -> {
                            val dirEntry = getDirEntry(entry)
                            if (output.fileExists(targetPath, dirEntry)) {
                                progress.fileSkipped(entry.size)
                                return
                            }
                        }
                        is Symlink -> {
                            val dirEntry = getDirEntry(entry)
                            val target = entry.readlink()
                            if (output.symlinkExists(targetPath, dirEntry, target)) {
                                progress.fileSkipped(0)
                                return
                            }
                        }
                    }
                }

                copyEntryInternal(entry, targetPath)
            } catch (e: Exception) {
                if (ignoreErrors) {
                    progress.errorIgnored()
                } else {
                    throw e
                }
            }
        }

        private suspend fun copyEntryInternal(entry: Entry, targetPath: String) {
            when (entry) {
                is Directory -> copyDirectory(entry, targetPath)
                is File -> copyFile(entry, targetPath)
                is Symlink -> copySymlink(entry, targetPath)
                else -> {
                    // Unknown entry type, skip
                }
            }
        }

        private suspend fun copyDirectory(dir: Directory, targetPath: String) {
            progress.directoryEnqueued()

            val dirEntry = getDirEntry(dir)
            output.beginDirectory(targetPath, dirEntry)

            // Delete extra files if enabled
            if (deleteExtra && output is FilesystemOutput) {
                deleteExtraFilesInDir(output, dir, targetPath)
            }

            // Restore directory contents
            copyDirectoryContents(dir, targetPath)

            output.finishDirectory(targetPath, dirEntry)
            progress.directoryRestored()
        }

        private suspend fun copyDirectoryContents(dir: Directory, targetPath: String) = coroutineScope {
            val entries = dir.readEntries()

            if (entries.isEmpty()) {
                return@coroutineScope
            }

            // Separate directories and files for processing order
            // Directories are processed first so we can quickly enumerate the tree
            val (directories, files) = entries.partition { it.isDirectory() }

            // Process directories first (enqueue them)
            val dirJobs = directories.map { entry ->
                async {
                    val childPath = joinPath(targetPath, entry.name)
                    copyEntry(entry, childPath)
                }
            }

            // Enqueue files and symlinks
            files.forEach { entry ->
                if (entry.isSymlink()) {
                    progress.symlinkEnqueued()
                } else {
                    progress.fileEnqueued(entry.size)
                }
            }

            // Process files with parallelism
            val fileJobs = files.map { entry ->
                async {
                    semaphore.acquire()
                    try {
                        val childPath = joinPath(targetPath, entry.name)
                        copyEntry(entry, childPath)
                    } finally {
                        semaphore.release()
                    }
                }
            }

            // Wait for all to complete
            dirJobs.forEach { it.await() }
            fileJobs.forEach { it.await() }
        }

        private suspend fun copyFile(file: File, targetPath: String) {
            val dirEntry = getDirEntry(file)

            val inputStream = file.open()
            try {
                output.writeFile(
                    relativePath = targetPath,
                    entry = dirEntry,
                    reader = inputStream,
                    progressCallback = { bytesWritten ->
                        progress.fileProgress(bytesWritten)
                    }
                )
                progress.fileRestored()
            } finally {
                inputStream.close()
            }
        }

        private suspend fun copySymlink(symlink: Symlink, targetPath: String) {
            val dirEntry = getDirEntry(symlink)
            val target = symlink.readlink()

            output.createSymlink(targetPath, dirEntry, target)
            progress.symlinkRestored()
        }

        private suspend fun deleteExtraFilesInDir(
            fsOutput: FilesystemOutput,
            snapshotDir: Directory,
            targetPath: String
        ) {
            val targetDir = fsOutput.targetPath.resolve(targetPath)
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return
            }

            // Get snapshot entries
            val snapshotEntries = snapshotDir.readEntries()
            val snapshotDirNames = snapshotEntries
                .filter { it.isDirectory() }
                .map { it.name }
                .toSet()
            val snapshotFileNames = snapshotEntries
                .filterNot { it.isDirectory() }
                .map { it.name }
                .toSet()

            // Compare with existing entries
            Files.list(targetDir).use { stream ->
                stream.forEach { existingPath ->
                    val name = existingPath.name

                    if (existingPath.isDirectory()) {
                        if (name !in snapshotDirNames) {
                            // Delete directory not in snapshot
                            deleteRecursively(existingPath)
                            progress.directoryDeleted()
                        }
                    } else if (existingPath.isSymbolicLink()) {
                        if (name !in snapshotFileNames) {
                            Files.delete(existingPath)
                            progress.symlinkDeleted()
                        }
                    } else if (existingPath.isRegularFile()) {
                        if (name !in snapshotFileNames) {
                            Files.delete(existingPath)
                            progress.fileDeleted()
                        }
                    }
                }
            }
        }

        private fun deleteRecursively(path: Path) {
            if (path.isDirectory()) {
                Files.list(path).use { stream ->
                    stream.forEach { child ->
                        deleteRecursively(child)
                    }
                }
            }
            Files.delete(path)
        }

        private fun joinPath(base: String, name: String): String {
            return if (base.isEmpty()) name else "$base/$name"
        }
    }
}

/**
 * Helper function to get DirEntry from various entry types.
 */
private fun getDirEntry(entry: Entry): DirEntry {
    return when (entry) {
        is RepositoryFile -> entry.dirEntry()
        is RepositoryDirectory -> entry.dirEntry()
        is RepositorySymlink -> entry.dirEntry()
        else -> {
            // Create a synthetic DirEntry for non-repository entries
            DirEntry(
                name = entry.name,
                type = when (entry.type) {
                    EntryType.FILE -> SnapshotEntryType.FILE
                    EntryType.DIRECTORY -> SnapshotEntryType.DIRECTORY
                    EntryType.SYMLINK -> SnapshotEntryType.SYMLINK
                    else -> SnapshotEntryType.UNKNOWN
                },
                permissions = entry.mode,
                fileSize = entry.size,
                modTime = entry.modTime,
                userId = entry.owner.userId,
                groupId = entry.owner.groupId
            )
        }
    }
}

package org.kopiaKt.snapshot.upload

import org.kopiaKt.snapshot.fs.DirectorySummary as FsDirectorySummary
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.DirectorySummary
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.EntryWithError
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Builds directory manifests in a thread-safe manner.
 *
 * Collects directory entries and builds statistics as entries are added.
 * Can be cloned for checkpoint support.
 *
 * Go type: snapshotfs.DirManifestBuilder
 */
class DirManifestBuilder {
    private val lock = ReentrantLock()

    // Summary statistics
    private var totalFileCount: Long = 0
    private var totalFileSize: Long = 0
    private var totalDirCount: Long = 0
    private var totalSymlinkCount: Long = 0
    private var maxModTime: Instant = Instant.EPOCH
    private var fatalErrorCount: Int = 0
    private var ignoredErrorCount: Int = 0
    private val failedEntries = mutableListOf<EntryWithError>()

    // Collected entries
    private val entries = mutableListOf<DirEntry>()

    /**
     * Creates a clone of this builder.
     *
     * Used for checkpointing - clones the current state so the original
     * can continue being modified.
     */
    fun clone(): DirManifestBuilder {
        lock.withLock {
            val copy = DirManifestBuilder()
            copy.totalFileCount = totalFileCount
            copy.totalFileSize = totalFileSize
            copy.totalDirCount = totalDirCount
            copy.totalSymlinkCount = totalSymlinkCount
            copy.maxModTime = maxModTime
            copy.fatalErrorCount = fatalErrorCount
            copy.ignoredErrorCount = ignoredErrorCount
            copy.failedEntries.addAll(failedEntries)
            copy.entries.addAll(entries)
            return copy
        }
    }

    /**
     * Adds a directory entry to the builder.
     *
     * @param entry The directory entry to add
     */
    fun addEntry(entry: DirEntry) {
        lock.withLock {
            entries.add(entry)

            // Update max mod time
            entry.modTime?.let { modTime ->
                if (modTime.isAfter(maxModTime)) {
                    maxModTime = modTime
                }
            }

            // Update summary based on entry type
            when (entry.type) {
                EntryType.SYMLINK -> {
                    totalSymlinkCount++
                }

                EntryType.FILE -> {
                    totalFileCount++
                    totalFileSize += entry.fileSize
                }

                EntryType.DIRECTORY -> {
                    // Aggregate child directory summary
                    entry.dirSummary?.let { childSummary ->
                        totalFileCount += childSummary.totalFileCount
                        totalFileSize += childSummary.totalFileSize
                        totalDirCount += childSummary.totalDirCount
                        totalSymlinkCount += childSummary.totalSymlinkCount
                        fatalErrorCount += childSummary.fatalErrorCount
                        ignoredErrorCount += childSummary.ignoredErrorCount

                        childSummary.failedEntries?.forEach { failed ->
                            failedEntries.add(failed)
                        }

                        childSummary.maxModTime?.let { childMaxModTime ->
                            if (childMaxModTime.isAfter(maxModTime)) {
                                maxModTime = childMaxModTime
                            }
                        }
                    }
                }

                else -> {}
            }
        }
    }

    /**
     * Adds a failed entry and increments either ignored or fatal error count.
     *
     * @param relativePath The relative path of the failed entry
     * @param isIgnoredError Whether this error should be ignored
     * @param error The error that occurred
     */
    fun addFailedEntry(relativePath: String, isIgnoredError: Boolean, error: Throwable) {
        lock.withLock {
            if (isIgnoredError) {
                ignoredErrorCount++
            } else {
                fatalErrorCount++
            }

            failedEntries.add(
                EntryWithError(
                    entryPath = relativePath,
                    error = error.message ?: error.toString()
                )
            )
        }
    }

    /**
     * Builds the final directory manifest.
     *
     * @param dirModTime The modification time of the directory itself
     * @param incompleteReason Optional reason if this is an incomplete snapshot (e.g., "checkpoint", "canceled")
     * @return The completed directory manifest
     */
    fun build(dirModTime: Instant, incompleteReason: String? = null): DirManifest {
        lock.withLock {
            // Include this directory in the count
            val finalDirCount = totalDirCount + 1

            // Use directory mod time if no entries
            val finalMaxModTime = if (entries.isEmpty()) {
                dirModTime
            } else {
                maxModTime
            }

            // Sort and limit failed entries
            val sortedFailedEntries = failedEntries
                .sortedBy { it.entryPath }
                .take(MAX_FAILED_ENTRIES_PER_DIRECTORY)

            // Create summary
            val summary = DirectorySummary(
                totalFileSize = totalFileSize,
                totalFileCount = totalFileCount,
                totalSymlinkCount = totalSymlinkCount,
                totalDirCount = finalDirCount,
                maxModTime = finalMaxModTime,
                incompleteReason = incompleteReason,
                fatalErrorCount = fatalErrorCount,
                ignoredErrorCount = ignoredErrorCount,
                failedEntries = sortedFailedEntries.ifEmpty { null }
            )

            // Sort entries: directories first, then by name
            val sortedEntries = entries.sortedWith(compareBy(
                { it.type != EntryType.DIRECTORY },
                { it.name }
            ))

            return DirManifest(
                entries = sortedEntries,
                summary = summary
            )
        }
    }

    /**
     * Converts FS DirectorySummary to model DirectorySummary for compatibility.
     */
    fun buildSummary(dirModTime: Instant, incompleteReason: String? = null): DirectorySummary {
        lock.withLock {
            val finalDirCount = totalDirCount + 1
            val finalMaxModTime = if (entries.isEmpty()) dirModTime else maxModTime

            val sortedFailedEntries = failedEntries
                .sortedBy { it.entryPath }
                .take(MAX_FAILED_ENTRIES_PER_DIRECTORY)

            return DirectorySummary(
                totalFileSize = totalFileSize,
                totalFileCount = totalFileCount,
                totalSymlinkCount = totalSymlinkCount,
                totalDirCount = finalDirCount,
                maxModTime = finalMaxModTime,
                incompleteReason = incompleteReason,
                fatalErrorCount = fatalErrorCount,
                ignoredErrorCount = ignoredErrorCount,
                failedEntries = sortedFailedEntries.ifEmpty { null }
            )
        }
    }

    /**
     * Returns true if no entries have been added.
     */
    fun isEmpty(): Boolean = lock.withLock { entries.isEmpty() }

    /**
     * Returns the current number of entries.
     */
    fun entryCount(): Int = lock.withLock { entries.size }

    companion object {
        const val MAX_FAILED_ENTRIES_PER_DIRECTORY = 10
    }
}

/**
 * Converts filesystem DirectorySummary to model DirectorySummary.
 */
fun FsDirectorySummary.toModelSummary(): DirectorySummary {
    return DirectorySummary(
        totalFileSize = totalFileSize,
        totalFileCount = totalFileCount,
        totalSymlinkCount = totalSymlinkCount,
        totalDirCount = totalDirCount,
        maxModTime = maxModTime,
        fatalErrorCount = fatalErrorCount,
        ignoredErrorCount = ignoredErrorCount,
        failedEntries = failedEntries.map {
            EntryWithError(entryPath = it.entryPath, error = it.error.message ?: it.error.toString())
        }.ifEmpty { null }
    )
}

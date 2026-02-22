package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.ensureActive
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.ErrorEntry
import org.kopiaKt.snapshot.fs.IgnoreFS
import org.kopiaKt.snapshot.policy.FilesPolicy
import kotlin.coroutines.coroutineContext

/**
 * Size bucket categories for file size distribution reporting.
 */
enum class SizeBucket(val label: String, val minBytes: Long, val maxBytes: Long) {
    UNDER_1KB("< 1 KB", 0, 1_024 - 1),
    FROM_1KB_TO_10KB("1 KB - 10 KB", 1_024, 10_240 - 1),
    FROM_10KB_TO_100KB("10 KB - 100 KB", 10_240, 102_400 - 1),
    FROM_100KB_TO_1MB("100 KB - 1 MB", 102_400, 1_048_576 - 1),
    FROM_1MB_TO_10MB("1 MB - 10 MB", 1_048_576, 10_485_760 - 1),
    FROM_10MB_TO_100MB("10 MB - 100 MB", 10_485_760, 104_857_600 - 1),
    FROM_100MB_TO_1GB("100 MB - 1 GB", 104_857_600, 1_073_741_824 - 1),
    OVER_1GB("> 1 GB", 1_073_741_824, Long.MAX_VALUE);

    companion object {
        /**
         * Returns the bucket that a given file size falls into.
         */
        fun forSize(bytes: Long): SizeBucket {
            return entries.first { bytes in it.minBytes..it.maxBytes }
        }
    }
}

/**
 * Result of a snapshot estimation.
 * Provides counts, sizes, and distribution of files in a directory tree.
 */
data class EstimateResult(
    /** Total number of regular files found. */
    val totalFiles: Int = 0,
    /** Total size in bytes of all regular files. */
    val totalBytes: Long = 0,
    /** Number of subdirectories found (excluding the root). */
    val totalDirectories: Int = 0,
    /** Number of symlinks found. */
    val totalSymlinks: Int = 0,
    /** Number of files excluded by policy rules. */
    val excludedFiles: Int = 0,
    /** Total bytes of excluded files. */
    val excludedBytes: Long = 0,
    /** Number of entries that produced errors during estimation. */
    val errorCount: Int = 0,
    /** File count per size bucket. */
    val sizeDistribution: Map<SizeBucket, Int> = emptyMap()
)

/**
 * Progress snapshot emitted during estimation.
 */
data class EstimateProgress(
    /** Files counted so far. */
    val totalFiles: Int,
    /** Bytes counted so far. */
    val totalBytes: Long,
    /** Directories visited so far. */
    val totalDirectories: Int,
    /** Current directory being scanned. */
    val currentDirectory: String
)

/**
 * Walks a directory tree and estimates the total file count, total size,
 * and size distribution before a backup starts.
 *
 * The estimator does not read file contents -- it only inspects entry metadata
 * (size, type, name) and applies policy-based exclusion rules.
 *
 * Supports cancellation through structured concurrency: if the calling
 * coroutine is cancelled, the walk stops at the next entry boundary.
 *
 * Go equivalent: snapshot/upload estimation logic in the Uploader.
 */
object SnapshotEstimator {

    /**
     * Estimates file counts and sizes for the given directory tree.
     *
     * @param root The root directory to walk
     * @param policy Optional files policy for exclusion rules. Pass null to include everything.
     * @param onProgress Optional callback invoked after each file is processed.
     * @return The estimation result with counts, sizes, and distribution.
     */
    suspend fun estimate(
        root: Directory,
        policy: FilesPolicy? = null,
        onProgress: ((EstimateProgress) -> Unit)? = null
    ): EstimateResult {
        val state = EstimationState()
        val effectiveRoot = if (policy != null) {
            IgnoreFS.wrap(root, policy)
        } else {
            root
        }

        walkDirectory(effectiveRoot, root, policy, "", state, onProgress)

        return state.toResult()
    }

    /**
     * Recursively walks a directory, accumulating estimation state.
     *
     * @param filteredDir The directory with ignore rules applied (may be IgnoreFS-wrapped)
     * @param rawDir The original unwrapped directory (used for excluded entry detection)
     * @param policy The files policy (for detecting excluded entries)
     * @param relativePath Path relative to the root
     * @param state Mutable accumulator for estimation counters
     * @param onProgress Optional progress callback
     */
    private suspend fun walkDirectory(
        filteredDir: Directory,
        rawDir: Directory,
        policy: FilesPolicy?,
        relativePath: String,
        state: EstimationState,
        onProgress: ((EstimateProgress) -> Unit)?
    ) {
        coroutineContext.ensureActive()

        // Count excluded entries by comparing raw vs filtered
        if (policy != null && filteredDir !== rawDir) {
            countExcluded(rawDir, filteredDir, state)
        }

        val iterator = filteredDir.iterate()
        try {
            while (true) {
                coroutineContext.ensureActive()

                val entry = iterator.next() ?: break

                val entryPath = joinPath(relativePath, entry.name)

                when {
                    entry is ErrorEntry -> {
                        state.errorCount++
                    }
                    entry.type == EntryType.ERROR -> {
                        state.errorCount++
                    }
                    entry is Directory -> {
                        state.totalDirectories++
                        walkDirectory(entry, entry, policy, entryPath, state, onProgress)
                    }
                    entry.type == EntryType.FILE -> {
                        state.totalFiles++
                        state.totalBytes += entry.size
                        state.addToDistribution(entry.size)
                        onProgress?.invoke(state.toProgress(entryPath))
                    }
                    entry.type == EntryType.SYMLINK -> {
                        state.totalSymlinks++
                    }
                }
            }
        } finally {
            iterator.close()
        }
    }

    /**
     * Counts entries that exist in the raw directory but not in the filtered one.
     * These are the entries excluded by policy.
     */
    private suspend fun countExcluded(
        rawDir: Directory,
        filteredDir: Directory,
        state: EstimationState
    ) {
        val rawEntries = rawDir.readEntries()
        val filteredNames = filteredDir.readEntries().map { it.name }.toSet()

        for (entry in rawEntries) {
            if (entry.name !in filteredNames) {
                when {
                    entry.type == EntryType.FILE -> {
                        state.excludedFiles++
                        state.excludedBytes += entry.size
                    }
                    entry.type == EntryType.DIRECTORY -> {
                        // Excluded directories and their contents
                        state.excludedFiles += countFilesRecursive(entry as? Directory)
                    }
                }
            }
        }
    }

    /**
     * Recursively counts files in a directory (for excluded directory totals).
     */
    private suspend fun countFilesRecursive(dir: Directory?): Int {
        if (dir == null) return 0
        var count = 0
        val entries = dir.readEntries()
        for (entry in entries) {
            when {
                entry.type == EntryType.FILE -> count++
                entry is Directory -> count += countFilesRecursive(entry)
            }
        }
        return count
    }

    private fun joinPath(parent: String, child: String): String {
        return if (parent.isEmpty()) child else "$parent/$child"
    }
}

/**
 * Mutable accumulator used during directory walking.
 */
private class EstimationState {
    var totalFiles: Int = 0
    var totalBytes: Long = 0
    var totalDirectories: Int = 0
    var totalSymlinks: Int = 0
    var excludedFiles: Int = 0
    var excludedBytes: Long = 0
    var errorCount: Int = 0
    val sizeDistribution: MutableMap<SizeBucket, Int> = mutableMapOf()

    fun addToDistribution(bytes: Long) {
        val bucket = SizeBucket.forSize(bytes)
        sizeDistribution[bucket] = (sizeDistribution[bucket] ?: 0) + 1
    }

    fun toResult(): EstimateResult = EstimateResult(
        totalFiles = totalFiles,
        totalBytes = totalBytes,
        totalDirectories = totalDirectories,
        totalSymlinks = totalSymlinks,
        excludedFiles = excludedFiles,
        excludedBytes = excludedBytes,
        errorCount = errorCount,
        sizeDistribution = sizeDistribution.toMap()
    )

    fun toProgress(currentDirectory: String): EstimateProgress = EstimateProgress(
        totalFiles = totalFiles,
        totalBytes = totalBytes,
        totalDirectories = totalDirectories,
        currentDirectory = currentDirectory
    )
}

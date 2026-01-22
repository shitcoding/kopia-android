package org.kopiaKt.snapshot.restore

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Statistics about a restore operation.
 *
 * Go type: restore.Stats
 */
data class RestoreStats(
    val restoredTotalFileSize: Long = 0,
    val enqueuedTotalFileSize: Long = 0,
    val skippedTotalFileSize: Long = 0,

    val restoredFileCount: Int = 0,
    val restoredDirCount: Int = 0,
    val restoredSymlinkCount: Int = 0,
    val enqueuedFileCount: Int = 0,
    val enqueuedDirCount: Int = 0,
    val enqueuedSymlinkCount: Int = 0,
    val skippedCount: Int = 0,
    val deletedFilesCount: Int = 0,
    val deletedSymlinkCount: Int = 0,
    val deletedDirCount: Int = 0,
    val ignoredErrorCount: Int = 0
) {
    /**
     * Returns true if the restore is complete (all enqueued items processed).
     */
    val isComplete: Boolean
        get() = enqueuedFileCount == restoredFileCount + skippedCount &&
                enqueuedDirCount == restoredDirCount &&
                enqueuedSymlinkCount == restoredSymlinkCount

    /**
     * Returns the progress percentage (0-100).
     */
    val progressPercent: Float
        get() {
            val total = enqueuedTotalFileSize
            if (total == 0L) return 100f
            return ((restoredTotalFileSize + skippedTotalFileSize) * 100f) / total
        }
}

/**
 * Callback for restore progress notifications.
 *
 * Go type: restore.ProgressCallback
 */
typealias RestoreProgressCallback = (stats: RestoreStats) -> Unit

/**
 * Interface for tracking restore progress.
 *
 * Thread-safe implementation for collecting statistics during restore.
 */
interface RestoreProgress {
    /**
     * Called when a file is enqueued for restoration.
     */
    fun fileEnqueued(size: Long)

    /**
     * Called when a directory is enqueued.
     */
    fun directoryEnqueued()

    /**
     * Called when a symlink is enqueued.
     */
    fun symlinkEnqueued()

    /**
     * Called when file bytes are written during restoration.
     */
    fun fileProgress(bytesWritten: Long)

    /**
     * Called when a file is completely restored.
     */
    fun fileRestored()

    /**
     * Called when a directory is completely restored.
     */
    fun directoryRestored()

    /**
     * Called when a symlink is restored.
     */
    fun symlinkRestored()

    /**
     * Called when a file is skipped (incremental restore).
     */
    fun fileSkipped(size: Long)

    /**
     * Called when an error is ignored.
     */
    fun errorIgnored()

    /**
     * Called when an extra file is deleted.
     */
    fun fileDeleted()

    /**
     * Called when an extra symlink is deleted.
     */
    fun symlinkDeleted()

    /**
     * Called when an extra directory is deleted.
     */
    fun directoryDeleted()

    /**
     * Returns a snapshot of current statistics.
     */
    fun snapshot(): RestoreStats
}

/**
 * Thread-safe implementation of RestoreProgress.
 *
 * Uses atomic operations for all counters to support parallel restoration.
 */
class CountingRestoreProgress(
    private val callback: RestoreProgressCallback? = null
) : RestoreProgress {

    private val restoredTotalFileSize = AtomicLong(0)
    private val enqueuedTotalFileSize = AtomicLong(0)
    private val skippedTotalFileSize = AtomicLong(0)

    private val restoredFileCount = AtomicInteger(0)
    private val restoredDirCount = AtomicInteger(0)
    private val restoredSymlinkCount = AtomicInteger(0)
    private val enqueuedFileCount = AtomicInteger(0)
    private val enqueuedDirCount = AtomicInteger(0)
    private val enqueuedSymlinkCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    private val deletedFilesCount = AtomicInteger(0)
    private val deletedSymlinkCount = AtomicInteger(0)
    private val deletedDirCount = AtomicInteger(0)
    private val ignoredErrorCount = AtomicInteger(0)

    override fun fileEnqueued(size: Long) {
        enqueuedFileCount.incrementAndGet()
        enqueuedTotalFileSize.addAndGet(size)
        notifyProgress()
    }

    override fun directoryEnqueued() {
        enqueuedDirCount.incrementAndGet()
        notifyProgress()
    }

    override fun symlinkEnqueued() {
        enqueuedSymlinkCount.incrementAndGet()
        notifyProgress()
    }

    override fun fileProgress(bytesWritten: Long) {
        restoredTotalFileSize.addAndGet(bytesWritten)
        notifyProgress()
    }

    override fun fileRestored() {
        restoredFileCount.incrementAndGet()
        notifyProgress()
    }

    override fun directoryRestored() {
        restoredDirCount.incrementAndGet()
        notifyProgress()
    }

    override fun symlinkRestored() {
        restoredSymlinkCount.incrementAndGet()
        notifyProgress()
    }

    override fun fileSkipped(size: Long) {
        skippedCount.incrementAndGet()
        skippedTotalFileSize.addAndGet(size)
        notifyProgress()
    }

    override fun errorIgnored() {
        ignoredErrorCount.incrementAndGet()
        notifyProgress()
    }

    override fun fileDeleted() {
        deletedFilesCount.incrementAndGet()
        notifyProgress()
    }

    override fun symlinkDeleted() {
        deletedSymlinkCount.incrementAndGet()
        notifyProgress()
    }

    override fun directoryDeleted() {
        deletedDirCount.incrementAndGet()
        notifyProgress()
    }

    override fun snapshot(): RestoreStats = RestoreStats(
        restoredTotalFileSize = restoredTotalFileSize.get(),
        enqueuedTotalFileSize = enqueuedTotalFileSize.get(),
        skippedTotalFileSize = skippedTotalFileSize.get(),
        restoredFileCount = restoredFileCount.get(),
        restoredDirCount = restoredDirCount.get(),
        restoredSymlinkCount = restoredSymlinkCount.get(),
        enqueuedFileCount = enqueuedFileCount.get(),
        enqueuedDirCount = enqueuedDirCount.get(),
        enqueuedSymlinkCount = enqueuedSymlinkCount.get(),
        skippedCount = skippedCount.get(),
        deletedFilesCount = deletedFilesCount.get(),
        deletedSymlinkCount = deletedSymlinkCount.get(),
        deletedDirCount = deletedDirCount.get(),
        ignoredErrorCount = ignoredErrorCount.get()
    )

    private fun notifyProgress() {
        callback?.invoke(snapshot())
    }
}

/**
 * No-op implementation of RestoreProgress.
 */
object NullRestoreProgress : RestoreProgress {
    override fun fileEnqueued(size: Long) {}
    override fun directoryEnqueued() {}
    override fun symlinkEnqueued() {}
    override fun fileProgress(bytesWritten: Long) {}
    override fun fileRestored() {}
    override fun directoryRestored() {}
    override fun symlinkRestored() {}
    override fun fileSkipped(size: Long) {}
    override fun errorIgnored() {}
    override fun fileDeleted() {}
    override fun symlinkDeleted() {}
    override fun directoryDeleted() {}
    override fun snapshot(): RestoreStats = RestoreStats()
}

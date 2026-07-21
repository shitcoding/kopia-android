package org.kopiaKt.snapshot.restore

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RestoreProgressTest {

    @Test
    fun `CountingRestoreProgress tracks file enqueue and restore`() {
        val progress = CountingRestoreProgress()

        progress.fileEnqueued(100)
        progress.fileEnqueued(200)
        progress.fileRestored()
        progress.fileProgress(50)

        val stats = progress.snapshot()
        assertThat(stats.enqueuedFileCount).isEqualTo(2)
        assertThat(stats.enqueuedTotalFileSize).isEqualTo(300)
        assertThat(stats.restoredFileCount).isEqualTo(1)
        assertThat(stats.restoredTotalFileSize).isEqualTo(50)
    }

    @Test
    fun `CountingRestoreProgress tracks directory enqueue and restore`() {
        val progress = CountingRestoreProgress()

        progress.directoryEnqueued()
        progress.directoryEnqueued()
        progress.directoryRestored()

        val stats = progress.snapshot()
        assertThat(stats.enqueuedDirCount).isEqualTo(2)
        assertThat(stats.restoredDirCount).isEqualTo(1)
    }

    @Test
    fun `CountingRestoreProgress tracks symlink enqueue and restore`() {
        val progress = CountingRestoreProgress()

        progress.symlinkEnqueued()
        progress.symlinkRestored()

        val stats = progress.snapshot()
        assertThat(stats.enqueuedSymlinkCount).isEqualTo(1)
        assertThat(stats.restoredSymlinkCount).isEqualTo(1)
    }

    @Test
    fun `CountingRestoreProgress tracks skipped files`() {
        val progress = CountingRestoreProgress()

        progress.fileEnqueued(500)
        progress.fileSkipped(500)

        val stats = progress.snapshot()
        assertThat(stats.enqueuedFileCount).isEqualTo(1)
        assertThat(stats.skippedCount).isEqualTo(1)
        assertThat(stats.skippedTotalFileSize).isEqualTo(500)
    }

    @Test
    fun `CountingRestoreProgress tracks ignored errors`() {
        val progress = CountingRestoreProgress()

        progress.errorIgnored()
        progress.errorIgnored()

        val stats = progress.snapshot()
        assertThat(stats.ignoredErrorCount).isEqualTo(2)
    }

    @Test
    fun `CountingRestoreProgress tracks deleted items`() {
        val progress = CountingRestoreProgress()

        progress.fileDeleted()
        progress.fileDeleted()
        progress.symlinkDeleted()
        progress.directoryDeleted()

        val stats = progress.snapshot()
        assertThat(stats.deletedFilesCount).isEqualTo(2)
        assertThat(stats.deletedSymlinkCount).isEqualTo(1)
        assertThat(stats.deletedDirCount).isEqualTo(1)
    }

    @Test
    fun `CountingRestoreProgress invokes callback on updates`() {
        var callbackCount = 0
        val progress = CountingRestoreProgress { callbackCount++ }

        progress.fileEnqueued(100)
        progress.fileRestored()
        progress.directoryEnqueued()

        assertThat(callbackCount).isEqualTo(3)
    }

    @Test
    fun `CountingRestoreProgress is thread-safe`() {
        val progress = CountingRestoreProgress()
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(100)

        repeat(100) { i ->
            executor.submit {
                try {
                    when (i % 5) {
                        0 -> progress.fileEnqueued(100)
                        1 -> progress.fileRestored()
                        2 -> progress.directoryEnqueued()
                        3 -> progress.fileProgress(10)
                        4 -> progress.fileSkipped(50)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        executor.shutdown()

        val stats = progress.snapshot()
        assertThat(stats.enqueuedFileCount).isEqualTo(20)
        assertThat(stats.restoredFileCount).isEqualTo(20)
        assertThat(stats.enqueuedDirCount).isEqualTo(20)
        assertThat(stats.restoredTotalFileSize).isEqualTo(200)
        assertThat(stats.skippedCount).isEqualTo(20)
        assertThat(stats.skippedTotalFileSize).isEqualTo(1000)
    }

    @Test
    fun `RestoreStats isComplete returns correct value`() {
        val incomplete = RestoreStats(
            enqueuedFileCount = 10,
            enqueuedDirCount = 5,
            enqueuedSymlinkCount = 2,
            restoredFileCount = 5,
            restoredDirCount = 5,
            restoredSymlinkCount = 2,
            skippedCount = 2, // 5 restored + 2 skipped = 7, not 10
        )
        assertThat(incomplete.isComplete).isFalse()

        val complete = RestoreStats(
            enqueuedFileCount = 10,
            enqueuedDirCount = 5,
            enqueuedSymlinkCount = 2,
            restoredFileCount = 7,
            restoredDirCount = 5,
            restoredSymlinkCount = 2,
            skippedCount = 3, // 7 restored + 3 skipped = 10
        )
        assertThat(complete.isComplete).isTrue()
    }

    @Test
    fun `RestoreStats progressPercent calculates correctly`() {
        val stats = RestoreStats(
            enqueuedTotalFileSize = 1000,
            restoredTotalFileSize = 400,
            skippedTotalFileSize = 100,
        )
        assertThat(stats.progressPercent).isEqualTo(50f)
    }

    @Test
    fun `RestoreStats progressPercent returns 100 for zero size`() {
        val stats = RestoreStats(enqueuedTotalFileSize = 0)
        assertThat(stats.progressPercent).isEqualTo(100f)
    }

    @Test
    fun `NullRestoreProgress returns empty stats`() {
        NullRestoreProgress.fileEnqueued(100)
        NullRestoreProgress.fileRestored()

        val stats = NullRestoreProgress.snapshot()
        assertThat(stats.enqueuedFileCount).isEqualTo(0)
        assertThat(stats.restoredFileCount).isEqualTo(0)
    }
}

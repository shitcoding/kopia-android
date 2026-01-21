package org.kopiaKt.snapshot.upload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UploadProgressTest {

    @Test
    fun `NullUploadProgress returns false for enabled`() {
        val progress = NullUploadProgress()
        assertFalse(progress.enabled())
    }

    @Test
    fun `NullUploadProgress estimationParameters returns classic type`() {
        val progress = NullUploadProgress()
        val params = progress.estimationParameters()
        assertEquals(EstimationType.CLASSIC, params.type)
    }

    @Test
    fun `CountingUploadProgress returns true for enabled`() {
        val progress = CountingUploadProgress()
        assertTrue(progress.enabled())
    }

    @Test
    fun `CountingUploadProgress tracks cached files`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.cachedFile("/path/to/file1", 100)
        progress.cachedFile("/path/to/file2", 200)

        val snapshot = progress.snapshot()
        assertEquals(2, snapshot.totalCachedFiles)
        assertEquals(300L, snapshot.totalCachedBytes)
    }

    @Test
    fun `CountingUploadProgress tracks hashed bytes`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.hashedBytes(1000)
        progress.hashedBytes(2000)

        val snapshot = progress.snapshot()
        assertEquals(3000L, snapshot.totalHashedBytes)
    }

    @Test
    fun `CountingUploadProgress tracks hashed files`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.finishedHashingFile("file1", 100)
        progress.finishedHashingFile("file2", 200)

        val snapshot = progress.snapshot()
        assertEquals(2, snapshot.totalHashedFiles)
    }

    @Test
    fun `CountingUploadProgress tracks uploaded bytes`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.uploadedBytes(5000)
        progress.uploadedBytes(3000)

        val snapshot = progress.snapshot()
        assertEquals(8000L, snapshot.totalUploadedBytes)
    }

    @Test
    fun `CountingUploadProgress tracks excluded files and dirs`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.excludedFile("file1", 100)
        progress.excludedFile("file2", 200)
        progress.excludedDir("dir1")

        val snapshot = progress.snapshot()
        assertEquals(2, snapshot.totalExcludedFiles)
        assertEquals(1, snapshot.totalExcludedDirs)
    }

    @Test
    fun `CountingUploadProgress tracks errors`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.error("/path/to/file", RuntimeException("test error"), isIgnored = false)
        progress.error("/path/to/file2", RuntimeException("ignored error"), isIgnored = true)

        val snapshot = progress.snapshot()
        assertEquals(1, snapshot.fatalErrorCount)
        assertEquals(1, snapshot.ignoredErrorCount)
        assertEquals("/path/to/file2", snapshot.lastErrorPath)
        assertEquals("ignored error", snapshot.lastError)
    }

    @Test
    fun `CountingUploadProgress tracks estimated data`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.estimatedDataSize(1000, 1024 * 1024 * 1024)

        val snapshot = progress.snapshot()
        assertEquals(1000L, snapshot.estimatedFiles)
        assertEquals(1024 * 1024 * 1024L, snapshot.estimatedBytes)
    }

    @Test
    fun `CountingUploadProgress tracks current directory`() {
        val progress = CountingUploadProgress()
        progress.uploadStarted()

        progress.startedDirectory("/home/user/documents")

        val snapshot = progress.snapshot()
        assertEquals("/home/user/documents", snapshot.currentDirectory)
    }

    @Test
    fun `CountingUploadProgress resets on uploadStarted`() {
        val progress = CountingUploadProgress()

        // First upload
        progress.uploadStarted()
        progress.hashedBytes(1000)
        progress.cachedFile("file", 500)

        var snapshot = progress.snapshot()
        assertEquals(1000L, snapshot.totalHashedBytes)
        assertEquals(1, snapshot.totalCachedFiles)

        // Second upload should reset counters
        progress.uploadStarted()

        snapshot = progress.snapshot()
        assertEquals(0L, snapshot.totalHashedBytes)
        assertEquals(0, snapshot.totalCachedFiles)
    }

    @Test
    fun `CallbackUploadProgress invokes callback on progress`() {
        val snapshots = mutableListOf<UploadCounters>()
        val progress = CallbackUploadProgress { counters ->
            snapshots.add(counters)
        }

        progress.uploadStarted()
        progress.hashedBytes(1000)
        progress.uploadedBytes(500)
        progress.finishedFile("file.txt", null)
        progress.finishedDirectory("/dir")

        // Callback should be invoked for each progress event
        assertEquals(4, snapshots.size)

        // Last snapshot should have accumulated values
        assertEquals(1000L, snapshots.last().totalHashedBytes)
        assertEquals(500L, snapshots.last().totalUploadedBytes)
    }

    @Test
    fun `EstimationParameters has default threshold`() {
        val params = EstimationParameters()
        assertEquals(EstimationType.CLASSIC, params.type)
        assertEquals(300_000L, params.adaptiveThreshold)
    }

    @Test
    fun `UploadCounters data class has correct defaults`() {
        val counters = UploadCounters()
        assertEquals(0L, counters.totalCachedBytes)
        assertEquals(0L, counters.totalHashedBytes)
        assertEquals(0L, counters.totalUploadedBytes)
        assertEquals(0, counters.totalCachedFiles)
        assertEquals(0, counters.totalHashedFiles)
        assertEquals(0, counters.fatalErrorCount)
        assertEquals(0, counters.ignoredErrorCount)
        assertEquals("", counters.currentDirectory)
        assertEquals("", counters.lastErrorPath)
        assertEquals("", counters.lastError)
    }
}

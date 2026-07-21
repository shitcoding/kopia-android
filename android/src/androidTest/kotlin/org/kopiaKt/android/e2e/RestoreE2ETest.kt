package org.kopiaKt.android.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.restore.CountingRestoreProgress
import org.kopiaKt.snapshot.restore.FilesystemOutput
import org.kopiaKt.snapshot.restore.FilesystemOutputOptions
import org.kopiaKt.snapshot.restore.RestoreOptions
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import java.io.File

/**
 * E2E tests for restore operations on Android.
 *
 * Tests that backed up data can be correctly restored to the device.
 * Verifies data integrity and various restore scenarios.
 *
 * Run with: ./gradlew :android:connectedAndroidTest --tests "*.RestoreE2ETest"
 */
@RunWith(AndroidJUnit4::class)
class RestoreE2ETest : AndroidE2ETestBase() {

    companion object {
        private const val TAG = "RestoreE2ETest"
    }

    @Test
    fun restoreSimpleFiles(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreSimpleFiles on ${getDeviceInfo()}")

        // Create and backup test data
        val testData = createSimpleTestData()
        val repository = createRepository()

        try {
            // Backup
            val manifestId = performBackup(repository, "Simple backup for restore test")
            assertThat(manifestId).isNotNull()

            // Get the snapshot manifest
            repository.refresh()
            val snapshot = getLatestSnapshot(repository)
            assertThat(snapshot).isNotNull()

            // Restore
            val restoreProgress = CountingRestoreProgress()
            performRestore(repository, snapshot!!, restoreProgress)

            val stats = restoreProgress.snapshot()
            Log.i(TAG, "Restore completed: ${stats.restoredFileCount} files restored")

            // Verify restored data matches original
            val comparison = verifyRestoredData(testData)
            assertThat(comparison.identical).isTrue()

            Log.i(TAG, "Verification passed: all ${testData.fileCount} files match")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreComplexFiles(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreComplexFiles on ${getDeviceInfo()}")

        requireStorage(10 * 1024 * 1024) // 10 MB

        // Create and backup complex test data
        val testData = createComplexTestData()
        val repository = createRepository()

        try {
            // Backup
            val manifestId = performBackup(repository, "Complex backup for restore test")
            assertThat(manifestId).isNotNull()

            repository.refresh()
            val snapshot = getLatestSnapshot(repository)
            assertThat(snapshot).isNotNull()

            // Restore
            val restoreProgress = CountingRestoreProgress()
            performRestore(repository, snapshot!!, restoreProgress)

            val stats = restoreProgress.snapshot()
            Log.i(TAG, "Restored ${stats.restoredFileCount} files, ${stats.restoredDirCount} directories")

            // Verify restored data matches original
            val comparison = verifyRestoredData(testData)
            assertThat(comparison.identical).isTrue()
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreLargeBinaryFile(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreLargeBinaryFile on ${getDeviceInfo()}")

        requireStorage(50 * 1024 * 1024) // 50 MB

        // Create a large binary file
        val testFile = createPatternFile("large_binary.bin", ContentPattern.RANDOM, 4 * 1024 * 1024)

        val repository = createRepository()
        try {
            // Backup
            val manifestId = performBackup(repository, "Large binary backup")
            assertThat(manifestId).isNotNull()

            repository.refresh()
            val snapshot = getLatestSnapshot(repository)

            // Restore
            performRestore(repository, snapshot!!)

            // Verify the restored file
            assertThat(verifyFile(testFile)).isTrue()
            Log.i(TAG, "Large binary file verified: ${testFile.content.size} bytes")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreEmptyDirectory(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreEmptyDirectory on ${getDeviceInfo()}")

        // Create empty directories
        File(sourceDir, "empty1").mkdirs()
        File(sourceDir, "empty2/nested_empty").mkdirs()
        File(sourceDir, "with_file").mkdirs()
        File(sourceDir, "with_file/file.txt").writeText("content")

        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Empty directory test")
            assertThat(manifestId).isNotNull()

            repository.refresh()
            val snapshot = getLatestSnapshot(repository)

            performRestore(repository, snapshot!!)

            // Verify empty directories exist
            assertThat(File(restoreDir, "empty1").isDirectory).isTrue()
            assertThat(File(restoreDir, "empty2/nested_empty").isDirectory).isTrue()
            assertThat(File(restoreDir, "with_file/file.txt").readText()).isEqualTo("content")

            Log.i(TAG, "Empty directories verified")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreWithOverwrite(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreWithOverwrite on ${getDeviceInfo()}")

        // Create and backup
        val originalContent = "Original content"
        val file = File(sourceDir, "overwrite_test.txt")
        file.writeText(originalContent)

        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Overwrite test backup")

            repository.refresh()
            val snapshot = getLatestSnapshot(repository)

            // Pre-create restore directory with different content
            restoreDir.mkdirs()
            val existingFile = File(restoreDir, "overwrite_test.txt")
            existingFile.writeText("Different content that should be overwritten")

            // Restore with overwrite
            val output = FilesystemOutput(
                targetPath = restoreDir.toPath(),
                options = FilesystemOutputOptions(
                    overwriteFiles = true,
                    overwriteDirectories = true,
                ),
            )
            performRestoreWithOutput(repository, snapshot!!, output)

            // Verify content was overwritten
            assertThat(existingFile.readText()).isEqualTo(originalContent)
            Log.i(TAG, "Overwrite verified")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreIncrementalSkipUnchanged(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreIncrementalSkipUnchanged on ${getDeviceInfo()}")

        // Create and backup two files
        File(sourceDir, "unchanged.txt").writeText("This won't change")
        File(sourceDir, "will_change.txt").writeText("Original")

        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Incremental restore test")

            repository.refresh()
            val snapshot = getLatestSnapshot(repository)

            // First restore - both files should be restored
            val firstProgress = CountingRestoreProgress()
            performRestore(repository, snapshot!!, firstProgress)

            val firstStats = firstProgress.snapshot()
            Log.i(TAG, "First restore: ${firstStats.restoredFileCount} files restored")
            assertThat(firstStats.restoredFileCount).isEqualTo(2)

            // Verify initial restore content
            assertThat(File(restoreDir, "unchanged.txt").readText()).isEqualTo("This won't change")
            assertThat(File(restoreDir, "will_change.txt").readText()).isEqualTo("Original")

            // Modify one file in the restore directory to simulate local change
            File(restoreDir, "will_change.txt").writeText("Modified after restore")

            // Second incremental restore - should skip unchanged file, re-restore changed one
            val progress = CountingRestoreProgress()
            performRestore(
                repository = repository,
                snapshot = snapshot,
                progress = progress,
                options = RestoreOptions(incremental = true),
            )

            val stats = progress.snapshot()
            Log.i(TAG, "Incremental restore: ${stats.skippedCount} skipped, ${stats.restoredFileCount} restored")

            // The unchanged file should be skipped (metadata matches), the modified one restored
            assertThat(stats.skippedCount).isAtLeast(1)
            // Total processed files (skipped + restored) should cover both files
            assertThat(stats.skippedCount + stats.restoredFileCount).isEqualTo(2)

            // Verify that the modified file was restored back to the original content
            assertThat(File(restoreDir, "will_change.txt").readText()).isEqualTo("Original")
            // Verify that the unchanged file is still correct
            assertThat(File(restoreDir, "unchanged.txt").readText()).isEqualTo("This won't change")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreParallel(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreParallel on ${getDeviceInfo()}")

        requireStorage(20 * 1024 * 1024) // 20 MB

        // Create many files
        val testData = createLargeTestData(fileCount = 50, avgFileSize = 10 * 1024)

        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Parallel restore test")

            repository.refresh()
            val snapshot = getLatestSnapshot(repository)

            val startTime = System.currentTimeMillis()

            // Restore with parallelism
            performRestore(
                repository = repository,
                snapshot = snapshot!!,
                options = RestoreOptions(parallel = 4),
            )

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Parallel restore of ${testData.fileCount} files took ${duration}ms")

            // Verify
            val comparison = verifyRestoredData(testData)
            assertThat(comparison.identical).isTrue()
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreSpecificSnapshot(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreSpecificSnapshot on ${getDeviceInfo()}")

        val repository = createRepository()
        try {
            // Create first backup with content A and capture its manifest ID
            File(sourceDir, "version.txt").writeText("Version A")
            val manifestId1 = performBackup(repository, "Snapshot A")
            Log.i(TAG, "Snapshot A manifest: ${manifestId1.value}")

            // Create second backup with content B and capture its manifest ID
            File(sourceDir, "version.txt").writeText("Version B")
            val manifestId2 = performBackup(repository, "Snapshot B")
            Log.i(TAG, "Snapshot B manifest: ${manifestId2.value}")

            repository.refresh()

            // Verify both snapshots exist
            val manifests = repository.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            assertThat(manifests.size).isEqualTo(2)

            // Restore snapshot A using its exact manifest ID (deterministic)
            val (snapshotA, _) = repository.getManifest(manifestId1, SnapshotManifest.serializer())
            performRestore(repository, snapshotA)

            val restoredContentA = File(restoreDir, "version.txt").readText()
            Log.i(TAG, "Restored from snapshot A: $restoredContentA")
            assertThat(restoredContentA).isEqualTo("Version A")

            // Clear restore directory and restore snapshot B
            restoreDir.deleteRecursively()
            restoreDir.mkdirs()

            val (snapshotB, _) = repository.getManifest(manifestId2, SnapshotManifest.serializer())
            performRestore(repository, snapshotB)

            val restoredContentB = File(restoreDir, "version.txt").readText()
            Log.i(TAG, "Restored from snapshot B: $restoredContentB")
            assertThat(restoredContentB).isEqualTo("Version B")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreDeepNestedStructure(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreDeepNestedStructure on ${getDeviceInfo()}")

        // Create deeply nested structure
        var currentDir = sourceDir
        val depth = 10
        val expectedFiles = mutableListOf<Pair<String, String>>()

        for (i in 1..depth) {
            currentDir = File(currentDir, "level$i")
            currentDir.mkdirs()
            val fileName = "file_at_level$i.txt"
            val content = "Content at level $i"
            File(currentDir, fileName).writeText(content)
            expectedFiles.add(fileName to content)
        }

        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Deep nested backup")

            repository.refresh()
            val snapshot = getLatestSnapshot(repository)

            performRestore(repository, snapshot!!)

            // Verify all nested files
            var verifyDir = restoreDir
            for (i in 1..depth) {
                verifyDir = File(verifyDir, "level$i")
                assertThat(verifyDir.isDirectory).isTrue()
                val file = File(verifyDir, "file_at_level$i.txt")
                assertThat(file.exists()).isTrue()
                assertThat(file.readText()).isEqualTo("Content at level $i")
            }

            Log.i(TAG, "Deep nested structure verified: $depth levels")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupAndRestoreRoundTrip(): Unit = runBlocking {
        Log.i(TAG, "Test: backupAndRestoreRoundTrip on ${getDeviceInfo()}")

        requireStorage(5 * 1024 * 1024) // 5 MB

        // Create comprehensive test data
        val testData = createComplexTestData()
        Log.i(TAG, "Created ${testData.fileCount} files, ${testData.totalSize} bytes total")

        // Create repository
        val repository = createRepository()

        try {
            // Backup
            val uploadProgress = CountingUploadProgress()
            val manifestId = performBackup(repository, "Full round-trip test", uploadProgress)
            assertThat(manifestId).isNotNull()

            val uploadStats = uploadProgress.snapshot()
            Log.i(TAG, "Backup: ${uploadStats.totalHashedFiles} files, ${uploadStats.totalHashedBytes} bytes")

            // Get snapshot
            repository.refresh()
            val snapshot = getLatestSnapshot(repository)
            assertThat(snapshot).isNotNull()

            // Restore
            val restoreProgress = CountingRestoreProgress()
            performRestore(repository, snapshot!!, restoreProgress)

            val restoreStats = restoreProgress.snapshot()
            Log.i(TAG, "Restore: ${restoreStats.restoredFileCount} files, ${restoreStats.restoredTotalFileSize} bytes")

            // Full verification
            val comparison = verifyRestoredData(testData)
            assertThat(comparison.identical).isTrue()

            Log.i(TAG, "Round trip SUCCESS: all ${testData.fileCount} files verified")
        } finally {
            repository.close()
        }
        Unit
    }

    // ===================
    // Helper methods
    // ===================

    private suspend fun performBackup(
        repository: DirectRepository,
        description: String,
        progress: CountingUploadProgress = CountingUploadProgress(),
    ): ManifestId {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            val source = SourceInfo(
                host = android.os.Build.DEVICE,
                userName = "android",
                path = sourceDir.absolutePath,
            )

            val uploader = SnapshotUploader(
                writer = writer,
                source = source,
                policy = Policy(),
                progress = progress,
            )

            val rootDir = LocalFilesystem.directory(sourceDir.toPath())

            val result = uploader.upload(
                rootDir = rootDir,
                options = UploadOptions(description = description),
            )

            writer.flush()

            if (result.incomplete) {
                throw RuntimeException("Backup incomplete: ${result.incompleteReason}")
            }

            return result.manifestId
        } finally {
            writer.close()
        }
    }

    private suspend fun getLatestSnapshot(repository: DirectRepository): SnapshotManifest? {
        val manifests = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
        )
        if (manifests.isEmpty()) return null

        // Get the most recent by modification time
        val latest = manifests.maxByOrNull { it.modTime }
        val (snapshot, _) = repository.getManifest(latest!!.id, SnapshotManifest.serializer())
        return snapshot
    }

    private suspend fun performRestore(
        repository: DirectRepository,
        snapshot: SnapshotManifest,
        progress: CountingRestoreProgress = CountingRestoreProgress(),
        options: RestoreOptions = RestoreOptions(),
    ) {
        val output = FilesystemOutput(
            targetPath = restoreDir.toPath(),
            options = FilesystemOutputOptions(
                overwriteFiles = true,
                overwriteDirectories = true,
            ),
        )
        performRestoreWithOutput(repository, snapshot, output, progress, options)
    }

    private suspend fun performRestoreWithOutput(
        repository: DirectRepository,
        snapshot: SnapshotManifest,
        output: FilesystemOutput,
        progress: CountingRestoreProgress = CountingRestoreProgress(),
        options: RestoreOptions = RestoreOptions(),
    ) {
        val repoRoot = snapshotRoot(repository, snapshot)

        val restorer = SnapshotRestorer(
            output = output,
            options = options,
            progress = progress,
        )

        restorer.restore(repoRoot)
    }
}

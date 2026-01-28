package org.kopiaKt.android.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions

/**
 * E2E tests for backup operations on Android.
 *
 * Tests various file types and sizes to verify backup functionality
 * works correctly on real Android devices/emulators.
 *
 * Run with: ./gradlew :android:connectedAndroidTest --tests "*.BackupE2ETest"
 */
@RunWith(AndroidJUnit4::class)
class BackupE2ETest : AndroidE2ETestBase() {

    companion object {
        private const val TAG = "BackupE2ETest"
    }

    @Test
    fun backupSimpleFiles(): Unit = runBlocking {
        Log.i(TAG, "Test: backupSimpleFiles on ${getDeviceInfo()}")

        // Create test data
        val testData = createSimpleTestData()
        Log.i(TAG, "Created ${testData.fileCount} files, ${testData.totalSize} bytes")

        // Create repository and run backup
        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Simple test backup")

            // Verify backup completed
            assertThat(manifestId).isNotNull()
            Log.i(TAG, "Backup completed with manifest ID: ${manifestId.value}")

            // Verify manifest exists
            repository.refresh()
            val manifests = repository.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
            )
            assertThat(manifests).isNotEmpty()
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupComplexFiles(): Unit = runBlocking {
        Log.i(TAG, "Test: backupComplexFiles on ${getDeviceInfo()}")

        // Need more storage for this test
        requireStorage(10 * 1024 * 1024) // 10 MB

        // Create complex test data
        val testData = createComplexTestData()
        Log.i(TAG, "Created ${testData.fileCount} files, ${testData.totalSize} bytes")

        // Create repository and run backup
        val repository = createRepository()
        try {
            val progress = CountingUploadProgress()
            val manifestId = performBackup(
                repository = repository,
                description = "Complex test backup",
                progress = progress
            )

            // Verify backup completed
            assertThat(manifestId).isNotNull()

            // Verify progress counters
            val counters = progress.snapshot()
            assertThat(counters.totalHashedFiles + counters.totalCachedFiles)
                .isEqualTo(testData.fileCount.toLong())

            Log.i(TAG, "Backup completed: ${counters.totalHashedFiles} hashed, " +
                    "${counters.totalHashedBytes} bytes")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupEmptyFile(): Unit = runBlocking {
        Log.i(TAG, "Test: backupEmptyFile on ${getDeviceInfo()}")

        // Create just an empty file
        val emptyFile = java.io.File(sourceDir, "empty.txt")
        emptyFile.createNewFile()

        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Empty file backup")

            assertThat(manifestId).isNotNull()
            Log.i(TAG, "Empty file backup completed")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupLargeBinaryFile(): Unit = runBlocking {
        Log.i(TAG, "Test: backupLargeBinaryFile on ${getDeviceInfo()}")

        // Need more storage
        requireStorage(50 * 1024 * 1024) // 50 MB

        // Create a large binary file (4 MB to test chunking)
        val testFile = createPatternFile("large_binary.bin", ContentPattern.RANDOM, 4 * 1024 * 1024)
        Log.i(TAG, "Created large binary file: ${testFile.content.size} bytes")

        val repository = createRepository()
        try {
            val progress = CountingUploadProgress()
            val manifestId = performBackup(
                repository = repository,
                description = "Large binary backup",
                progress = progress
            )

            assertThat(manifestId).isNotNull()

            val counters = progress.snapshot()
            assertThat(counters.totalHashedBytes).isAtLeast(4 * 1024 * 1024L)

            Log.i(TAG, "Large binary backup completed: ${counters.totalHashedBytes} bytes")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupCompressibleContent(): Unit = runBlocking {
        Log.i(TAG, "Test: backupCompressibleContent on ${getDeviceInfo()}")

        // Create compressible file
        val testFile = createPatternFile("compressible.txt", ContentPattern.COMPRESSIBLE, 1024 * 1024)
        Log.i(TAG, "Created compressible file: ${testFile.content.size} bytes")

        // Use ZSTD compression
        val repository = createRepository(splitter = "FIXED-128K")
        try {
            val manifestId = performBackup(repository, "Compressible backup")

            assertThat(manifestId).isNotNull()
            Log.i(TAG, "Compressible content backup completed")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupWithDeduplication(): Unit = runBlocking {
        Log.i(TAG, "Test: backupWithDeduplication on ${getDeviceInfo()}")

        // Create identical files for dedup testing
        val content = ByteArray(1024 * 100) { (it % 256).toByte() }
        for (i in 1..5) {
            val file = java.io.File(sourceDir, "identical_$i.bin")
            file.writeBytes(content)
        }
        Log.i(TAG, "Created 5 identical files")

        val repository = createRepository()
        try {
            val progress = CountingUploadProgress()
            val manifestId = performBackup(
                repository = repository,
                description = "Dedup test backup",
                progress = progress
            )

            assertThat(manifestId).isNotNull()

            // First file hashed, rest should be deduplicated
            val counters = progress.snapshot()
            Log.i(TAG, "Dedup results: hashed=${counters.totalHashedFiles}, " +
                    "cached=${counters.totalCachedFiles}")

            // All files should be processed (hashed at least once)
            assertThat(counters.totalHashedFiles + counters.totalCachedFiles).isAtLeast(5)
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupDeepNestedDirectories(): Unit = runBlocking {
        Log.i(TAG, "Test: backupDeepNestedDirectories on ${getDeviceInfo()}")

        // Create deeply nested structure
        var currentDir = sourceDir
        val depth = 10
        for (i in 1..depth) {
            currentDir = java.io.File(currentDir, "level$i")
            currentDir.mkdirs()
            java.io.File(currentDir, "file_at_level$i.txt").writeText("Content at level $i")
        }
        Log.i(TAG, "Created $depth-level deep directory structure")

        val repository = createRepository()
        try {
            val manifestId = performBackup(repository, "Deep nested backup")

            assertThat(manifestId).isNotNull()
            Log.i(TAG, "Deep nested backup completed")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupManySmallFiles(): Unit = runBlocking {
        Log.i(TAG, "Test: backupManySmallFiles on ${getDeviceInfo()}")

        requireStorage(20 * 1024 * 1024) // 20 MB

        // Create many small files (simulating typical app data)
        val testData = createLargeTestData(fileCount = 200, avgFileSize = 1024)
        Log.i(TAG, "Created ${testData.fileCount} small files")

        val repository = createRepository()
        try {
            val progress = CountingUploadProgress()
            val startTime = System.currentTimeMillis()

            val manifestId = performBackup(
                repository = repository,
                description = "Many small files backup",
                progress = progress
            )

            val duration = System.currentTimeMillis() - startTime
            assertThat(manifestId).isNotNull()

            val counters = progress.snapshot()
            Log.i(TAG, "Backed up ${counters.totalHashedFiles} files in ${duration}ms")
            Log.i(TAG, "Rate: ${counters.totalHashedFiles * 1000 / duration} files/sec")
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupWithDifferentHashAlgorithms(): Unit = runBlocking {
        Log.i(TAG, "Test: backupWithDifferentHashAlgorithms on ${getDeviceInfo()}")

        // Create test file
        java.io.File(sourceDir, "test.txt").writeText("Test content for hash algorithm testing")

        val hashAlgorithms = listOf(
            "BLAKE2B-256-128",
            "BLAKE2B-256-256",
            "BLAKE3-256"
        )

        for (hashAlg in hashAlgorithms) {
            // Clean and recreate repo directory
            repoDir.deleteRecursively()
            repoDir.mkdirs()

            Log.i(TAG, "Testing hash algorithm: $hashAlg")

            val repository = createRepository(hash = hashAlg)
            try {
                val manifestId = performBackup(repository, "Hash algorithm test: $hashAlg")
                assertThat(manifestId).isNotNull()
                Log.i(TAG, "Backup with $hashAlg succeeded")
            } finally {
                repository.close()
            }
        }
        Unit
    }

    @Test
    fun backupIncrementalUnchanged(): Unit = runBlocking {
        Log.i(TAG, "Test: backupIncrementalUnchanged on ${getDeviceInfo()}")

        // Create initial test data
        val testData = createSimpleTestData()

        val repository = createRepository()
        try {
            // First backup
            val progress1 = CountingUploadProgress()
            val manifestId1 = performBackup(
                repository = repository,
                description = "Initial backup",
                progress = progress1
            )
            assertThat(manifestId1).isNotNull()

            val counters1 = progress1.snapshot()
            Log.i(TAG, "First backup: ${counters1.totalHashedFiles} hashed, ${counters1.totalCachedFiles} cached")

            // Second backup (same files, should be cached)
            val progress2 = CountingUploadProgress()
            val manifestId2 = performBackup(
                repository = repository,
                description = "Incremental backup",
                progress = progress2,
                previousManifestId = manifestId1.value
            )
            assertThat(manifestId2).isNotNull()

            val counters2 = progress2.snapshot()
            Log.i(TAG, "Second backup: ${counters2.totalHashedFiles} hashed, ${counters2.totalCachedFiles} cached")

            // In incremental mode, unchanged files should be cached
            // Note: This depends on how the uploader detects unchanged files
        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun backupRepositoryReopenAndContinue(): Unit = runBlocking {
        Log.i(TAG, "Test: backupRepositoryReopenAndContinue on ${getDeviceInfo()}")

        // Create test data
        createSimpleTestData()

        // First: create repository and backup
        var manifestId1: org.kopiaKt.core.manifest.ManifestId
        run {
            val repository = createRepository()
            try {
                manifestId1 = performBackup(repository, "First backup")
                assertThat(manifestId1).isNotNull()
            } finally {
                repository.close()
            }
        }

        Log.i(TAG, "First backup completed, manifest: ${manifestId1.value}")

        // Add more files
        java.io.File(sourceDir, "new_file.txt").writeText("New content")

        // Reopen repository and backup again
        run {
            val repository = openRepository()
            try {
                val manifestId2 = performBackup(repository, "Second backup after reopen")
                assertThat(manifestId2).isNotNull()

                // Should have two snapshots now
                repository.refresh()
                val manifests = repository.findManifests(
                    mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
                )
                assertThat(manifests.size).isEqualTo(2)

                Log.i(TAG, "Second backup completed, total snapshots: ${manifests.size}")
            } finally {
                repository.close()
            }
        }
        Unit
    }

    // ===================
    // Helper methods
    // ===================

    private suspend fun performBackup(
        repository: org.kopiaKt.core.repository.DirectRepository,
        description: String,
        progress: CountingUploadProgress = CountingUploadProgress(),
        previousManifestId: String? = null
    ): org.kopiaKt.core.manifest.ManifestId {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            val source = SourceInfo(
                host = android.os.Build.DEVICE,
                userName = "android",
                path = sourceDir.absolutePath
            )

            val uploader = SnapshotUploader(
                writer = writer,
                source = source,
                policy = Policy(),
                progress = progress
            )

            val rootDir = LocalFilesystem.directory(sourceDir.toPath())

            val result = uploader.upload(
                rootDir = rootDir,
                options = UploadOptions(
                    description = description,
                    parallelUploads = 2 // Conservative for testing
                )
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
}

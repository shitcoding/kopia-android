@file:OptIn(ExperimentalPathApi::class)

package org.kopiaKt.e2e.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.writeSession
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.restore.CountingRestoreProgress
import org.kopiaKt.snapshot.restore.FilesystemOutput
import org.kopiaKt.snapshot.restore.RestoreOptions
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Profiling-focused benchmark tests.
 *
 * These tests are designed to be run with a profiler attached (e.g., JProfiler, YourKit,
 * async-profiler, or VisualVM) to identify hot paths and optimization opportunities.
 *
 * Run with: ./gradlew :e2e:test --tests "*ProfilerBenchmarkTest*" -Pe2e
 *
 * For profiling, set JVM args:
 * -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints (for async-profiler)
 *
 * Hot paths identified (based on Kopia architecture):
 *
 * ## Backup Hot Paths:
 * 1. **Hashing** - BLAKE2B/BLAKE3 hashing of file content
 *    - Location: org.kopiaKt.core.crypto.Blake2bHasher, Blake3Hasher
 *    - Optimization: BouncyCastle implementation, consider native JNI
 *
 * 2. **Chunking/Splitting** - Buzhash/RabinKarp rolling hash for boundary detection
 *    - Location: org.kopiaKt.core.splitter.Buzhash32Splitter, RabinKarp64Splitter
 *    - Optimization: Pre-computed tables, branchless boundary detection
 *
 * 3. **Encryption** - AES-256-GCM encryption
 *    - Location: org.kopiaKt.core.crypto.Aes256GcmCipher
 *    - Optimization: Hardware AES-NI via javax.crypto
 *
 * 4. **Compression** - Zstd/LZ4/GZIP compression
 *    - Location: org.kopiaKt.core.compression.*
 *    - Optimization: Native libraries (zstd-jni, lz4-java)
 *
 * 5. **File I/O** - Reading source files
 *    - Location: org.kopiaKt.snapshot.fs.LocalFilesystem
 *    - Optimization: Buffer sizes, NIO direct buffers
 *
 * 6. **Pack Index Building** - Building pack indexes
 *    - Location: org.kopiaKt.core.content.PackIndexV1, PackIndexV2
 *    - Optimization: Pre-sized byte arrays, efficient varint encoding
 *
 * ## Restore Hot Paths:
 * 1. **Decryption** - AES-256-GCM decryption
 *    - Same as encryption
 *
 * 2. **Decompression** - Zstd/LZ4/GZIP decompression
 *    - Same as compression
 *
 * 3. **File I/O** - Writing restored files
 *    - Location: org.kopiaKt.snapshot.restore.FilesystemOutput
 *    - Optimization: Buffer sizes, atomic writes
 *
 * 4. **Index Lookup** - Binary search in pack indexes
 *    - Location: org.kopiaKt.core.content.PackIndexV1.getInfo
 *    - Optimization: Cache hot indexes in memory
 */
class ProfilerBenchmarkTest {

    private lateinit var testDir: Path
    private lateinit var repoDir: Path
    private lateinit var sourceDir: Path
    private lateinit var restoreDir: Path

    private val password = "profiler-test"
    private val testDataGenerator = BenchmarkTestData()

    @BeforeEach
    fun setup() {
        testDir = Files.createTempDirectory("kopiaKt-profiler-")
        repoDir = testDir.resolve("repo")
        sourceDir = testDir.resolve("source")
        restoreDir = testDir.resolve("restore")
        repoDir.createDirectories()
        sourceDir.createDirectories()
        restoreDir.createDirectories()
    }

    @AfterEach
    fun teardown() {
        if (testDir.exists()) {
            testDir.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        fun isE2EEnabled(): Boolean = System.getenv("RUN_E2E_TESTS")?.toBoolean() == true ||
            System.getenv("CI")?.toBoolean() == true ||
            System.getProperty("e2e")?.toBoolean() == true
    }

    @Nested
    inner class HashingProfiles {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile hashing - large continuous data`() {
            // This test generates a large amount of data to profile hashing
            val config = TestDataConfig(
                fileCount = 10,
                avgFileSize = 100 * 1024 * 1024, // 100 MB files
                directoryCount = 1,
                pattern = TestDataPattern.FEW_LARGE_FILES,
                contentType = ContentType.RANDOM,
            )

            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println("Profiling Hashing")
            println("=================")
            println("Data: ${formatBytes(testDataSpec.totalBytes)}")
            println()
            println("Attach profiler now and press Enter to start...")
            // In actual profiling, we'd wait for user input
            // System.`in`.read()

            val start = System.nanoTime()
            runBackup()
            val end = System.nanoTime()

            val duration = Duration.ofNanos(end - start)
            val throughput = testDataSpec.totalBytes / duration.toNanos().toDouble() * 1_000_000_000 / (1024 * 1024)

            println()
            println("Completed in ${formatDuration(duration)}")
            println("Throughput: ${String.format("%.2f", throughput)} MB/s")
            println()
            println("Look for hot methods in:")
            println("  - org.kopiaKt.core.crypto.Blake*")
            println("  - org.bouncycastle.crypto.digests.*")
        }
    }

    @Nested
    inner class SplittingProfiles {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile chunking - buzhash boundaries`() {
            // Large files to profile chunking algorithm
            val config = TestDataConfig(
                fileCount = 50,
                avgFileSize = 10 * 1024 * 1024, // 10 MB files
                directoryCount = 1,
                pattern = TestDataPattern.FEW_LARGE_FILES,
                contentType = ContentType.RANDOM,
            )

            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println("Profiling Chunking (Buzhash)")
            println("============================")
            println("Data: ${formatBytes(testDataSpec.totalBytes)}")

            val start = System.nanoTime()
            runBackup()
            val end = System.nanoTime()

            val duration = Duration.ofNanos(end - start)
            println("Completed in ${formatDuration(duration)}")
            println()
            println("Look for hot methods in:")
            println("  - org.kopiaKt.core.splitter.Buzhash32.*")
            println("  - org.kopiaKt.core.splitter.Buzhash32Splitter.*")
        }
    }

    @Nested
    inner class CompressionProfiles {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile compression - compressible content`() {
            // Highly compressible content to stress compression
            val config = TestDataConfig(
                fileCount = 100,
                avgFileSize = 1 * 1024 * 1024, // 1 MB files
                directoryCount = 5,
                pattern = TestDataPattern.COMPRESSIBLE,
                contentType = ContentType.COMPRESSIBLE,
            )

            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println("Profiling Compression")
            println("=====================")
            println("Data: ${formatBytes(testDataSpec.totalBytes)} (compressible)")

            val start = System.nanoTime()
            runBackup()
            val end = System.nanoTime()

            val duration = Duration.ofNanos(end - start)
            println("Completed in ${formatDuration(duration)}")
            println()
            println("Look for hot methods in:")
            println("  - org.kopiaKt.core.compression.ZstdCompressor.*")
            println("  - com.github.luben.zstd.*")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile compression - random content (incompressible)`() {
            // Random content - compression should be fast (no compression)
            val config = TestDataConfig(
                fileCount = 100,
                avgFileSize = 1 * 1024 * 1024, // 1 MB files
                directoryCount = 5,
                pattern = TestDataPattern.FEW_LARGE_FILES,
                contentType = ContentType.RANDOM,
            )

            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println("Profiling Compression (Random Data)")
            println("====================================")
            println("Data: ${formatBytes(testDataSpec.totalBytes)} (random/incompressible)")

            val start = System.nanoTime()
            runBackup()
            val end = System.nanoTime()

            val duration = Duration.ofNanos(end - start)
            println("Completed in ${formatDuration(duration)}")
        }
    }

    @Nested
    inner class EncryptionProfiles {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile encryption - AES-256-GCM`() {
            // Large data to profile encryption
            val config = TestDataConfig(
                fileCount = 50,
                avgFileSize = 10 * 1024 * 1024, // 10 MB files
                directoryCount = 1,
                pattern = TestDataPattern.FEW_LARGE_FILES,
                contentType = ContentType.RANDOM,
            )

            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println("Profiling Encryption")
            println("====================")
            println("Data: ${formatBytes(testDataSpec.totalBytes)}")

            val start = System.nanoTime()
            runBackup()
            val end = System.nanoTime()

            val duration = Duration.ofNanos(end - start)
            println("Completed in ${formatDuration(duration)}")
            println()
            println("Look for hot methods in:")
            println("  - org.kopiaKt.core.crypto.Aes256GcmCipher.*")
            println("  - javax.crypto.Cipher.*")
        }
    }

    @Nested
    inner class FileIOProfiles {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile file IO - many small files`() {
            // Many small files to stress file I/O
            val config = TestDataConfig(
                fileCount = 5000,
                avgFileSize = 10 * 1024, // 10 KB files
                directoryCount = 50,
                pattern = TestDataPattern.MANY_SMALL_FILES,
                contentType = ContentType.RANDOM,
            )

            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println("Profiling File I/O (Many Small Files)")
            println("======================================")
            println("Files: ${testDataSpec.fileCount}")
            println("Data: ${formatBytes(testDataSpec.totalBytes)}")

            val start = System.nanoTime()
            runBackup()
            val end = System.nanoTime()

            val duration = Duration.ofNanos(end - start)
            val filesPerSec = testDataSpec.fileCount / duration.toNanos().toDouble() * 1_000_000_000

            println("Completed in ${formatDuration(duration)}")
            println("Files/sec: ${String.format("%.0f", filesPerSec)}")
            println()
            println("Look for hot methods in:")
            println("  - org.kopiaKt.snapshot.fs.LocalFilesystem.*")
            println("  - java.nio.file.*")
        }
    }

    @Nested
    inner class RestoreProfiles {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile restore - full restore cycle`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // First create backup
            val manifest = runBackup()

            println("Profiling Restore")
            println("=================")
            println("Data: ${formatBytes(testDataSpec.totalBytes)}")

            val start = System.nanoTime()
            runRestore(manifest)
            val end = System.nanoTime()

            val duration = Duration.ofNanos(end - start)
            val throughput = testDataSpec.totalBytes / duration.toNanos().toDouble() * 1_000_000_000 / (1024 * 1024)

            println("Completed in ${formatDuration(duration)}")
            println("Throughput: ${String.format("%.2f", throughput)} MB/s")
            println()
            println("Look for hot methods in:")
            println("  - org.kopiaKt.snapshot.restore.SnapshotRestorer.*")
            println("  - org.kopiaKt.core.crypto.Aes256GcmCipher.decrypt()")
            println("  - org.kopiaKt.core.compression.*Compressor.decompress()")
        }
    }

    @Nested
    inner class FullCycleProfiles {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.ProfilerBenchmarkTest#isE2EEnabled")
        fun `profile full cycle - typical workload`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println("Profiling Full Cycle")
            println("====================")
            println("Data: ${formatBytes(testDataSpec.totalBytes)}")
            println()

            // Backup
            println("Phase 1: Backup")
            val backupStart = System.nanoTime()
            val manifest = runBackup()
            val backupEnd = System.nanoTime()
            val backupDuration = Duration.ofNanos(backupEnd - backupStart)
            println("  Duration: ${formatDuration(backupDuration)}")

            // Restore
            println("Phase 2: Restore")
            val restoreStart = System.nanoTime()
            runRestore(manifest)
            val restoreEnd = System.nanoTime()
            val restoreDuration = Duration.ofNanos(restoreEnd - restoreStart)
            println("  Duration: ${formatDuration(restoreDuration)}")

            println()
            println("Summary:")
            println("  Backup throughput: ${String.format("%.2f", testDataSpec.totalBytes / backupDuration.toNanos().toDouble() * 1_000_000_000 / (1024 * 1024))} MB/s")
            println("  Restore throughput: ${String.format("%.2f", testDataSpec.totalBytes / restoreDuration.toNanos().toDouble() * 1_000_000_000 / (1024 * 1024))} MB/s")
        }
    }

    // Helper methods

    private fun runBackup(): SnapshotManifest = runBlocking {
        val storage = FilesystemBlobStorage(repoDir)
        val repo = DirectRepositoryImpl.create(storage, password, createBenchmarkRepositoryConfig())

        try {
            writeSession(repo) { writer ->
                val source = SourceInfo("profiler-test", "profiler-test", sourceDir.toString())

                val uploader = SnapshotUploader(
                    writer = writer,
                    source = source,
                    policy = Policy(),
                    progress = CountingUploadProgress(),
                )

                uploader.upload(LocalFilesystem.directory(sourceDir), UploadOptions()).manifest
            }
        } finally {
            repo.close()
        }
    }

    private fun runRestore(manifest: SnapshotManifest) = runBlocking {
        val storage = FilesystemBlobStorage(repoDir)
        val repo = DirectRepositoryImpl.open(storage, password)

        try {
            val rootEntry = snapshotRoot(repo, manifest)

            val output = FilesystemOutput(restoreDir)
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(),
                progress = CountingRestoreProgress(),
            )

            restorer.restore(rootEntry)
        } finally {
            repo.close()
        }
    }

    private fun formatDuration(d: Duration): String {
        val millis = d.toMillis()
        return when {
            millis < 1000 -> "${millis}ms"
            millis < 60000 -> String.format("%.2fs", millis / 1000.0)
            else -> String.format("%.2fm", millis / 60000.0)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

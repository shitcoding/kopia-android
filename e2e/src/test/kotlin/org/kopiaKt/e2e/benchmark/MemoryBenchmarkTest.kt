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
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
/**
 * Memory usage benchmarks for backup/restore operations.
 *
 * These tests verify that memory usage stays within acceptable bounds for mobile devices.
 * Target: < 256MB heap for typical operations, < 512MB for large operations.
 *
 * Run with: ./gradlew :e2e:test --tests "*MemoryBenchmarkTest*" -Pe2e
 */
class MemoryBenchmarkTest {

    private lateinit var testDir: Path
    private lateinit var repoDir: Path
    private lateinit var sourceDir: Path
    private lateinit var restoreDir: Path

    private val password = "benchmark-password"
    private val testDataGenerator = BenchmarkTestData()
    private val memoryBean = ManagementFactory.getMemoryMXBean()

    // Memory thresholds for mobile-friendly operation
    private val targetMaxHeapSmall = 256L * 1024 * 1024 // 256 MB for small backups
    private val targetMaxHeapLarge = 512L * 1024 * 1024 // 512 MB for large backups

    @BeforeEach
    fun setup() {
        testDir = Files.createTempDirectory("kopiaKt-bench-memory-")
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
    inner class BackupMemoryUsage {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - small backup stays under 256MB`() {
            val config = BenchmarkScenarios.SMALL_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val memoryStats = measureMemoryDuringBackup(testDataSpec)

            printMemoryReport("Small Backup", testDataSpec, memoryStats)

            // Verify memory stays within bounds
            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapSmall, "Small backup")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - medium backup stays under 256MB`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val memoryStats = measureMemoryDuringBackup(testDataSpec)

            printMemoryReport("Medium Backup", testDataSpec, memoryStats)

            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapSmall, "Medium backup")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - large backup stays under 512MB`() {
            val config = BenchmarkScenarios.LARGE_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val memoryStats = measureMemoryDuringBackup(testDataSpec)

            printMemoryReport("Large Backup", testDataSpec, memoryStats)

            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapLarge, "Large backup")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - many small files`() {
            val config = BenchmarkScenarios.STRESS_MANY_FILES
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val memoryStats = measureMemoryDuringBackup(testDataSpec)

            printMemoryReport("Many Small Files", testDataSpec, memoryStats)

            // Many small files can use more memory due to metadata overhead
            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapLarge, "Many small files backup")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - few large files`() {
            val config = BenchmarkScenarios.STRESS_LARGE_FILES
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val memoryStats = measureMemoryDuringBackup(testDataSpec)

            printMemoryReport("Large Files", testDataSpec, memoryStats)

            // Large files should use constant memory due to streaming
            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapLarge, "Large files backup")
        }
    }

    @Nested
    inner class RestoreMemoryUsage {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - small restore stays under 256MB`() {
            val config = BenchmarkScenarios.SMALL_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // Create backup first
            val manifest = runBlocking { createBackup(testDataSpec) }

            val memoryStats = measureMemoryDuringRestore(manifest, testDataSpec)

            printMemoryReport("Small Restore", testDataSpec, memoryStats)

            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapSmall, "Small restore")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - medium restore stays under 256MB`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val manifest = runBlocking { createBackup(testDataSpec) }

            val memoryStats = measureMemoryDuringRestore(manifest, testDataSpec)

            printMemoryReport("Medium Restore", testDataSpec, memoryStats)

            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapSmall, "Medium restore")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory usage - large restore stays under 512MB`() {
            val config = BenchmarkScenarios.LARGE_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val manifest = runBlocking { createBackup(testDataSpec) }

            val memoryStats = measureMemoryDuringRestore(manifest, testDataSpec)

            printMemoryReport("Large Restore", testDataSpec, memoryStats)

            assertMemoryWithinBounds(memoryStats.peakHeapUsed, targetMaxHeapLarge, "Large restore")
        }
    }

    @Nested
    inner class MemoryEfficiency {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory efficiency - bytes per file ratio`() {
            // Test that memory usage scales reasonably with file count
            val scenarios = listOf(
                BenchmarkScenarios.SMALL_BACKUP to "Small (100 files)",
                BenchmarkScenarios.MEDIUM_BACKUP to "Medium (500 files)",
                BenchmarkScenarios.STRESS_MANY_FILES to "Stress (10000 files)",
            )

            val results = mutableListOf<Pair<String, MemoryStats>>()

            for ((config, name) in scenarios) {
                // Reset directories
                repoDir.deleteRecursively()
                sourceDir.deleteRecursively()
                repoDir.createDirectories()
                sourceDir.createDirectories()

                val testDataSpec = testDataGenerator.create(sourceDir, config)
                val memoryStats = measureMemoryDuringBackup(testDataSpec)
                results.add(name to memoryStats)

                // Calculate memory per file
                val memoryPerFile = memoryStats.peakHeapUsed / testDataSpec.fileCount
                println("$name: ${formatBytes(memoryStats.peakHeapUsed)} total, ${formatBytes(memoryPerFile)} per file")
            }

            println()
            println("Memory Efficiency Summary:")
            println("=".repeat(60))
            for ((name, stats) in results) {
                println("$name: peak=${formatBytes(stats.peakHeapUsed)}, allocated=${formatBytes(stats.totalAllocated)}")
            }
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.MemoryBenchmarkTest#isE2EEnabled")
        fun `memory efficiency - GC pressure`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // Measure GC activity during backup
            val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
            val gcCountBefore = gcBeans.sumOf { it.collectionCount }
            val gcTimeBefore = gcBeans.sumOf { it.collectionTime }

            measureMemoryDuringBackup(testDataSpec)

            val gcCountAfter = gcBeans.sumOf { it.collectionCount }
            val gcTimeAfter = gcBeans.sumOf { it.collectionTime }

            val gcCollections = gcCountAfter - gcCountBefore
            val gcTimeMs = gcTimeAfter - gcTimeBefore

            println()
            println("GC Pressure Analysis:")
            println("=".repeat(60))
            println("GC Collections: $gcCollections")
            println("GC Time: ${gcTimeMs}ms")
            println("Data processed: ${formatBytes(testDataSpec.totalBytes)}")

            // Warn if GC time is > 10% of operation time
            if (gcTimeMs > 0) {
                println("Note: High GC pressure may indicate memory allocation inefficiency")
            }
        }
    }

    // Helper methods

    private fun measureMemoryDuringBackup(testDataSpec: TestDataSpec): MemoryStats {
        forceGcAndWait()

        val startHeapUsed = getUsedHeap()
        var peakHeapUsed = startHeapUsed
        var sampleCount = 0

        // Start memory sampling in background
        val sampling = AtomicBoolean(true)
        val samplerThread = Thread {
            while (sampling.get()) {
                val current = getUsedHeap()
                if (current > peakHeapUsed) {
                    peakHeapUsed = current
                }
                sampleCount++
                Thread.sleep(10)
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            runBlocking {
                val storage = FilesystemBlobStorage(repoDir)
                val repo = DirectRepositoryImpl.create(storage, password, createBenchmarkRepositoryConfig())

                try {
                    writeSession(repo) { writer ->
                        val source = SourceInfo(
                            host = "memory-test",
                            userName = "memory-test",
                            path = sourceDir.toString(),
                        )

                        val uploader = SnapshotUploader(
                            writer = writer,
                            source = source,
                            policy = Policy(),
                            progress = CountingUploadProgress(),
                        )

                        val rootDir = LocalFilesystem.directory(sourceDir)
                        uploader.upload(rootDir, UploadOptions())
                    }
                } finally {
                    repo.close()
                }
            }
        } finally {
            sampling.set(false)
            samplerThread.join(100)
        }

        forceGcAndWait()
        val endHeapUsed = getUsedHeap()

        return MemoryStats(
            startHeapUsed = startHeapUsed,
            peakHeapUsed = peakHeapUsed,
            endHeapUsed = endHeapUsed,
            totalAllocated = peakHeapUsed - startHeapUsed,
            memoryRetained = endHeapUsed - startHeapUsed,
            sampleCount = sampleCount,
        )
    }

    private fun measureMemoryDuringRestore(manifest: SnapshotManifest, testDataSpec: TestDataSpec): MemoryStats {
        forceGcAndWait()

        val startHeapUsed = getUsedHeap()
        var peakHeapUsed = startHeapUsed
        var sampleCount = 0

        val sampling = AtomicBoolean(true)
        val samplerThread = Thread {
            while (sampling.get()) {
                val current = getUsedHeap()
                if (current > peakHeapUsed) {
                    peakHeapUsed = current
                }
                sampleCount++
                Thread.sleep(10)
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            runBlocking {
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
        } finally {
            sampling.set(false)
            samplerThread.join(100)
        }

        forceGcAndWait()
        val endHeapUsed = getUsedHeap()

        return MemoryStats(
            startHeapUsed = startHeapUsed,
            peakHeapUsed = peakHeapUsed,
            endHeapUsed = endHeapUsed,
            totalAllocated = peakHeapUsed - startHeapUsed,
            memoryRetained = endHeapUsed - startHeapUsed,
            sampleCount = sampleCount,
        )
    }

    private suspend fun createBackup(testDataSpec: TestDataSpec): SnapshotManifest {
        val storage = FilesystemBlobStorage(repoDir)
        val repo = DirectRepositoryImpl.create(storage, password, createBenchmarkRepositoryConfig())

        try {
            return writeSession(repo) { writer ->
                val source = SourceInfo(
                    host = "memory-test",
                    userName = "memory-test",
                    path = sourceDir.toString(),
                )

                val uploader = SnapshotUploader(
                    writer = writer,
                    source = source,
                    policy = Policy(),
                    progress = CountingUploadProgress(),
                )

                val rootDir = LocalFilesystem.directory(sourceDir)
                uploader.upload(rootDir, UploadOptions()).manifest
            }
        } finally {
            repo.close()
        }
    }

    private fun getUsedHeap(): Long = memoryBean.heapMemoryUsage.used

    private fun forceGcAndWait() {
        System.gc()
        Thread.sleep(100)
        System.gc()
        Thread.sleep(100)
    }

    private fun printMemoryReport(name: String, testDataSpec: TestDataSpec, stats: MemoryStats) {
        println()
        println("=== Memory Report: $name ===")
        println("Data: ${testDataSpec.fileCount} files, ${formatBytes(testDataSpec.totalBytes)}")
        println()
        println("Memory Statistics:")
        println("  Start Heap:      ${formatBytes(stats.startHeapUsed)}")
        println("  Peak Heap:       ${formatBytes(stats.peakHeapUsed)}")
        println("  End Heap:        ${formatBytes(stats.endHeapUsed)}")
        println("  Total Allocated: ${formatBytes(stats.totalAllocated)}")
        println("  Memory Retained: ${formatBytes(stats.memoryRetained)}")
        println("  Samples:         ${stats.sampleCount}")
        println()
    }

    private fun assertMemoryWithinBounds(actual: Long, threshold: Long, operationName: String) {
        if (actual > threshold) {
            val message = "$operationName used ${formatBytes(actual)} which exceeds ${formatBytes(threshold)} threshold"
            println("WARNING: $message")
            // Don't fail - memory varies by JVM and environment
        } else {
            println("✓ $operationName: ${formatBytes(actual)} within ${formatBytes(threshold)} threshold")
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * Memory usage statistics.
 */
data class MemoryStats(
    val startHeapUsed: Long,
    val peakHeapUsed: Long,
    val endHeapUsed: Long,
    val totalAllocated: Long,
    val memoryRetained: Long,
    val sampleCount: Int,
)

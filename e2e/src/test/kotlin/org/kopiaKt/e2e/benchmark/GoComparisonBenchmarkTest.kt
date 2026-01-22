@file:OptIn(ExperimentalPathApi::class)

package org.kopiaKt.e2e.benchmark

import kotlin.io.path.ExperimentalPathApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.writeSession
import org.kopiaKt.e2e.KopiaCliRunner
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
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Direct comparison benchmarks between Kotlin and Go implementations.
 *
 * These tests run the same operations on both implementations and compare
 * the results to verify the Kotlin implementation is within acceptable
 * performance bounds (target: within 50% of Go performance).
 *
 * Run with: ./gradlew :e2e:test --tests "*GoComparisonBenchmarkTest*" -Pe2e
 */
class GoComparisonBenchmarkTest {

    private lateinit var testDir: Path
    private lateinit var sourceDir: Path
    private lateinit var kotlinRepoDir: Path
    private lateinit var goRepoDir: Path
    private lateinit var kotlinRestoreDir: Path
    private lateinit var goRestoreDir: Path
    private lateinit var goConfigDir: Path

    private val password = "benchmark-password"
    private val testDataGenerator = BenchmarkTestData()

    private lateinit var kopiaCli: KopiaCliRunner

    @BeforeEach
    fun setup() {
        // Skip if Go Kopia not available
        Assumptions.assumeTrue(isGoKopiaAvailable(), "Go Kopia binary not available")

        testDir = Files.createTempDirectory("kopiaKt-go-compare-")
        sourceDir = testDir.resolve("source")
        kotlinRepoDir = testDir.resolve("kotlin-repo")
        goRepoDir = testDir.resolve("go-repo")
        kotlinRestoreDir = testDir.resolve("kotlin-restore")
        goRestoreDir = testDir.resolve("go-restore")
        goConfigDir = testDir.resolve("go-config")

        listOf(sourceDir, kotlinRepoDir, goRepoDir, kotlinRestoreDir, goRestoreDir, goConfigDir)
            .forEach { it.createDirectories() }

        kopiaCli = KopiaCliRunner(configDir = goConfigDir)
    }

    @AfterEach
    fun teardown() {
        if (::testDir.isInitialized && testDir.exists()) {
            testDir.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        fun isE2EEnabled(): Boolean {
            return System.getenv("RUN_E2E_TESTS")?.toBoolean() == true ||
                    System.getenv("CI")?.toBoolean() == true ||
                    System.getProperty("e2e")?.toBoolean() == true
        }

        @JvmStatic
        fun isGoKopiaAvailable(): Boolean {
            return try {
                KopiaCliRunner.defaultKopiaBinary()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    @Nested
    inner class BackupComparison {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.GoComparisonBenchmarkTest#isE2EEnabled")
        fun `compare backup performance - small (10MB)`() {
            runComparison(BenchmarkScenarios.SMALL_BACKUP, "Small Backup")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.GoComparisonBenchmarkTest#isE2EEnabled")
        fun `compare backup performance - medium (100MB)`() {
            runComparison(BenchmarkScenarios.MEDIUM_BACKUP, "Medium Backup")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.GoComparisonBenchmarkTest#isE2EEnabled")
        fun `compare backup performance - large (500MB)`() {
            runComparison(BenchmarkScenarios.LARGE_BACKUP, "Large Backup")
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.GoComparisonBenchmarkTest#isE2EEnabled")
        fun `compare backup performance - many small files`() {
            runComparison(BenchmarkScenarios.STRESS_MANY_FILES, "Many Small Files")
        }

        private fun runComparison(config: TestDataConfig, name: String) {
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println()
            println("=" .repeat(70))
            println("Comparison: $name")
            println("Data: ${testDataSpec.fileCount} files, ${formatBytes(testDataSpec.totalBytes)}")
            println("=" .repeat(70))

            // Warmup
            println("Warmup...")
            runKotlinBackup()
            kotlinRepoDir.deleteRecursively()
            kotlinRepoDir.createDirectories()

            runBlocking { kopiaCli.repositoryCreate(goRepoDir, password) }
            runBlocking { kopiaCli.snapshotCreate(sourceDir) }
            goRepoDir.deleteRecursively()
            goRepoDir.createDirectories()
            goConfigDir.deleteRecursively()
            goConfigDir.createDirectories()

            // Measure Go
            val goTimes = mutableListOf<Long>()
            repeat(3) {
                goConfigDir.deleteRecursively()
                goConfigDir.createDirectories()

                val start = System.nanoTime()
                runBlocking {
                    kopiaCli.repositoryCreate(goRepoDir, password)
                    kopiaCli.snapshotCreate(sourceDir)
                }
                val end = System.nanoTime()
                goTimes.add(end - start)

                goRepoDir.deleteRecursively()
                goRepoDir.createDirectories()

                println("  Go iteration ${it + 1}: ${formatDuration(Duration.ofNanos(end - start))}")
            }

            // Measure Kotlin
            val kotlinTimes = mutableListOf<Long>()
            repeat(3) {
                val start = System.nanoTime()
                runKotlinBackup()
                val end = System.nanoTime()
                kotlinTimes.add(end - start)

                kotlinRepoDir.deleteRecursively()
                kotlinRepoDir.createDirectories()

                println("  Kotlin iteration ${it + 1}: ${formatDuration(Duration.ofNanos(end - start))}")
            }

            // Calculate statistics
            val goAvgNanos = goTimes.average()
            val kotlinAvgNanos = kotlinTimes.average()
            val ratio = kotlinAvgNanos / goAvgNanos

            val goThroughput = testDataSpec.totalBytes / goAvgNanos * 1_000_000_000 / (1024 * 1024)
            val kotlinThroughput = testDataSpec.totalBytes / kotlinAvgNanos * 1_000_000_000 / (1024 * 1024)

            println()
            println("Results:")
            println("  Go avg:     ${formatDuration(Duration.ofNanos(goAvgNanos.toLong()))} (${String.format("%.2f", goThroughput)} MB/s)")
            println("  Kotlin avg: ${formatDuration(Duration.ofNanos(kotlinAvgNanos.toLong()))} (${String.format("%.2f", kotlinThroughput)} MB/s)")
            println("  Ratio:      ${String.format("%.2f", ratio)}x")
            println()

            when {
                ratio <= 1.0 -> println("✓ Kotlin is ${String.format("%.0f", (1 - ratio) * 100)}% FASTER than Go!")
                ratio <= 1.5 -> println("✓ Kotlin is within 50% of Go performance")
                else -> println("⚠ Kotlin is ${String.format("%.0f", (ratio - 1) * 100)}% SLOWER than Go")
            }
        }

        private fun runKotlinBackup() = runBlocking {
            val storage = FilesystemBlobStorage(kotlinRepoDir)
            val repo = DirectRepositoryImpl.create(storage, password, createBenchmarkRepositoryConfig())

            try {
                writeSession(repo) { writer ->
                    val source = SourceInfo(
                        host = "compare-test",
                        userName = "compare-test",
                        path = sourceDir.toString()
                    )

                    val uploader = SnapshotUploader(
                        writer = writer,
                        source = source,
                        policy = Policy(),
                        progress = CountingUploadProgress()
                    )

                    val rootDir = LocalFilesystem.directory(sourceDir)
                    uploader.upload(rootDir, UploadOptions())
                }
            } finally {
                repo.close()
            }
        }
    }

    @Nested
    inner class RestoreComparison {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.GoComparisonBenchmarkTest#isE2EEnabled")
        fun `compare restore performance - medium (100MB)`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println()
            println("=" .repeat(70))
            println("Comparison: Medium Restore")
            println("Data: ${testDataSpec.fileCount} files, ${formatBytes(testDataSpec.totalBytes)}")
            println("=" .repeat(70))

            // Create backups with both implementations
            println("Creating backups...")

            val kotlinManifest = runBlocking {
                val storage = FilesystemBlobStorage(kotlinRepoDir)
                val repo = DirectRepositoryImpl.create(storage, password, createBenchmarkRepositoryConfig())

                try {
                    writeSession(repo) { writer ->
                        val source = SourceInfo("test", "test", sourceDir.toString())
                        val uploader = SnapshotUploader(writer, source, Policy(), CountingUploadProgress())
                        uploader.upload(LocalFilesystem.directory(sourceDir), UploadOptions()).manifest
                    }
                } finally {
                    repo.close()
                }
            }

            runBlocking {
                kopiaCli.repositoryCreate(goRepoDir, password)
            }
            val goSnapshot = runBlocking {
                kopiaCli.snapshotCreate(sourceDir)
            }
            val goSnapshotId = goSnapshot.rootEntry?.obj
                ?: throw IllegalStateException("Go snapshot has no root object")

            // Warmup restores
            println("Warmup...")
            runKotlinRestore(kotlinManifest)
            kotlinRestoreDir.deleteRecursively()
            kotlinRestoreDir.createDirectories()

            runBlocking {
                kopiaCli.snapshotRestore(goSnapshotId, goRestoreDir)
            }
            goRestoreDir.deleteRecursively()
            goRestoreDir.createDirectories()

            // Measure Go restore
            val goTimes = mutableListOf<Long>()
            repeat(3) {
                val start = System.nanoTime()
                runBlocking {
                    kopiaCli.snapshotRestore(goSnapshotId, goRestoreDir)
                }
                val end = System.nanoTime()
                goTimes.add(end - start)

                goRestoreDir.deleteRecursively()
                goRestoreDir.createDirectories()

                println("  Go iteration ${it + 1}: ${formatDuration(Duration.ofNanos(end - start))}")
            }

            // Measure Kotlin restore
            val kotlinTimes = mutableListOf<Long>()
            repeat(3) {
                val start = System.nanoTime()
                runKotlinRestore(kotlinManifest)
                val end = System.nanoTime()
                kotlinTimes.add(end - start)

                kotlinRestoreDir.deleteRecursively()
                kotlinRestoreDir.createDirectories()

                println("  Kotlin iteration ${it + 1}: ${formatDuration(Duration.ofNanos(end - start))}")
            }

            // Calculate statistics
            val goAvgNanos = goTimes.average()
            val kotlinAvgNanos = kotlinTimes.average()
            val ratio = kotlinAvgNanos / goAvgNanos

            val goThroughput = testDataSpec.totalBytes / goAvgNanos * 1_000_000_000 / (1024 * 1024)
            val kotlinThroughput = testDataSpec.totalBytes / kotlinAvgNanos * 1_000_000_000 / (1024 * 1024)

            println()
            println("Results:")
            println("  Go avg:     ${formatDuration(Duration.ofNanos(goAvgNanos.toLong()))} (${String.format("%.2f", goThroughput)} MB/s)")
            println("  Kotlin avg: ${formatDuration(Duration.ofNanos(kotlinAvgNanos.toLong()))} (${String.format("%.2f", kotlinThroughput)} MB/s)")
            println("  Ratio:      ${String.format("%.2f", ratio)}x")
            println()

            when {
                ratio <= 1.0 -> println("✓ Kotlin is ${String.format("%.0f", (1 - ratio) * 100)}% FASTER than Go!")
                ratio <= 1.5 -> println("✓ Kotlin is within 50% of Go performance")
                else -> println("⚠ Kotlin is ${String.format("%.0f", (ratio - 1) * 100)}% SLOWER than Go")
            }
        }

        private fun runKotlinRestore(manifest: SnapshotManifest) = runBlocking {
            val storage = FilesystemBlobStorage(kotlinRepoDir)
            val repo = DirectRepositoryImpl.open(storage, password)

            try {
                val rootEntry = snapshotRoot(repo, manifest)

                val output = FilesystemOutput(kotlinRestoreDir)
                val restorer = SnapshotRestorer(
                    output = output,
                    options = RestoreOptions(),
                    progress = CountingRestoreProgress()
                )

                restorer.restore(rootEntry)
            } finally {
                repo.close()
            }
        }
    }

    @Nested
    inner class AlgorithmComparison {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.GoComparisonBenchmarkTest#isE2EEnabled")
        fun `compare hash algorithm performance`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            println()
            println("=" .repeat(70))
            println("Hash Algorithm Comparison")
            println("Data: ${testDataSpec.fileCount} files, ${formatBytes(testDataSpec.totalBytes)}")
            println("=" .repeat(70))

            val algorithms = listOf(
                "BLAKE2B-256-128" to "BLAKE2B-256-128",
                "BLAKE3-256" to "BLAKE3-256",
                "BLAKE3-256-128" to "BLAKE3-256-128"
            )

            for ((algoName, algo) in algorithms) {
                println()
                println("Algorithm: $algoName")

                // Reset repos
                kotlinRepoDir.deleteRecursively()
                kotlinRepoDir.createDirectories()
                goRepoDir.deleteRecursively()
                goRepoDir.createDirectories()
                goConfigDir.deleteRecursively()
                goConfigDir.createDirectories()

                // Go timing
                val goStart = System.nanoTime()
                runBlocking {
                    kopiaCli.repositoryCreate(goRepoDir, password, blockHashAlgorithm = algo)
                    kopiaCli.snapshotCreate(sourceDir)
                }
                val goEnd = System.nanoTime()
                val goTime = Duration.ofNanos(goEnd - goStart)

                // Kotlin timing
                val kotlinStart = System.nanoTime()
                runBlocking {
                    val storage = FilesystemBlobStorage(kotlinRepoDir)
                    val repo = DirectRepositoryImpl.create(
                        storage,
                        password,
                        createBenchmarkRepositoryConfig(hash = algo)
                    )

                    try {
                        writeSession(repo) { writer ->
                            val source = SourceInfo("test", "test", sourceDir.toString())
                            val uploader = SnapshotUploader(writer, source, Policy(), CountingUploadProgress())
                            uploader.upload(LocalFilesystem.directory(sourceDir), UploadOptions())
                        }
                    } finally {
                        repo.close()
                    }
                }
                val kotlinEnd = System.nanoTime()
                val kotlinTime = Duration.ofNanos(kotlinEnd - kotlinStart)

                val ratio = kotlinTime.toNanos().toDouble() / goTime.toNanos()

                println("  Go:     ${formatDuration(goTime)}")
                println("  Kotlin: ${formatDuration(kotlinTime)}")
                println("  Ratio:  ${String.format("%.2f", ratio)}x")
            }
        }
    }

    // Helper methods

    private fun formatDuration(d: Duration): String {
        val millis = d.toMillis()
        return when {
            millis < 1000 -> "${millis}ms"
            millis < 60000 -> String.format("%.2fs", millis / 1000.0)
            else -> String.format("%.2fm", millis / 60000.0)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

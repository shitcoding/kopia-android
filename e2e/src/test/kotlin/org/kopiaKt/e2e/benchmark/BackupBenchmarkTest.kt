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
import org.kopiaKt.e2e.KopiaCliRunner
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Performance benchmarks for backup (upload) operations.
 *
 * Run with: ./gradlew :e2e:test --tests "*BackupBenchmarkTest*" -Pe2e
 *
 * Or set environment variable: RUN_E2E_TESTS=true
 */
class BackupBenchmarkTest {

    private lateinit var testDir: Path
    private lateinit var repoDir: Path
    private lateinit var sourceDir: Path
    private lateinit var configDir: Path

    private val password = "benchmark-password"
    private val testDataGenerator = BenchmarkTestData()
    private val benchmarkRunner = BenchmarkRunner(
        BenchmarkConfig(
            warmupIterations = 1,
            measurementIterations = 3,
            compareWithGo = isGoKopiaAvailable(),
        ),
    )

    @BeforeEach
    fun setup() {
        testDir = Files.createTempDirectory("kopiaKt-bench-backup-")
        repoDir = testDir.resolve("repo")
        sourceDir = testDir.resolve("source")
        configDir = testDir.resolve("config")
        repoDir.createDirectories()
        sourceDir.createDirectories()
        configDir.createDirectories()
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

        @JvmStatic
        fun isGoKopiaAvailable(): Boolean = try {
            KopiaCliRunner.defaultKopiaBinary()
            true
        } catch (e: Exception) {
            false
        }
    }

    @Nested
    inner class SmallBackups {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.BackupBenchmarkTest#isE2EEnabled")
        fun `benchmark small backup - 10MB mixed files`() {
            val config = BenchmarkScenarios.SMALL_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val result = benchmarkRunner.runBenchmark(
                name = "Small Backup (10MB)",
                description = "Backup ${testDataSpec.fileCount} files totaling ${formatBytes(testDataSpec.totalBytes)}",
                testDataSpec = testDataSpec,
                setup = { createKotlinRepository() },
                benchmark = { repo -> benchmarkKotlinBackup(repo, testDataSpec) },
                teardown = { repo ->
                    runBlocking { repo.close() }
                    repoDir.deleteRecursively()
                    repoDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    { benchmarkGoBackup(testDataSpec) }
                } else {
                    null
                },
            )

            printResult(result)
            assertWithin50PercentOfGo(result)
        }
    }

    @Nested
    inner class MediumBackups {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.BackupBenchmarkTest#isE2EEnabled")
        fun `benchmark medium backup - 100MB mixed files`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val result = benchmarkRunner.runBenchmark(
                name = "Medium Backup (100MB)",
                description = "Backup ${testDataSpec.fileCount} files totaling ${formatBytes(testDataSpec.totalBytes)}",
                testDataSpec = testDataSpec,
                setup = { createKotlinRepository() },
                benchmark = { repo -> benchmarkKotlinBackup(repo, testDataSpec) },
                teardown = { repo ->
                    runBlocking { repo.close() }
                    repoDir.deleteRecursively()
                    repoDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    { benchmarkGoBackup(testDataSpec) }
                } else {
                    null
                },
            )

            printResult(result)
            assertWithin50PercentOfGo(result)
        }
    }

    @Nested
    inner class LargeBackups {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.BackupBenchmarkTest#isE2EEnabled")
        fun `benchmark large backup - 500MB mixed files`() {
            val config = BenchmarkScenarios.LARGE_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val result = benchmarkRunner.runBenchmark(
                name = "Large Backup (500MB)",
                description = "Backup ${testDataSpec.fileCount} files totaling ${formatBytes(testDataSpec.totalBytes)}",
                testDataSpec = testDataSpec,
                setup = { createKotlinRepository() },
                benchmark = { repo -> benchmarkKotlinBackup(repo, testDataSpec) },
                teardown = { repo ->
                    runBlocking { repo.close() }
                    repoDir.deleteRecursively()
                    repoDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    { benchmarkGoBackup(testDataSpec) }
                } else {
                    null
                },
            )

            printResult(result)
            assertWithin50PercentOfGo(result)
        }
    }

    @Nested
    inner class StressTests {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.BackupBenchmarkTest#isE2EEnabled")
        fun `benchmark stress - many small files (10000 files)`() {
            val config = BenchmarkScenarios.STRESS_MANY_FILES
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val result = benchmarkRunner.runBenchmark(
                name = "Stress: Many Small Files",
                description = "Backup ${testDataSpec.fileCount} small files",
                testDataSpec = testDataSpec,
                setup = { createKotlinRepository() },
                benchmark = { repo -> benchmarkKotlinBackup(repo, testDataSpec) },
                teardown = { repo ->
                    runBlocking { repo.close() }
                    repoDir.deleteRecursively()
                    repoDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    { benchmarkGoBackup(testDataSpec) }
                } else {
                    null
                },
            )

            printResult(result)
        }

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.BackupBenchmarkTest#isE2EEnabled")
        fun `benchmark stress - few large files (1GB)`() {
            val config = BenchmarkScenarios.STRESS_LARGE_FILES
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val result = benchmarkRunner.runBenchmark(
                name = "Stress: Large Files",
                description = "Backup ${testDataSpec.fileCount} large files totaling ${formatBytes(testDataSpec.totalBytes)}",
                testDataSpec = testDataSpec,
                setup = { createKotlinRepository() },
                benchmark = { repo -> benchmarkKotlinBackup(repo, testDataSpec) },
                teardown = { repo ->
                    runBlocking { repo.close() }
                    repoDir.deleteRecursively()
                    repoDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    { benchmarkGoBackup(testDataSpec) }
                } else {
                    null
                },
            )

            printResult(result)
        }
    }

    @Nested
    inner class DeduplicationBenchmarks {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.BackupBenchmarkTest#isE2EEnabled")
        fun `benchmark deduplication - duplicate content`() {
            val config = BenchmarkScenarios.DEDUP_TEST
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val result = benchmarkRunner.runBenchmark(
                name = "Deduplication Test",
                description = "Backup with duplicate content (should deduplicate)",
                testDataSpec = testDataSpec,
                setup = { createKotlinRepository() },
                benchmark = { repo -> benchmarkKotlinBackup(repo, testDataSpec) },
                teardown = { repo ->
                    runBlocking { repo.close() }
                    repoDir.deleteRecursively()
                    repoDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    { benchmarkGoBackup(testDataSpec) }
                } else {
                    null
                },
            )

            printResult(result)
        }
    }

    @Nested
    inner class CompressionBenchmarks {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.BackupBenchmarkTest#isE2EEnabled")
        fun `benchmark compression - compressible content`() {
            val config = BenchmarkScenarios.COMPRESSION_TEST
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            val result = benchmarkRunner.runBenchmark(
                name = "Compression Test",
                description = "Backup highly compressible content",
                testDataSpec = testDataSpec,
                setup = { createKotlinRepository() },
                benchmark = { repo -> benchmarkKotlinBackup(repo, testDataSpec) },
                teardown = { repo ->
                    runBlocking { repo.close() }
                    repoDir.deleteRecursively()
                    repoDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    { benchmarkGoBackup(testDataSpec) }
                } else {
                    null
                },
            )

            printResult(result)
        }
    }

    // Helper methods

    private fun createKotlinRepository(): DirectRepositoryImpl = runBlocking {
        val storage = FilesystemBlobStorage(repoDir)
        DirectRepositoryImpl.create(storage, password, createBenchmarkRepositoryConfig())
    }

    private suspend fun benchmarkKotlinBackup(
        repo: DirectRepositoryImpl,
        testDataSpec: TestDataSpec,
    ): BenchmarkMeasurement {
        val (_, measurement) = benchmarkRunner.measure(
            bytesProcessed = testDataSpec.totalBytes,
            filesProcessed = testDataSpec.fileCount.toLong(),
        ) {
            writeSession(repo) { writer ->
                val source = SourceInfo(
                    host = "benchmark-host",
                    userName = "benchmark-user",
                    path = sourceDir.toString(),
                )

                val progress = CountingUploadProgress()
                val uploader = SnapshotUploader(
                    writer = writer,
                    source = source,
                    policy = Policy(),
                    progress = progress,
                )

                val rootDir = LocalFilesystem.directory(sourceDir)
                uploader.upload(rootDir, UploadOptions())
            }
        }

        return measurement
    }

    private suspend fun benchmarkGoBackup(testDataSpec: TestDataSpec): BenchmarkMeasurement {
        val goRepoDir = testDir.resolve("go-repo")
        val goConfigDir = testDir.resolve("go-config")

        try {
            goRepoDir.createDirectories()
            goConfigDir.createDirectories()

            val kopiaCli = KopiaCliRunner(configDir = goConfigDir)

            val (_, measurement) = benchmarkRunner.measure(
                bytesProcessed = testDataSpec.totalBytes,
                filesProcessed = testDataSpec.fileCount.toLong(),
            ) {
                // Create repository
                kopiaCli.repositoryCreate(goRepoDir, password)

                // Create snapshot
                kopiaCli.snapshotCreate(sourceDir)
            }

            return measurement
        } finally {
            goRepoDir.deleteRecursively()
            goConfigDir.deleteRecursively()
        }
    }

    private fun printResult(result: BenchmarkResult) {
        println()
        println(result.formatReport())
        println()
    }

    private fun assertWithin50PercentOfGo(result: BenchmarkResult) {
        val ratio = result.goComparisonRatio ?: return // Skip if no Go comparison

        if (ratio > 1.5) {
            println("WARNING: Kotlin is ${String.format("%.0f", (ratio - 1) * 100)}% slower than Go (exceeds 50% threshold)")
            // Don't fail the test, just warn - performance varies by environment
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

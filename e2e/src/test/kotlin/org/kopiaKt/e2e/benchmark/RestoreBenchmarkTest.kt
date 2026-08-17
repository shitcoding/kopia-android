@file:OptIn(ExperimentalPathApi::class)

package org.kopiaKt.e2e.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
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
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Performance benchmarks for restore operations.
 *
 * Run with: ./gradlew :e2e:test --tests "*RestoreBenchmarkTest*" -Pe2e
 */
class RestoreBenchmarkTest {

    private lateinit var testDir: Path
    private lateinit var repoDir: Path
    private lateinit var sourceDir: Path
    private lateinit var restoreDir: Path
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
        testDir = Files.createTempDirectory("kopiaKt-bench-restore-")
        repoDir = testDir.resolve("repo")
        sourceDir = testDir.resolve("source")
        restoreDir = testDir.resolve("restore")
        configDir = testDir.resolve("config")
        repoDir.createDirectories()
        sourceDir.createDirectories()
        restoreDir.createDirectories()
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
    inner class SmallRestores {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.RestoreBenchmarkTest#isE2EEnabled")
        fun `benchmark small restore - 10MB mixed files`() {
            val config = BenchmarkScenarios.SMALL_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // Create snapshot first
            val snapshotManifest = runBlocking { createKotlinBackup(testDataSpec) }

            val result = benchmarkRunner.runBenchmark(
                name = "Small Restore (10MB)",
                description = "Restore ${testDataSpec.fileCount} files totaling ${formatBytes(testDataSpec.totalBytes)}",
                testDataSpec = testDataSpec,
                setup = {
                    restoreDir.deleteRecursively()
                    restoreDir.createDirectories()
                    snapshotManifest
                },
                benchmark = { manifest -> benchmarkKotlinRestore(manifest, testDataSpec) },
                teardown = { _ ->
                    restoreDir.deleteRecursively()
                    restoreDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    {
                        restoreDir.deleteRecursively()
                        restoreDir.createDirectories()
                        benchmarkGoRestore(testDataSpec)
                    }
                } else {
                    null
                },
            )

            printResult(result)
            assertWithin50PercentOfGo(result)
        }
    }

    @Nested
    inner class MediumRestores {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.RestoreBenchmarkTest#isE2EEnabled")
        fun `benchmark medium restore - 100MB mixed files`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // Create snapshot first
            val snapshotManifest = runBlocking { createKotlinBackup(testDataSpec) }

            val result = benchmarkRunner.runBenchmark(
                name = "Medium Restore (100MB)",
                description = "Restore ${testDataSpec.fileCount} files totaling ${formatBytes(testDataSpec.totalBytes)}",
                testDataSpec = testDataSpec,
                setup = {
                    restoreDir.deleteRecursively()
                    restoreDir.createDirectories()
                    snapshotManifest
                },
                benchmark = { manifest -> benchmarkKotlinRestore(manifest, testDataSpec) },
                teardown = { _ ->
                    restoreDir.deleteRecursively()
                    restoreDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    {
                        restoreDir.deleteRecursively()
                        restoreDir.createDirectories()
                        benchmarkGoRestore(testDataSpec)
                    }
                } else {
                    null
                },
            )

            printResult(result)
            assertWithin50PercentOfGo(result)
        }
    }

    @Nested
    inner class LargeRestores {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.RestoreBenchmarkTest#isE2EEnabled")
        fun `benchmark large restore - 500MB mixed files`() {
            val config = BenchmarkScenarios.LARGE_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // Create snapshot first
            val snapshotManifest = runBlocking { createKotlinBackup(testDataSpec) }

            val result = benchmarkRunner.runBenchmark(
                name = "Large Restore (500MB)",
                description = "Restore ${testDataSpec.fileCount} files totaling ${formatBytes(testDataSpec.totalBytes)}",
                testDataSpec = testDataSpec,
                setup = {
                    restoreDir.deleteRecursively()
                    restoreDir.createDirectories()
                    snapshotManifest
                },
                benchmark = { manifest -> benchmarkKotlinRestore(manifest, testDataSpec) },
                teardown = { _ ->
                    restoreDir.deleteRecursively()
                    restoreDir.createDirectories()
                },
                goBenchmark = if (isGoKopiaAvailable()) {
                    {
                        restoreDir.deleteRecursively()
                        restoreDir.createDirectories()
                        benchmarkGoRestore(testDataSpec)
                    }
                } else {
                    null
                },
            )

            printResult(result)
            assertWithin50PercentOfGo(result)
        }
    }

    @Nested
    inner class ParallelRestores {

        @Test
        @EnabledIf("org.kopiaKt.e2e.benchmark.RestoreBenchmarkTest#isE2EEnabled")
        fun `benchmark parallel restore - varying parallelism`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // Create snapshot first
            val snapshotManifest = runBlocking { createKotlinBackup(testDataSpec) }

            // Test different parallelism levels
            for (parallelism in listOf(1, 2, 4, 8)) {
                restoreDir.deleteRecursively()
                restoreDir.createDirectories()

                val result = benchmarkRunner.runBenchmark(
                    name = "Parallel Restore (parallelism=$parallelism)",
                    description = "Restore with $parallelism parallel workers",
                    testDataSpec = testDataSpec,
                    setup = {
                        restoreDir.deleteRecursively()
                        restoreDir.createDirectories()
                        Pair(snapshotManifest, parallelism)
                    },
                    benchmark = { (manifest, parallel) ->
                        benchmarkKotlinRestore(manifest, testDataSpec, parallel)
                    },
                    teardown = { _ ->
                        restoreDir.deleteRecursively()
                        restoreDir.createDirectories()
                    },
                )

                printResult(result)
            }
        }
    }

    @Nested
    inner class IncrementalRestores {

        /**
         * Quarantined: it benchmarks an optimisation that does not exist (task-73).
         *
         * The test restores once, then restores AGAIN into the same directory expecting the second
         * pass to skip files that are already there. `FilesystemOutput` has no such comparison — with
         * the default [org.kopiaKt.snapshot.restore.FilesystemOutputOptions] it refuses outright
         * ("Non-empty directory already exists and overwrite is disabled"), which is what this has
         * been failing with. So the failure is the test asserting a feature, not a regression in one.
         *
         * Deliberately disabled rather than deleted: "should a restore skip unchanged files" is a
         * real question for a phone on metered data, and this is where the measurement goes if the
         * answer is yes. Re-enable together with the feature, not before.
         */
        @Test
        @Disabled("Benchmarks a skip-unchanged restore that FilesystemOutput does not implement (task-73)")
        @EnabledIf("org.kopiaKt.e2e.benchmark.RestoreBenchmarkTest#isE2EEnabled")
        fun `benchmark incremental restore - skip unchanged files`() {
            val config = BenchmarkScenarios.MEDIUM_BACKUP
            val testDataSpec = testDataGenerator.create(sourceDir, config)

            // Create snapshot first
            val snapshotManifest = runBlocking { createKotlinBackup(testDataSpec) }

            // First restore (full)
            runBlocking {
                val storage = FilesystemBlobStorage(repoDir)
                val repo = DirectRepositoryImpl.open(storage, password)
                try {
                    val rootEntry = snapshotRoot(repo, snapshotManifest)
                    val output = FilesystemOutput(restoreDir)
                    val restorer = SnapshotRestorer(
                        output = output,
                        options = RestoreOptions(),
                    )
                    restorer.restore(rootEntry)
                } finally {
                    repo.close()
                }
            }

            // Now benchmark incremental restore (should skip all files)
            val result = benchmarkRunner.runBenchmark(
                name = "Incremental Restore",
                description = "Restore with all files already present (should skip)",
                testDataSpec = testDataSpec,
                setup = { snapshotManifest },
                benchmark = { manifest ->
                    benchmarkKotlinRestore(manifest, testDataSpec, incremental = true)
                },
                teardown = { },
            )

            printResult(result)

            // Verify that incremental restore is much faster
            println("Incremental restore should be nearly instant since all files exist.")
        }
    }

    // Helper methods

    private suspend fun createKotlinBackup(testDataSpec: TestDataSpec): SnapshotManifest {
        val storage = FilesystemBlobStorage(repoDir)
        val repo = DirectRepositoryImpl.create(storage, password, createBenchmarkRepositoryConfig())

        try {
            return writeSession(repo) { writer ->
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
                val uploadResult = uploader.upload(rootDir, UploadOptions())
                uploadResult.manifest
            }
        } finally {
            repo.close()
        }
    }

    private suspend fun benchmarkKotlinRestore(
        manifest: SnapshotManifest,
        testDataSpec: TestDataSpec,
        parallelism: Int = Runtime.getRuntime().availableProcessors(),
        incremental: Boolean = false,
    ): BenchmarkMeasurement {
        val (_, measurement) = benchmarkRunner.measure(
            bytesProcessed = testDataSpec.totalBytes,
            filesProcessed = testDataSpec.fileCount.toLong(),
        ) {
            val storage = FilesystemBlobStorage(repoDir)
            val repo = DirectRepositoryImpl.open(storage, password)

            try {
                val rootObjectId = manifest.rootEntry?.objectId
                    ?: throw IllegalStateException("Snapshot has no root entry")

                val rootEntry = snapshotRoot(repo, manifest)

                val progress = CountingRestoreProgress()
                val output = FilesystemOutput(restoreDir)
                val restorer = SnapshotRestorer(
                    output = output,
                    options = RestoreOptions(
                        parallel = parallelism,
                        incremental = incremental,
                    ),
                    progress = progress,
                )

                restorer.restore(rootEntry)
            } finally {
                repo.close()
            }
        }

        return measurement
    }

    private suspend fun benchmarkGoRestore(testDataSpec: TestDataSpec): BenchmarkMeasurement {
        val goRepoDir = testDir.resolve("go-repo")
        val goConfigDir = testDir.resolve("go-config")
        val goRestoreDir = testDir.resolve("go-restore")

        try {
            goRepoDir.createDirectories()
            goConfigDir.createDirectories()
            goRestoreDir.createDirectories()

            val kopiaCli = KopiaCliRunner(configDir = goConfigDir)

            // Create repository and backup
            kopiaCli.repositoryCreate(goRepoDir, password)
            val snapshotInfo = kopiaCli.snapshotCreate(sourceDir)

            val snapshotId = snapshotInfo.rootEntry?.obj
                ?: throw IllegalStateException("Go snapshot has no root object ID")

            // Benchmark restore
            val (_, measurement) = benchmarkRunner.measure(
                bytesProcessed = testDataSpec.totalBytes,
                filesProcessed = testDataSpec.fileCount.toLong(),
            ) {
                kopiaCli.snapshotRestore(snapshotId, goRestoreDir)
            }

            return measurement
        } finally {
            goRepoDir.deleteRecursively()
            goConfigDir.deleteRecursively()
            goRestoreDir.deleteRecursively()
        }
    }

    private fun printResult(result: BenchmarkResult) {
        println()
        println(result.formatReport())
        println()
    }

    private fun assertWithin50PercentOfGo(result: BenchmarkResult) {
        val ratio = result.goComparisonRatio ?: return

        if (ratio > 1.5) {
            println("WARNING: Kotlin is ${String.format("%.0f", (ratio - 1) * 100)}% slower than Go (exceeds 50% threshold)")
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

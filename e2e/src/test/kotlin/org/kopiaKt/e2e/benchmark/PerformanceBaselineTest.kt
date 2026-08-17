package org.kopiaKt.e2e.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.ZstdCompressor
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.testutil.LargeDataGenerator
import org.kopiaKt.core.testutil.TestRepositoryFactory
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Performance baseline tests that establish minimum acceptable throughput
 * for core repository operations.
 *
 * These tests use generous time limits to avoid flaky failures on slower
 * hardware while still catching severe performance regressions.
 *
 * Run with: ./gradlew :e2e:test --tests "*PerformanceBaselineTest*" -Pe2e
 */
@Tag("benchmark")
class PerformanceBaselineTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
        repo = null
    }

    companion object {
        private const val ONE_MB = 1024 * 1024

        @JvmStatic
        fun isE2EEnabled(): Boolean = System.getenv("RUN_E2E_TESTS")?.toBoolean() == true ||
            System.getenv("CI")?.toBoolean() == true ||
            System.getProperty("e2e")?.toBoolean() == true
    }

    @Nested
    @DisplayName("Write/Read Throughput")
    inner class WriteReadThroughput {

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @DisplayName("Should write and read 100MB of data in under 30 seconds")
        fun `should write and read 100MB of data in under 30 seconds`(): Unit = runBlocking {
            assumeTrue(isE2EEnabled()) {
                "E2E benchmarks disabled — set RUN_E2E_TESTS=true, CI=true, or run with -Pe2e"
            }

            val chunkSize = ONE_MB
            val chunkCount = 100
            val totalBytes = chunkSize.toLong() * chunkCount

            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val objectIds = mutableListOf<ObjectId>()

            val totalTimeMs = measureTimeMillis {
                // Write phase: 100 x 1MB chunks
                val writer = repository.newDirectWriter()
                for (i in 0 until chunkCount) {
                    val data = LargeDataGenerator.generate(chunkSize, seed = i.toLong())
                    val objectId = writer.writeObject(data)
                    objectIds.add(objectId)
                }
                writer.flush()
                writer.close()

                repository.refresh()

                // Read phase: read all chunks back
                for (objectId in objectIds) {
                    repository.readObject(objectId)
                }
            }

            val throughputMBps = totalBytes.toDouble() / ONE_MB / (totalTimeMs / 1000.0)

            println()
            println("=== Write/Read 100MB Baseline ===")
            println("  Total time:  ${totalTimeMs}ms")
            println("  Data size:   $chunkCount x ${chunkSize / ONE_MB}MB = ${totalBytes / ONE_MB}MB")
            println("  Throughput:  ${"%.2f".format(throughputMBps)} MB/s (write+read combined)")
            println()

            assertTrue(
                totalTimeMs < 30_000,
                "Write+read of ${totalBytes / ONE_MB}MB took ${totalTimeMs}ms, " +
                    "exceeding the 30s baseline limit",
            )
        }
    }

    @Nested
    @DisplayName("Index Loading")
    inner class IndexLoading {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Should load 1000 index entries in under 5 seconds")
        fun `should load 1000 index entries in under 5 seconds`(): Unit = runBlocking {
            assumeTrue(isE2EEnabled()) {
                "E2E benchmarks disabled — set RUN_E2E_TESTS=true, CI=true, or run with -Pe2e"
            }

            val objectCount = 1000
            val objectSize = 512 // small objects to keep total size manageable

            // Phase 1: create repository and populate with 1000 objects
            val storage = InMemoryBlobStorage()
            val config = TestRepositoryFactory.createConfig()
            val password = "index-load-test"

            val creationRepo = DirectRepositoryImpl.create(storage, password, config)
            val writer = creationRepo.newDirectWriter()
            for (i in 0 until objectCount) {
                val data = LargeDataGenerator.generate(objectSize, seed = i.toLong())
                writer.writeObject(data)
            }
            writer.flush()
            writer.close()
            creationRepo.close()

            // Phase 2: reopen repository and measure index loading time
            val loadTimeMs = measureTimeMillis {
                val reopened = DirectRepositoryImpl.open(storage, password)
                repo = reopened
            }

            println()
            println("=== Index Loading Baseline ===")
            println("  Objects:    $objectCount (each ${objectSize}B)")
            println("  Load time:  ${loadTimeMs}ms")
            println()

            assertTrue(
                loadTimeMs < 5_000,
                "Loading index with $objectCount entries took ${loadTimeMs}ms, " +
                    "exceeding the 5s baseline limit",
            )
        }
    }

    @Nested
    @DisplayName("Compression Throughput")
    inner class CompressionThroughput {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("Should compress 10MB with zstd in under 2 seconds")
        fun `should compress 10MB with zstd in under 2 seconds`() {
            assumeTrue(isE2EEnabled()) {
                "E2E benchmarks disabled — set RUN_E2E_TESTS=true, CI=true, or run with -Pe2e"
            }

            val dataSize = 10 * ONE_MB
            val data = LargeDataGenerator.generate(dataSize, seed = 77L)
            val compressor = ZstdCompressor.default()

            var compressed: ByteArray? = null
            val compressTimeMs = measureTimeMillis {
                compressed = compressor.compress(data)
            }

            val compressedSize = compressed!!.size
            val ratio = dataSize.toDouble() / compressedSize
            val throughputMBps = dataSize.toDouble() / ONE_MB / (compressTimeMs / 1000.0)

            println()
            println("=== Zstd Compression Baseline ===")
            println("  Input size:       ${dataSize / ONE_MB}MB")
            println("  Compressed size:  ${"%.2f".format(compressedSize / (ONE_MB.toDouble()))}MB")
            println("  Ratio:            ${"%.2f".format(ratio)}x")
            println("  Compress time:    ${compressTimeMs}ms")
            println("  Throughput:       ${"%.2f".format(throughputMBps)} MB/s")
            println()

            assertTrue(
                compressTimeMs < 2_000,
                "Compressing ${dataSize / ONE_MB}MB with zstd took ${compressTimeMs}ms, " +
                    "exceeding the 2s baseline limit",
            )
        }
    }
}

package org.kopiaKt.e2e.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * How much the upload pipeline actually gains from running several files at once (task-66).
 *
 * Measured on a Nothing Phone (2): **Go kopia does 1.4 GB in 4 s on that phone; this app takes 90 s**
 * — about 22× slower, on identical hardware, identical data and identical repository parameters.
 * Storage was ruled out (the device writes at 462 MB/s) and so was compression (32.1 s with zstd vs
 * 31.9 s without, and Go is 4 s either way).
 *
 * What is left is the pipeline, and the shape of it is visible in the code:
 * `ContentManager.writeContent` holds ONE per-repository mutex across hashing, the dedup lookup,
 * compression, encryption, pack assembly and — when a pack fills — the ~20 MB `toByteArray()` copy
 * and the `storage.putBlob` that writes it. (NOT encrypting the whole pack: contents are encrypted
 * per-content, and `buildEncrypted` encrypts only the small local index. An earlier draft of this
 * comment said otherwise — in this file a wrong architecture note is how the next session fixes the
 * wrong thing.) `TreeWalker`'s `Semaphore(parallelUploads)` therefore buys parallel file
 * *reads and splitting* only. (The Android path runs 4 permits, not 8:
 * `BackupWorkerConfig.parallelUploads` is `availableProcessors().coerceIn(1, 4)`.)
 *
 * This measures that directly, with in-memory storage so no disk I/O is in the picture: the same
 * bytes written through one writer by 1 coroutine and by 4. If the lock dominates, the two are the
 * same wall time and the concurrency is decorative.
 *
 * Deliberately **prints** rather than asserting a throughput number — a hard threshold on shared CI
 * hardware is a flake generator. The only assertion is the one that cannot be hardware-dependent:
 * that the work actually happened. Read the printed ratio.
 *
 * Run with: `./gradlew :e2e:test --tests "*ContentWriteConcurrencyBenchmarkTest*" -Pe2e`
 */
@Tag("benchmark")
@DisplayName("Content write concurrency (task-66)")
class ContentWriteConcurrencyBenchmarkTest {

    private companion object {
        const val ONE_MB = 1024 * 1024
        const val OBJECTS = 48

        fun isE2EEnabled(): Boolean = System.getenv("RUN_E2E_TESTS")?.toBoolean() == true ||
            System.getenv("CI")?.toBoolean() == true ||
            System.getProperty("e2e")?.toBoolean() == true
    }

    /** Incompressible, like the photos and video a phone backup is mostly made of. */
    private fun payloads(): List<ByteArray> {
        val random = SecureRandom()
        return List(OBJECTS) { ByteArray(ONE_MB).also { random.nextBytes(it) } }
    }

    private fun writeAll(parallelism: Int, payloads: List<ByteArray>): Long {
        val (repo, _) = runBlocking { TestRepositoryFactory.createInMemory() }
        return try {
            measureTimeMillis {
                runBlocking(Dispatchers.Default) {
                    val writer = repo.newDirectWriter(WriteSessionOptions(purpose = "benchmark"))
                    payloads.chunked((payloads.size + parallelism - 1) / parallelism)
                        .map { chunk ->
                            async { chunk.forEach { withContext(Dispatchers.Default) { writer.writeObject(it) } } }
                        }
                        .awaitAll()
                    writer.flush()
                }
            }
        } finally {
            repo.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `writing the same bytes with four workers instead of one`() {
        assumeTrue(isE2EEnabled(), "Set RUN_E2E_TESTS=true or -Pe2e to run benchmarks")

        // Same bytes both times, so the two runs differ only in how many coroutines carry them.
        val payloads = payloads()
        val megabytes = OBJECTS.toDouble()

        val serial = writeAll(parallelism = 1, payloads = payloads)
        val parallel = writeAll(parallelism = 4, payloads = payloads)

        val serialRate = megabytes / (serial / 1000.0)
        val parallelRate = megabytes / (parallel / 1000.0)

        println("[task-66] $OBJECTS MB through one writer, in-memory storage, incompressible data")
        println("[task-66]   1 worker : $serial ms  (%.1f MB/s)".format(serialRate))
        println("[task-66]   4 workers: $parallel ms  (%.1f MB/s)".format(parallelRate))
        val speedUp = "%.2fx".format(parallelRate / serialRate)
        println("[task-66]   speed-up : $speedUp  (4.0x perfect; ~1.0x means fully serialized)")

        // The only hardware-independent claim: both runs did the work. The number above is the
        // finding; a threshold here would just flake on a shared runner.
        assert(serial > 0 && parallel > 0)
    }
}

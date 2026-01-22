@file:OptIn(ExperimentalPathApi::class)

package org.kopiaKt.e2e.benchmark

import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Runs benchmarks with warmup, measurement iterations, and optional Go comparison.
 */
class BenchmarkRunner(
    private val config: BenchmarkConfig = BenchmarkConfig()
) {
    private val memoryBean = ManagementFactory.getMemoryMXBean()

    /**
     * Runs a benchmark with the configured number of iterations.
     *
     * @param name Benchmark name
     * @param description Benchmark description
     * @param testDataSpec Test data specification
     * @param setup Setup function called before each iteration (return value passed to benchmark)
     * @param benchmark The benchmark function to measure
     * @param teardown Cleanup function called after each iteration
     * @param goBenchmark Optional Go benchmark function for comparison
     */
    fun <T> runBenchmark(
        name: String,
        description: String,
        testDataSpec: TestDataSpec? = null,
        setup: () -> T,
        benchmark: suspend (T) -> BenchmarkMeasurement,
        teardown: (T) -> Unit = {},
        goBenchmark: (suspend () -> BenchmarkMeasurement)? = null
    ): BenchmarkResult {
        println("Running benchmark: $name")
        println("  Description: $description")

        val kotlinMeasurements = mutableListOf<BenchmarkMeasurement>()
        val goMeasurements = mutableListOf<BenchmarkMeasurement>()

        // Warmup iterations
        println("  Warmup iterations: ${config.warmupIterations}")
        repeat(config.warmupIterations) { i ->
            print("    Warmup ${i + 1}/${config.warmupIterations}...")
            val context = setup()
            try {
                runBlocking { benchmark(context) }
                println(" done")
            } finally {
                teardown(context)
                maybeGc()
            }
        }

        // Measurement iterations
        println("  Measurement iterations: ${config.measurementIterations}")
        repeat(config.measurementIterations) { i ->
            print("    Iteration ${i + 1}/${config.measurementIterations}...")

            val context = setup()
            try {
                val measurement = runBlocking { benchmark(context) }
                kotlinMeasurements.add(measurement)
                println(" ${formatDuration(measurement.duration)} (${formatThroughput(measurement)})")
            } finally {
                teardown(context)
                maybeGc()
            }
        }

        // Go comparison
        if (config.compareWithGo && goBenchmark != null) {
            println("  Go comparison iterations: ${config.measurementIterations}")

            // Warmup for Go
            repeat(config.warmupIterations) { i ->
                print("    Go warmup ${i + 1}/${config.warmupIterations}...")
                runBlocking { goBenchmark() }
                println(" done")
                maybeGc()
            }

            // Measurement for Go
            repeat(config.measurementIterations) { i ->
                print("    Go iteration ${i + 1}/${config.measurementIterations}...")
                val measurement = runBlocking { goBenchmark() }
                goMeasurements.add(measurement)
                println(" ${formatDuration(measurement.duration)} (${formatThroughput(measurement)})")
                maybeGc()
            }
        }

        return BenchmarkResult(
            name = name,
            description = description,
            measurements = kotlinMeasurements,
            goMeasurements = goMeasurements.takeIf { it.isNotEmpty() },
            testDataSpec = testDataSpec
        )
    }

    /**
     * Measures a single benchmark operation.
     *
     * @param bytesProcessed Total bytes processed
     * @param filesProcessed Total files processed
     * @param collectMemory Whether to collect memory statistics
     * @param operation The operation to measure
     */
    suspend fun <R> measure(
        bytesProcessed: Long,
        filesProcessed: Long,
        collectMemory: Boolean = config.collectMemoryStats,
        metadata: Map<String, String> = emptyMap(),
        operation: suspend () -> R
    ): Pair<R, BenchmarkMeasurement> {
        maybeGc()

        val startMemory = if (collectMemory) getUsedMemory() else null
        val peakMemoryTracker = if (collectMemory) PeakMemoryTracker() else null
        peakMemoryTracker?.start()

        val startTime = Instant.now()
        val result = operation()
        val endTime = Instant.now()

        peakMemoryTracker?.stop()
        val endMemory = if (collectMemory) getUsedMemory() else null

        val measurement = BenchmarkMeasurement(
            duration = Duration.between(startTime, endTime),
            bytesProcessed = bytesProcessed,
            filesProcessed = filesProcessed,
            peakMemoryBytes = peakMemoryTracker?.peakMemory,
            allocatedMemoryBytes = if (startMemory != null && endMemory != null) {
                (endMemory - startMemory).coerceAtLeast(0)
            } else null,
            metadata = metadata
        )

        return result to measurement
    }

    /**
     * Creates a temporary directory for benchmark data.
     */
    fun createTempDir(prefix: String): Path {
        val baseDir = config.benchmarkDir ?: Files.createTempDirectory("kopiaKt-bench-")
        val dir = baseDir.resolve("$prefix-${System.currentTimeMillis()}")
        dir.createDirectories()
        return dir
    }

    /**
     * Cleans up a temporary directory.
     */
    fun cleanupTempDir(dir: Path) {
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun maybeGc() {
        if (config.forceGcBetweenIterations) {
            System.gc()
            Thread.sleep(100) // Give GC time to run
        }
    }

    private fun getUsedMemory(): Long {
        return memoryBean.heapMemoryUsage.used
    }

    private fun formatDuration(d: Duration): String {
        val millis = d.toMillis()
        return when {
            millis < 1000 -> "${millis}ms"
            millis < 60000 -> String.format("%.2fs", millis / 1000.0)
            else -> String.format("%.2fm", millis / 60000.0)
        }
    }

    private fun formatThroughput(m: BenchmarkMeasurement): String {
        val mbPerSec = m.throughputBytesPerSec / (1024 * 1024)
        return String.format("%.2f MB/s", mbPerSec)
    }

    /**
     * Simple peak memory tracker that polls memory usage in a background thread.
     */
    private inner class PeakMemoryTracker {
        @Volatile
        var peakMemory: Long = 0L
            private set

        @Volatile
        private var running = false

        private var thread: Thread? = null

        fun start() {
            running = true
            peakMemory = getUsedMemory()

            thread = Thread {
                while (running) {
                    val current = getUsedMemory()
                    if (current > peakMemory) {
                        peakMemory = current
                    }
                    Thread.sleep(10) // Sample every 10ms
                }
            }.apply {
                isDaemon = true
                name = "PeakMemoryTracker"
                start()
            }
        }

        fun stop() {
            running = false
            thread?.join(100)
        }
    }
}

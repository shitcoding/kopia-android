package org.kopiaKt.e2e.benchmark

import org.kopiaKt.core.format.RepositoryConfig
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration

/**
 * Creates a default repository configuration for benchmarks.
 */
fun createBenchmarkRepositoryConfig(
    hash: String = "BLAKE2B-256-128",
    encryption: String = "AES256-GCM-HMAC-SHA256",
    splitter: String = "FIXED-1M",
): RepositoryConfig {
    val random = SecureRandom()
    val secret = ByteArray(32).also { random.nextBytes(it) }
    val masterKey = ByteArray(32).also { random.nextBytes(it) }

    return RepositoryConfig(
        hash = hash,
        encryption = encryption,
        secret = secret,
        masterKey = masterKey,
        splitter = splitter,
    )
}

/**
 * Configuration for benchmark runs.
 */
data class BenchmarkConfig(
    /**
     * Number of warmup iterations to discard.
     */
    val warmupIterations: Int = 1,

    /**
     * Number of measurement iterations.
     */
    val measurementIterations: Int = 3,

    /**
     * Directory for benchmark data (temporary files, etc.).
     */
    val benchmarkDir: Path? = null,

    /**
     * Whether to compare with Go implementation.
     */
    val compareWithGo: Boolean = true,

    /**
     * Timeout for each benchmark operation.
     */
    val timeout: Duration = Duration.ofMinutes(10),

    /**
     * Whether to collect memory statistics.
     */
    val collectMemoryStats: Boolean = true,

    /**
     * Whether to force garbage collection between iterations.
     */
    val forceGcBetweenIterations: Boolean = true,
)

/**
 * Result of a single benchmark measurement.
 */
data class BenchmarkMeasurement(
    /**
     * Duration of the operation.
     */
    val duration: Duration,

    /**
     * Bytes processed.
     */
    val bytesProcessed: Long,

    /**
     * Files processed.
     */
    val filesProcessed: Long,

    /**
     * Peak memory usage in bytes (if collected).
     */
    val peakMemoryBytes: Long? = null,

    /**
     * Allocated memory in bytes (if collected).
     */
    val allocatedMemoryBytes: Long? = null,

    /**
     * Additional metadata.
     */
    val metadata: Map<String, String> = emptyMap(),
) {
    /**
     * Throughput in bytes per second.
     */
    val throughputBytesPerSec: Double
        get() = if (duration.toNanos() > 0) {
            bytesProcessed.toDouble() / duration.toNanos() * 1_000_000_000
        } else {
            0.0
        }

    /**
     * Throughput in files per second.
     */
    val throughputFilesPerSec: Double
        get() = if (duration.toNanos() > 0) {
            filesProcessed.toDouble() / duration.toNanos() * 1_000_000_000
        } else {
            0.0
        }
}

/**
 * Aggregated benchmark results.
 */
data class BenchmarkResult(
    /**
     * Name of the benchmark.
     */
    val name: String,

    /**
     * Description of what was benchmarked.
     */
    val description: String,

    /**
     * Individual measurements.
     */
    val measurements: List<BenchmarkMeasurement>,

    /**
     * Go implementation measurements (if compared).
     */
    val goMeasurements: List<BenchmarkMeasurement>? = null,

    /**
     * Test data specification.
     */
    val testDataSpec: TestDataSpec? = null,
) {
    /**
     * Number of measurements.
     */
    val measurementCount: Int get() = measurements.size

    /**
     * Average duration across measurements.
     */
    val avgDuration: Duration
        get() = Duration.ofNanos(measurements.map { it.duration.toNanos() }.average().toLong())

    /**
     * Minimum duration.
     */
    val minDuration: Duration
        get() = measurements.minByOrNull { it.duration }?.duration ?: Duration.ZERO

    /**
     * Maximum duration.
     */
    val maxDuration: Duration
        get() = measurements.maxByOrNull { it.duration }?.duration ?: Duration.ZERO

    /**
     * Standard deviation of duration in nanoseconds.
     */
    val stdDevDurationNanos: Double
        get() {
            val avg = avgDuration.toNanos().toDouble()
            val variance = measurements.map { (it.duration.toNanos() - avg).let { d -> d * d } }.average()
            return kotlin.math.sqrt(variance)
        }

    /**
     * Average throughput in bytes per second.
     */
    val avgThroughputBytesPerSec: Double
        get() = measurements.map { it.throughputBytesPerSec }.average()

    /**
     * Average throughput in MB per second.
     */
    val avgThroughputMBPerSec: Double
        get() = avgThroughputBytesPerSec / (1024 * 1024)

    /**
     * Average peak memory usage.
     */
    val avgPeakMemoryBytes: Long?
        get() = measurements.mapNotNull { it.peakMemoryBytes }.takeIf { it.isNotEmpty() }?.average()?.toLong()

    /**
     * Comparison ratio with Go (Kotlin time / Go time).
     * Values > 1 mean Kotlin is slower.
     */
    val goComparisonRatio: Double?
        get() {
            val goAvg = goMeasurements?.map { it.duration.toNanos() }?.average() ?: return null
            val ktAvg = avgDuration.toNanos().toDouble()
            return if (goAvg > 0) ktAvg / goAvg else null
        }

    /**
     * Formats the result as a human-readable string.
     */
    fun formatReport(): String = buildString {
        appendLine("=== $name ===")
        appendLine(description)
        appendLine()

        testDataSpec?.let { spec ->
            appendLine("Test Data:")
            appendLine("  Files: ${spec.fileCount}")
            appendLine("  Total Size: ${formatBytes(spec.totalBytes)}")
            appendLine("  Avg File Size: ${formatBytes(spec.avgFileSize.toLong())}")
            appendLine()
        }

        appendLine("Kotlin Results ($measurementCount iterations):")
        appendLine("  Duration: ${formatDuration(avgDuration)} (min: ${formatDuration(minDuration)}, max: ${formatDuration(maxDuration)})")
        appendLine("  Throughput: ${String.format("%.2f", avgThroughputMBPerSec)} MB/s")

        avgPeakMemoryBytes?.let {
            appendLine("  Peak Memory: ${formatBytes(it)}")
        }

        goMeasurements?.let { goMs ->
            val goAvgDuration = Duration.ofNanos(goMs.map { it.duration.toNanos() }.average().toLong())
            val goAvgThroughput = goMs.map { it.throughputBytesPerSec }.average() / (1024 * 1024)

            appendLine()
            appendLine("Go Results (${goMs.size} iterations):")
            appendLine("  Duration: ${formatDuration(goAvgDuration)}")
            appendLine("  Throughput: ${String.format("%.2f", goAvgThroughput)} MB/s")

            goComparisonRatio?.let { ratio ->
                appendLine()
                appendLine("Comparison (Kotlin / Go):")
                appendLine("  Time Ratio: ${String.format("%.2f", ratio)}x")
                when {
                    ratio < 0.9 -> appendLine("  → Kotlin is ${String.format("%.0f", (1 - ratio) * 100)}% FASTER")
                    ratio > 1.1 -> appendLine("  → Kotlin is ${String.format("%.0f", (ratio - 1) * 100)}% SLOWER")
                    else -> appendLine("  → Performance is comparable")
                }
            }
        }
    }

    companion object {
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
}

/**
 * Specification of test data used in a benchmark.
 */
data class TestDataSpec(
    /**
     * Number of files.
     */
    val fileCount: Int,

    /**
     * Total bytes.
     */
    val totalBytes: Long,

    /**
     * Average file size in bytes.
     */
    val avgFileSize: Int,

    /**
     * Minimum file size in bytes.
     */
    val minFileSize: Int = 0,

    /**
     * Maximum file size in bytes.
     */
    val maxFileSize: Int = 0,

    /**
     * Number of directories.
     */
    val directoryCount: Int = 0,

    /**
     * Content pattern used.
     */
    val contentPattern: String = "random",
)

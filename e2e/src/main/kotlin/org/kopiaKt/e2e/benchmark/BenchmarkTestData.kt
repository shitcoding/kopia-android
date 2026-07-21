@file:OptIn(ExperimentalPathApi::class)

package org.kopiaKt.e2e.benchmark

import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

/**
 * Generator for benchmark test data.
 *
 * Creates various test data patterns to exercise different aspects
 * of backup/restore performance.
 */
class BenchmarkTestData(
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Creates test data with the specified configuration.
     *
     * @param targetDir Directory to create test data in
     * @param config Test data configuration
     * @return Specification of what was created
     */
    fun create(targetDir: Path, config: TestDataConfig): TestDataSpec {
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.createDirectories()

        var totalFiles = 0
        var totalBytes = 0L

        when (config.pattern) {
            TestDataPattern.MANY_SMALL_FILES -> {
                // Create many small files (simulates documents, configs)
                val filesPerDir = config.fileCount / config.directoryCount.coerceAtLeast(1)
                repeat(config.directoryCount) { dirIndex ->
                    val dir = targetDir.resolve("dir_${dirIndex.toString().padStart(4, '0')}")
                    dir.createDirectories()

                    repeat(filesPerDir) { fileIndex ->
                        val size = (config.avgFileSize * (0.5 + random.nextDouble())).toInt()
                        val file = dir.resolve("file_${fileIndex.toString().padStart(5, '0')}.txt")
                        createFileWithContent(file, size, config.contentType)
                        totalFiles++
                        totalBytes += size
                    }
                }
            }

            TestDataPattern.FEW_LARGE_FILES -> {
                // Create a few large files (simulates media, databases)
                repeat(config.fileCount) { fileIndex ->
                    val size = config.avgFileSize
                    val file = targetDir.resolve("large_${fileIndex.toString().padStart(4, '0')}.bin")
                    createFileWithContent(file, size, config.contentType)
                    totalFiles++
                    totalBytes += size
                }
            }

            TestDataPattern.MIXED -> {
                // Mix of small and large files (simulates typical projects)
                val smallCount = config.fileCount * 80 / 100
                val largeCount = config.fileCount - smallCount

                // Small files in directories
                val dirs = config.directoryCount.coerceAtLeast(1)
                val smallPerDir = smallCount / dirs
                repeat(dirs) { dirIndex ->
                    val dir = targetDir.resolve("dir_${dirIndex.toString().padStart(4, '0')}")
                    dir.createDirectories()

                    repeat(smallPerDir) { fileIndex ->
                        val size = (config.avgFileSize / 10 * (0.5 + random.nextDouble())).toInt()
                        val file = dir.resolve("small_${fileIndex.toString().padStart(5, '0')}.txt")
                        createFileWithContent(file, size, ContentType.TEXT)
                        totalFiles++
                        totalBytes += size
                    }
                }

                // Large files at root
                repeat(largeCount) { fileIndex ->
                    val size = config.avgFileSize * 10
                    val file = targetDir.resolve("large_${fileIndex.toString().padStart(4, '0')}.bin")
                    createFileWithContent(file, size, ContentType.RANDOM)
                    totalFiles++
                    totalBytes += size
                }
            }

            TestDataPattern.DEEP_HIERARCHY -> {
                // Deep directory hierarchy (simulates nested project structure)
                var currentDir = targetDir
                val depth = config.directoryCount.coerceAtLeast(10)
                val filesPerLevel = config.fileCount / depth

                repeat(depth) { level ->
                    currentDir = currentDir.resolve("level_$level")
                    currentDir.createDirectories()

                    repeat(filesPerLevel) { fileIndex ->
                        val size = (config.avgFileSize * (0.5 + random.nextDouble())).toInt()
                        val file = currentDir.resolve("file_${fileIndex.toString().padStart(4, '0')}.dat")
                        createFileWithContent(file, size, config.contentType)
                        totalFiles++
                        totalBytes += size
                    }
                }
            }

            TestDataPattern.COMPRESSIBLE -> {
                // Highly compressible content (simulates logs, text)
                repeat(config.fileCount) { fileIndex ->
                    val size = config.avgFileSize
                    val file = targetDir.resolve("compress_${fileIndex.toString().padStart(5, '0')}.txt")
                    createFileWithContent(file, size, ContentType.COMPRESSIBLE)
                    totalFiles++
                    totalBytes += size
                }
            }

            TestDataPattern.DUPLICATES -> {
                // Files with duplicate content (simulates copied files)
                val uniqueFiles = config.fileCount / 10
                val duplicatesPerFile = config.fileCount / uniqueFiles

                // Create unique files
                val contents = mutableListOf<ByteArray>()
                repeat(uniqueFiles) { i ->
                    val content = createContent(config.avgFileSize, config.contentType)
                    contents.add(content)

                    val file = targetDir.resolve("unique_${i.toString().padStart(4, '0')}.bin")
                    file.writeBytes(content)
                    totalFiles++
                    totalBytes += content.size
                }

                // Create duplicates
                repeat(uniqueFiles) { i ->
                    val content = contents[i]
                    repeat(duplicatesPerFile - 1) { dupIndex ->
                        val dir = targetDir.resolve("dup_$i")
                        dir.createDirectories()
                        val file = dir.resolve("copy_${dupIndex.toString().padStart(4, '0')}.bin")
                        file.writeBytes(content)
                        totalFiles++
                        totalBytes += content.size
                    }
                }
            }
        }

        return TestDataSpec(
            fileCount = totalFiles,
            totalBytes = totalBytes,
            avgFileSize = if (totalFiles > 0) (totalBytes / totalFiles).toInt() else 0,
            minFileSize = config.avgFileSize / 2,
            maxFileSize = config.avgFileSize * 2,
            directoryCount = config.directoryCount,
            contentPattern = "${config.pattern}-${config.contentType}",
        )
    }

    private fun createFileWithContent(path: Path, size: Int, contentType: ContentType) {
        val content = createContent(size, contentType)
        path.writeBytes(content)
    }

    private fun createContent(size: Int, contentType: ContentType): ByteArray = when (contentType) {
        ContentType.RANDOM -> ByteArray(size).also { random.nextBytes(it) }

        ContentType.TEXT -> {
            val words = listOf(
                "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
                "lorem", "ipsum", "dolor", "sit", "amet", "consectetur",
                "backup", "restore", "snapshot", "repository", "content",
            )
            val sb = StringBuilder()
            while (sb.length < size) {
                sb.append(words[random.nextInt(words.size)])
                sb.append(" ")
            }
            sb.toString().take(size).toByteArray()
        }

        ContentType.COMPRESSIBLE -> {
            // Highly repetitive content
            val pattern = "ABCDEFGHIJ_PATTERN_DATA_".toByteArray()
            val result = ByteArray(size)
            var pos = 0
            while (pos < size) {
                val toCopy = minOf(pattern.size, size - pos)
                System.arraycopy(pattern, 0, result, pos, toCopy)
                pos += toCopy
            }
            result
        }

        ContentType.BINARY -> {
            // Mix of structured and random data (like executables)
            val result = ByteArray(size)
            // Header (zeros)
            val headerSize = minOf(256, size)
            // Data (random)
            random.nextBytes(result)
            for (i in 0 until headerSize) {
                result[i] = 0
            }
            result
        }
    }
}

/**
 * Configuration for test data generation.
 */
data class TestDataConfig(
    /**
     * Total number of files to create.
     */
    val fileCount: Int,

    /**
     * Average file size in bytes.
     */
    val avgFileSize: Int,

    /**
     * Number of directories to create.
     */
    val directoryCount: Int = 10,

    /**
     * File distribution pattern.
     */
    val pattern: TestDataPattern = TestDataPattern.MIXED,

    /**
     * Content type for files.
     */
    val contentType: ContentType = ContentType.RANDOM,
)

/**
 * File distribution patterns.
 */
enum class TestDataPattern {
    /**
     * Many small files (< 1KB average).
     */
    MANY_SMALL_FILES,

    /**
     * Few large files (> 1MB average).
     */
    FEW_LARGE_FILES,

    /**
     * Mix of small and large files.
     */
    MIXED,

    /**
     * Deep directory hierarchy.
     */
    DEEP_HIERARCHY,

    /**
     * Highly compressible content.
     */
    COMPRESSIBLE,

    /**
     * Files with duplicate content for dedup testing.
     */
    DUPLICATES,
}

/**
 * Content types for generated files.
 */
enum class ContentType {
    /**
     * Random bytes (incompressible).
     */
    RANDOM,

    /**
     * Text-like content.
     */
    TEXT,

    /**
     * Highly compressible content.
     */
    COMPRESSIBLE,

    /**
     * Binary with some structure.
     */
    BINARY,
}

/**
 * Common benchmark scenarios with pre-defined configurations.
 */
object BenchmarkScenarios {
    /**
     * Small backup: ~10MB total, many small files.
     */
    val SMALL_BACKUP = TestDataConfig(
        fileCount = 100,
        avgFileSize = 100 * 1024, // 100KB average
        directoryCount = 10,
        pattern = TestDataPattern.MIXED,
    )

    /**
     * Medium backup: ~100MB total, mixed files.
     */
    val MEDIUM_BACKUP = TestDataConfig(
        fileCount = 500,
        avgFileSize = 200 * 1024, // 200KB average
        directoryCount = 20,
        pattern = TestDataPattern.MIXED,
    )

    /**
     * Large backup: ~500MB total, mixed files.
     */
    val LARGE_BACKUP = TestDataConfig(
        fileCount = 1000,
        avgFileSize = 500 * 1024, // 500KB average
        directoryCount = 50,
        pattern = TestDataPattern.MIXED,
    )

    /**
     * Stress test: ~1GB total, large files.
     */
    val STRESS_LARGE_FILES = TestDataConfig(
        fileCount = 100,
        avgFileSize = 10 * 1024 * 1024, // 10MB average
        directoryCount = 5,
        pattern = TestDataPattern.FEW_LARGE_FILES,
    )

    /**
     * Many small files: 10,000 files, ~100MB total.
     */
    val STRESS_MANY_FILES = TestDataConfig(
        fileCount = 10000,
        avgFileSize = 10 * 1024, // 10KB average
        directoryCount = 100,
        pattern = TestDataPattern.MANY_SMALL_FILES,
    )

    /**
     * Deduplication test: Files with duplicate content.
     */
    val DEDUP_TEST = TestDataConfig(
        fileCount = 500,
        avgFileSize = 100 * 1024,
        directoryCount = 50,
        pattern = TestDataPattern.DUPLICATES,
    )

    /**
     * Compression test: Highly compressible content.
     */
    val COMPRESSION_TEST = TestDataConfig(
        fileCount = 100,
        avgFileSize = 1 * 1024 * 1024, // 1MB average
        directoryCount = 10,
        pattern = TestDataPattern.COMPRESSIBLE,
        contentType = ContentType.COMPRESSIBLE,
    )
}

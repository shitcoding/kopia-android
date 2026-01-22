package org.kopiaKt.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Generates test data for E2E testing.
 *
 * Creates various file structures that exercise different aspects of the backup system:
 * - Small and large files
 * - Nested directories
 * - Symbolic links
 * - Binary files with various content patterns
 * - Files with special characters in names
 */
class TestDataGenerator(
    private val random: SecureRandom = SecureRandom()
) {

    /**
     * Create a simple test directory with a few files.
     */
    fun createSimpleDirectory(root: Path): DirectoryInfo {
        root.createDirectories()

        val files = mutableListOf<FileInfo>()
        val dirs = mutableListOf<Path>()

        // Create some text files
        createTextFile(root.resolve("readme.txt"), "This is a test README file.\n").let {
            files.add(it)
        }

        createTextFile(root.resolve("data.json"), """{"name": "test", "value": 42}""").let {
            files.add(it)
        }

        // Create a subdirectory with files
        val subDir = root.resolve("subdir")
        subDir.createDirectories()
        dirs.add(subDir)

        createTextFile(subDir.resolve("nested.txt"), "Nested file content\n").let {
            files.add(it)
        }

        return DirectoryInfo(root, files, dirs, emptyList())
    }

    /**
     * Create a complex test directory with various file types and sizes.
     */
    fun createComplexDirectory(root: Path): DirectoryInfo {
        root.createDirectories()

        val files = mutableListOf<FileInfo>()
        val dirs = mutableListOf<Path>()
        val symlinks = mutableListOf<SymlinkInfo>()

        // 1. Small text files
        val docsDir = root.resolve("docs")
        docsDir.createDirectories()
        dirs.add(docsDir)

        for (i in 1..5) {
            createTextFile(
                docsDir.resolve("document$i.txt"),
                "Document $i content\n" + "Line ".repeat(i * 10)
            ).let { files.add(it) }
        }

        // 2. Binary files of various sizes
        val binDir = root.resolve("binary")
        binDir.createDirectories()
        dirs.add(binDir)

        // Small binary (1 KB)
        createBinaryFile(binDir.resolve("small.bin"), 1024).let { files.add(it) }

        // Medium binary (64 KB)
        createBinaryFile(binDir.resolve("medium.bin"), 64 * 1024).let { files.add(it) }

        // Larger binary (512 KB) - will test chunking
        createBinaryFile(binDir.resolve("large.bin"), 512 * 1024).let { files.add(it) }

        // 3. Nested directory structure
        val nestedDir = root.resolve("nested/level1/level2/level3")
        nestedDir.createDirectories()
        dirs.add(root.resolve("nested"))
        dirs.add(root.resolve("nested/level1"))
        dirs.add(root.resolve("nested/level1/level2"))
        dirs.add(nestedDir)

        createTextFile(nestedDir.resolve("deep.txt"), "Deeply nested file\n").let { files.add(it) }

        // 4. Files with repetitive content (good for dedup testing)
        val dedupDir = root.resolve("dedup")
        dedupDir.createDirectories()
        dirs.add(dedupDir)

        val repeatContent = "ABCDEFGHIJ".repeat(1000)
        for (i in 1..3) {
            createTextFile(dedupDir.resolve("repeat$i.txt"), repeatContent).let { files.add(it) }
        }

        // 5. Symbolic links (if supported)
        try {
            val linkPath = root.resolve("link_to_readme.txt")
            linkPath.createSymbolicLinkPointingTo(root.resolve("docs/document1.txt"))
            symlinks.add(SymlinkInfo(linkPath, "docs/document1.txt"))
        } catch (e: UnsupportedOperationException) {
            // Symlinks not supported on this platform
        }

        // 6. Empty file
        val emptyFile = root.resolve("empty.txt")
        emptyFile.createFile()
        files.add(FileInfo(emptyFile, 0, ByteArray(0)))

        // 7. Empty directory
        val emptyDir = root.resolve("empty_dir")
        emptyDir.createDirectories()
        dirs.add(emptyDir)

        return DirectoryInfo(root, files, dirs, symlinks)
    }

    /**
     * Create a large directory for stress testing.
     *
     * @param root Target directory
     * @param fileCount Number of files to create
     * @param avgFileSize Average file size in bytes
     */
    fun createLargeDirectory(
        root: Path,
        fileCount: Int = 100,
        avgFileSize: Int = 10 * 1024
    ): DirectoryInfo {
        root.createDirectories()

        val files = mutableListOf<FileInfo>()
        val dirs = mutableListOf<Path>()

        // Create subdirectories for organization
        val dirsCount = (fileCount / 20).coerceAtLeast(1)
        val createdDirs = mutableListOf<Path>()

        for (i in 0 until dirsCount) {
            val dir = root.resolve("dir_$i")
            dir.createDirectories()
            createdDirs.add(dir)
            dirs.add(dir)
        }

        // Create files distributed across directories
        for (i in 0 until fileCount) {
            val targetDir = createdDirs[i % createdDirs.size]

            // Vary file size around average (50% to 150%)
            val sizeVariation = 0.5 + random.nextDouble()
            val fileSize = (avgFileSize * sizeVariation).toInt()

            createBinaryFile(targetDir.resolve("file_$i.bin"), fileSize).let { files.add(it) }
        }

        return DirectoryInfo(root, files, dirs, emptyList())
    }

    /**
     * Create a test file with specific content patterns.
     */
    fun createPatternFile(path: Path, pattern: ContentPattern, size: Int): FileInfo {
        val content = when (pattern) {
            ContentPattern.ZEROS -> ByteArray(size)
            ContentPattern.ONES -> ByteArray(size) { 0xFF.toByte() }
            ContentPattern.SEQUENTIAL -> ByteArray(size) { (it % 256).toByte() }
            ContentPattern.RANDOM -> ByteArray(size).also { random.nextBytes(it) }
            ContentPattern.COMPRESSIBLE -> createCompressibleContent(size)
        }

        path.writeBytes(content)
        return FileInfo(path, content.size.toLong(), content)
    }

    private fun createTextFile(path: Path, content: String): FileInfo {
        path.writeText(content)
        return FileInfo(path, content.length.toLong(), content.toByteArray())
    }

    private fun createBinaryFile(path: Path, size: Int): FileInfo {
        val content = ByteArray(size)
        random.nextBytes(content)
        path.writeBytes(content)
        return FileInfo(path, size.toLong(), content)
    }

    private fun createCompressibleContent(size: Int): ByteArray {
        // Create content that compresses well (repeated patterns)
        val pattern = "HELLO_WORLD_TEST_DATA_"
        val result = ByteArray(size)
        var pos = 0
        while (pos < size) {
            val toCopy = minOf(pattern.length, size - pos)
            System.arraycopy(pattern.toByteArray(), 0, result, pos, toCopy)
            pos += toCopy
        }
        return result
    }

    enum class ContentPattern {
        ZEROS,
        ONES,
        SEQUENTIAL,
        RANDOM,
        COMPRESSIBLE
    }
}

/**
 * Information about a created file.
 */
data class FileInfo(
    val path: Path,
    val size: Long,
    val content: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FileInfo
        return path == other.path && size == other.size && content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

/**
 * Information about a symbolic link.
 */
data class SymlinkInfo(
    val path: Path,
    val target: String
)

/**
 * Information about a created directory structure.
 */
data class DirectoryInfo(
    val root: Path,
    val files: List<FileInfo>,
    val dirs: List<Path>,
    val symlinks: List<SymlinkInfo>
) {
    val totalSize: Long get() = files.sumOf { it.size }
    val fileCount: Int get() = files.size
    val dirCount: Int get() = dirs.size
}

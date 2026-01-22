package org.kopiaKt.e2e

import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random

/**
 * Helper class for E2E tests.
 *
 * Provides utilities for:
 * - Creating test repositories
 * - Generating test data
 * - Comparing file contents
 * - Managing temporary directories
 */
class E2ETestHelper(
    val testDir: Path = Files.createTempDirectory("kopiaKt-e2e-")
) {

    val repoDir: Path = testDir.resolve("repository")
    val sourceDir: Path = testDir.resolve("source")
    val restoreDir: Path = testDir.resolve("restore")
    val configDir: Path = testDir.resolve("config")

    val kopiaCli: KopiaCliRunner by lazy {
        KopiaCliRunner(configDir = configDir)
    }

    init {
        repoDir.createDirectories()
        sourceDir.createDirectories()
        restoreDir.createDirectories()
        configDir.createDirectories()
    }

    /**
     * Default test password.
     */
    val defaultPassword = "test-password-123"

    /**
     * Creates a KopiaKt repository.
     */
    suspend fun createKotlinRepository(
        password: String = defaultPassword,
        config: RepositoryConfig = RepositoryConfig()
    ): DirectRepositoryImpl {
        val storage = FilesystemBlobStorage(repoDir)
        return DirectRepositoryImpl.create(storage, password, config)
    }

    /**
     * Opens an existing repository with KopiaKt.
     */
    suspend fun openKotlinRepository(
        password: String = defaultPassword
    ): DirectRepositoryImpl {
        val storage = FilesystemBlobStorage(repoDir)
        return DirectRepositoryImpl.open(storage, password)
    }

    /**
     * Creates a repository using Go Kopia CLI.
     */
    suspend fun createGoRepository(
        password: String = defaultPassword
    ): KopiaCliRunner.CommandResult {
        return kopiaCli.repositoryCreate(repoDir, password)
    }

    /**
     * Connects to a repository using Go Kopia CLI.
     */
    suspend fun connectGoRepository(
        password: String = defaultPassword
    ): KopiaCliRunner.CommandResult {
        return kopiaCli.repositoryConnect(repoDir, password)
    }

    /**
     * Generates test files in the source directory.
     */
    fun generateTestFiles(
        spec: TestFileSpec = TestFileSpec()
    ): List<GeneratedFile> {
        val files = mutableListOf<GeneratedFile>()

        // Create regular files
        repeat(spec.fileCount) { i ->
            val name = "file_${i.toString().padStart(4, '0')}.dat"
            val path = sourceDir.resolve(name)
            val content = generateRandomContent(spec.minFileSize, spec.maxFileSize)
            path.writeBytes(content)
            files.add(GeneratedFile(path, content))
        }

        // Create text files
        repeat(spec.textFileCount) { i ->
            val name = "text_${i.toString().padStart(4, '0')}.txt"
            val path = sourceDir.resolve(name)
            val content = generateTextContent(spec.minFileSize, spec.maxFileSize)
            path.writeText(content)
            files.add(GeneratedFile(path, content.toByteArray()))
        }

        // Create subdirectories with files
        repeat(spec.subdirCount) { i ->
            val subdir = sourceDir.resolve("subdir_${i.toString().padStart(2, '0')}")
            subdir.createDirectories()

            repeat(spec.filesPerSubdir) { j ->
                val name = "nested_${j.toString().padStart(4, '0')}.dat"
                val path = subdir.resolve(name)
                val content = generateRandomContent(spec.minFileSize, spec.maxFileSize)
                path.writeBytes(content)
                files.add(GeneratedFile(path, content))
            }
        }

        // Create symlinks (if requested)
        if (spec.symlinkCount > 0 && files.isNotEmpty()) {
            repeat(minOf(spec.symlinkCount, files.size)) { i ->
                val targetFile = files[i % files.size]
                val linkName = "link_${i.toString().padStart(4, '0')}"
                val linkPath = sourceDir.resolve(linkName)
                try {
                    Files.createSymbolicLink(linkPath, targetFile.path)
                } catch (e: UnsupportedOperationException) {
                    // Symlinks not supported on this platform
                }
            }
        }

        return files
    }

    /**
     * Compares two directories recursively.
     */
    fun compareDirectories(
        expected: Path,
        actual: Path,
        ignoreSymlinks: Boolean = false
    ): DirectoryComparisonResult {
        val differences = mutableListOf<FileDifference>()

        val expectedFiles = collectFiles(expected)
        val actualFiles = collectFiles(actual)

        // Find files in expected but not in actual
        for ((relativePath, expectedFile) in expectedFiles) {
            val actualFile = actualFiles[relativePath]
            if (actualFile == null) {
                differences.add(FileDifference.Missing(relativePath))
                continue
            }

            // Compare content
            if (expectedFile.isRegularFile() && actualFile.isRegularFile()) {
                val expectedHash = hashFile(expectedFile)
                val actualHash = hashFile(actualFile)
                if (!expectedHash.contentEquals(actualHash)) {
                    differences.add(FileDifference.ContentMismatch(relativePath, expectedHash, actualHash))
                }
            } else if (expectedFile.isDirectory() != actualFile.isDirectory()) {
                differences.add(FileDifference.TypeMismatch(relativePath))
            } else if (!ignoreSymlinks && expectedFile.isSymbolicLink() && actualFile.isSymbolicLink()) {
                val expectedTarget = Files.readSymbolicLink(expectedFile)
                val actualTarget = Files.readSymbolicLink(actualFile)
                if (expectedTarget != actualTarget) {
                    differences.add(FileDifference.SymlinkTargetMismatch(relativePath, expectedTarget, actualTarget))
                }
            }
        }

        // Find extra files in actual
        for ((relativePath, _) in actualFiles) {
            if (relativePath !in expectedFiles) {
                differences.add(FileDifference.Extra(relativePath))
            }
        }

        return DirectoryComparisonResult(differences)
    }

    /**
     * Cleans up test directories.
     */
    fun cleanup() {
        testDir.toFile().deleteRecursively()
    }

    private fun collectFiles(root: Path): Map<String, Path> {
        val files = mutableMapOf<String, Path>()
        if (!Files.exists(root)) return files

        Files.walk(root).forEach { path ->
            if (path != root) {
                val relativePath = root.relativize(path).toString()
                files[relativePath] = path
            }
        }
        return files
    }

    private fun hashFile(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest()
    }

    private fun generateRandomContent(minSize: Int, maxSize: Int): ByteArray {
        val size = if (minSize == maxSize) minSize else Random.nextInt(minSize, maxSize)
        return Random.nextBytes(size)
    }

    private fun generateTextContent(minSize: Int, maxSize: Int): String {
        val size = if (minSize == maxSize) minSize else Random.nextInt(minSize, maxSize)
        val words = listOf(
            "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur",
            "backup", "restore", "snapshot", "repository", "content",
            "kotlin", "android", "kopia", "encryption", "compression"
        )
        val sb = StringBuilder()
        while (sb.length < size) {
            sb.append(words.random())
            sb.append(" ")
        }
        return sb.toString().take(size)
    }

    private fun Path.isRegularFile() = Files.isRegularFile(this)
    private fun Path.isDirectory() = Files.isDirectory(this)
    private fun Path.isSymbolicLink() = Files.isSymbolicLink(this)
}

/**
 * Specification for generating test files.
 */
data class TestFileSpec(
    val fileCount: Int = 10,
    val textFileCount: Int = 5,
    val subdirCount: Int = 2,
    val filesPerSubdir: Int = 5,
    val symlinkCount: Int = 0,
    val minFileSize: Int = 100,
    val maxFileSize: Int = 10000
)

/**
 * Information about a generated test file.
 */
data class GeneratedFile(
    val path: Path,
    val content: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedFile) return false
        return path == other.path && content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        return 31 * path.hashCode() + content.contentHashCode()
    }
}

/**
 * Result of comparing two directories.
 */
data class DirectoryComparisonResult(
    val differences: List<FileDifference>
) {
    val identical: Boolean get() = differences.isEmpty()

    override fun toString(): String {
        if (identical) return "Directories are identical"
        return buildString {
            appendLine("Found ${differences.size} differences:")
            differences.forEach { diff ->
                appendLine("  - $diff")
            }
        }
    }
}

/**
 * Represents a difference between two files.
 */
sealed class FileDifference {
    abstract val path: String

    data class Missing(override val path: String) : FileDifference() {
        override fun toString() = "Missing: $path"
    }

    data class Extra(override val path: String) : FileDifference() {
        override fun toString() = "Extra: $path"
    }

    data class ContentMismatch(
        override val path: String,
        val expectedHash: ByteArray,
        val actualHash: ByteArray
    ) : FileDifference() {
        override fun toString() = "Content mismatch: $path"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ContentMismatch) return false
            return path == other.path
        }
        override fun hashCode() = path.hashCode()
    }

    data class TypeMismatch(override val path: String) : FileDifference() {
        override fun toString() = "Type mismatch: $path"
    }

    data class SymlinkTargetMismatch(
        override val path: String,
        val expectedTarget: Path,
        val actualTarget: Path
    ) : FileDifference() {
        override fun toString() = "Symlink target mismatch: $path (expected: $expectedTarget, actual: $actualTarget)"
    }
}

package org.kopiaKt.e2e

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Base class for cross-compatibility E2E tests.
 *
 * Provides common setup and utilities for testing interoperability
 * between the Kotlin implementation and Go Kopia.
 */
abstract class CrossCompatibilityTestBase {

    @TempDir
    lateinit var tempDir: Path

    protected lateinit var testDir: Path
    protected lateinit var repoDir: Path
    protected lateinit var sourceDir: Path
    protected lateinit var restoreDir: Path
    protected lateinit var configDir: Path

    protected lateinit var cliRunner: KopiaCliRunner
    protected lateinit var testDataGenerator: TestDataGenerator

    protected val testPassword = "test-password-123"

    @BeforeEach
    fun setUp() {
        // Create test directories
        testDir = tempDir.resolve("e2e-test")
        testDir.createDirectories()

        repoDir = testDir.resolve("repo")
        repoDir.createDirectories()

        sourceDir = testDir.resolve("source")
        sourceDir.createDirectories()

        restoreDir = testDir.resolve("restore")
        restoreDir.createDirectories()

        configDir = testDir.resolve("config")
        configDir.createDirectories()

        // Initialize helpers
        cliRunner = KopiaCliRunner(
            kopiaBinary = kopiaBinaryPath,
            configDir = configDir
        )

        testDataGenerator = TestDataGenerator()
    }

    /**
     * Create a repository using Go Kopia CLI.
     */
    protected suspend fun createRepositoryWithGo(
        hashAlgorithm: String = "BLAKE2B-256-128",
        encryption: String = "AES256-GCM-HMAC-SHA256"
    ) {
        cliRunner.repositoryCreate(
            repoPath = repoDir,
            password = testPassword,
            blockHashAlgorithm = hashAlgorithm,
            encryptionAlgorithm = encryption
        )
    }

    /**
     * Create a repository using Kotlin implementation.
     */
    protected suspend fun createRepositoryWithKotlin(
        hash: String = "BLAKE2B-256-128",
        encryption: String = "AES256-GCM-HMAC-SHA256",
        splitter: String = "FIXED-1M"
    ): DirectRepositoryImpl {
        val storage = createBlobStorage()
        val config = createRepositoryConfig(hash, encryption, splitter)
        return DirectRepositoryImpl.create(storage, testPassword, config)
    }

    /**
     * Open a repository using Kotlin implementation.
     */
    protected suspend fun openRepositoryWithKotlin(): DirectRepositoryImpl {
        val storage = createBlobStorage()
        return DirectRepositoryImpl.open(storage, testPassword)
    }

    /**
     * Connect to repository using Go Kopia CLI.
     */
    protected suspend fun connectRepositoryWithGo() {
        cliRunner.repositoryConnect(repoDir, testPassword)
    }

    /**
     * Create blob storage for the test repository.
     */
    protected fun createBlobStorage(): BlobStorage {
        return FilesystemBlobStorage(repoDir)
    }

    /**
     * Create repository configuration for Kotlin.
     */
    protected fun createRepositoryConfig(
        hash: String,
        encryption: String,
        splitter: String
    ): RepositoryConfig {
        val random = SecureRandom()
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val masterKey = ByteArray(32).also { random.nextBytes(it) }

        return RepositoryConfig(
            hash = hash,
            encryption = encryption,
            secret = secret,
            masterKey = masterKey,
            splitter = splitter
        )
    }

    /**
     * Compare two directories recursively.
     */
    protected fun compareDirectories(dir1: Path, dir2: Path): ComparisonResult {
        val missingInDir2 = mutableListOf<String>()
        val missingInDir1 = mutableListOf<String>()
        val contentMismatches = mutableListOf<String>()

        val dir1Files = collectFiles(dir1)
        val dir2Files = collectFiles(dir2)

        // Check files in dir1
        for ((relativePath, content) in dir1Files) {
            if (!dir2Files.containsKey(relativePath)) {
                missingInDir2.add(relativePath)
            } else if (!content.contentEquals(dir2Files[relativePath]!!)) {
                contentMismatches.add(relativePath)
            }
        }

        // Check files only in dir2
        for (relativePath in dir2Files.keys) {
            if (!dir1Files.containsKey(relativePath)) {
                missingInDir1.add(relativePath)
            }
        }

        return ComparisonResult(
            identical = missingInDir1.isEmpty() && missingInDir2.isEmpty() && contentMismatches.isEmpty(),
            missingInFirst = missingInDir1,
            missingInSecond = missingInDir2,
            contentMismatches = contentMismatches
        )
    }

    private fun collectFiles(dir: Path): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()

        if (!dir.exists()) return result

        Files.walk(dir).use { stream ->
            stream.forEach { path ->
                if (path.isRegularFile()) {
                    val relativePath = dir.relativize(path).toString()
                    result[relativePath] = path.readBytes()
                }
            }
        }

        return result
    }

    /**
     * Verify a file matches expected content.
     */
    protected fun verifyFile(path: Path, expectedContent: ByteArray): Boolean {
        if (!path.exists() || !path.isRegularFile()) return false
        return path.readBytes().contentEquals(expectedContent)
    }

    /**
     * Clean up after test.
     */
    protected suspend fun cleanup() {
        // Disconnect Go CLI if connected
        try {
            cliRunner.repositoryDisconnect()
        } catch (e: Exception) {
            // Ignore
        }
    }

    companion object {
        lateinit var kopiaBinaryPath: Path
        var goKopiaAvailable = false

        @JvmStatic
        @BeforeAll
        fun checkPrerequisites() {
            // Find kopia binary
            try {
                kopiaBinaryPath = KopiaCliRunner.defaultKopiaBinary()
                goKopiaAvailable = true
                println("Found Go Kopia at: ${kopiaBinaryPath.absolutePathString()}")
            } catch (e: IllegalStateException) {
                println("WARNING: Go Kopia binary not found. Some tests will be skipped.")
            }
        }

        /**
         * Skip test if Go Kopia is not available.
         */
        fun requireGoKopia() {
            Assumptions.assumeTrue(
                goKopiaAvailable,
                "Go Kopia binary not available, skipping test"
            )
        }
    }
}

/**
 * Result of comparing two directories.
 */
data class ComparisonResult(
    val identical: Boolean,
    val missingInFirst: List<String>,
    val missingInSecond: List<String>,
    val contentMismatches: List<String>
) {
    override fun toString(): String {
        if (identical) return "Directories are identical"

        val sb = StringBuilder("Directories differ:\n")
        if (missingInFirst.isNotEmpty()) {
            sb.append("  Missing in first: ${missingInFirst.joinToString()}\n")
        }
        if (missingInSecond.isNotEmpty()) {
            sb.append("  Missing in second: ${missingInSecond.joinToString()}\n")
        }
        if (contentMismatches.isNotEmpty()) {
            sb.append("  Content mismatches: ${contentMismatches.joinToString()}\n")
        }
        return sb.toString()
    }
}

package org.kopiaKt.e2e

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readBytes
import kotlin.io.path.readSymbolicLink

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
            configDir = configDir,
        )

        testDataGenerator = TestDataGenerator()
    }

    /**
     * Create a repository using Go Kopia CLI.
     */
    protected suspend fun createRepositoryWithGo(
        hashAlgorithm: String = "BLAKE2B-256-128",
        encryption: String = "AES256-GCM-HMAC-SHA256",
    ) {
        cliRunner.repositoryCreate(
            repoPath = repoDir,
            password = testPassword,
            blockHashAlgorithm = hashAlgorithm,
            encryptionAlgorithm = encryption,
        )
    }

    /**
     * Create a repository using Kotlin implementation.
     */
    protected suspend fun createRepositoryWithKotlin(
        hash: String = "BLAKE2B-256-128",
        encryption: String = "AES256-GCM-HMAC-SHA256",
        splitter: String = "FIXED-1M",
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
    protected fun createBlobStorage(): BlobStorage = FilesystemBlobStorage(repoDir)

    /**
     * Create repository configuration for Kotlin.
     */
    protected fun createRepositoryConfig(
        hash: String,
        encryption: String,
        splitter: String,
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
     * Compare two directories recursively, including file content
     * and optional metadata checks (entry types, symlink targets,
     * permissions, modification times, empty directories).
     *
     * @param options Controls which metadata checks to perform.
     *                Defaults to content + type comparison; other metadata checks are opt-in.
     */
    protected fun compareDirectories(
        dir1: Path,
        dir2: Path,
        options: ComparisonOptions = ComparisonOptions(),
    ): ComparisonResult {
        val missingInDir2 = mutableListOf<String>()
        val missingInDir1 = mutableListOf<String>()
        val contentMismatches = mutableListOf<String>()
        val typeMismatches = mutableListOf<String>()
        val emptyDirectoryMismatches = mutableListOf<String>()
        val symlinkTargetMismatches = mutableListOf<String>()
        val permissionMismatches = mutableListOf<String>()
        val mtimeMismatches = mutableListOf<String>()

        val dir1Entries = collectEntries(dir1)
        val dir2Entries = collectEntries(dir2)

        val allPaths = (dir1Entries.keys + dir2Entries.keys).toSortedSet()

        for (relativePath in allPaths) {
            val entry1 = dir1Entries[relativePath]
            val entry2 = dir2Entries[relativePath]

            if (entry1 == null) {
                missingInDir1.add(relativePath)
                continue
            }
            if (entry2 == null) {
                missingInDir2.add(relativePath)
                continue
            }

            // Type check
            if (options.checkTypes && entry1.type != entry2.type) {
                typeMismatches.add("$relativePath (${entry1.type} vs ${entry2.type})")
                continue
            }

            // Content check for regular files
            if (entry1.type == EntryInfo.Type.FILE && entry2.type == EntryInfo.Type.FILE) {
                val content1 = entry1.content
                val content2 = entry2.content
                if (content1 != null && content2 != null && !content1.contentEquals(content2)) {
                    contentMismatches.add(relativePath)
                }
            }

            // Symlink target check
            if (options.checkSymlinkTargets &&
                entry1.type == EntryInfo.Type.SYMLINK &&
                entry2.type == EntryInfo.Type.SYMLINK
            ) {
                if (entry1.symlinkTarget != entry2.symlinkTarget) {
                    symlinkTargetMismatches.add(
                        "$relativePath ('${entry1.symlinkTarget}' vs '${entry2.symlinkTarget}')",
                    )
                }
            }

            // Permission check (POSIX only)
            if (options.checkPermissions && IS_POSIX) {
                val perms1 = entry1.permissions
                val perms2 = entry2.permissions
                if (perms1 != null && perms2 != null && perms1 != perms2) {
                    permissionMismatches.add(
                        "$relativePath (${formatPermissions(perms1)} vs ${formatPermissions(perms2)})",
                    )
                }
            }

            // Modification time check
            if (options.checkMtimes) {
                val mtime1 = entry1.mtime
                val mtime2 = entry2.mtime
                if (mtime1 != null && mtime2 != null) {
                    val delta = Duration.between(mtime1, mtime2).abs()
                    if (delta > options.mtimeTolerance) {
                        mtimeMismatches.add(
                            "$relativePath (delta=${delta.toMillis()}ms, tolerance=${options.mtimeTolerance.toMillis()}ms)",
                        )
                    }
                }
            }
        }

        // Empty directory check
        if (options.checkEmptyDirectories) {
            val emptyDirs1 = dir1Entries.filter {
                it.value.type == EntryInfo.Type.DIRECTORY && it.value.isEmptyDirectory
            }.keys
            val emptyDirs2 = dir2Entries.filter {
                it.value.type == EntryInfo.Type.DIRECTORY && it.value.isEmptyDirectory
            }.keys
            for (d in emptyDirs1) {
                val other = dir2Entries[d]
                if (other == null) {
                    emptyDirectoryMismatches.add("$d (empty dir missing in second)")
                } else if (other.type == EntryInfo.Type.DIRECTORY && !other.isEmptyDirectory) {
                    emptyDirectoryMismatches.add("$d (empty in first, non-empty in second)")
                }
            }
            for (d in emptyDirs2) {
                val other = dir1Entries[d]
                if (other == null) {
                    emptyDirectoryMismatches.add("$d (empty dir missing in first)")
                } else if (other.type == EntryInfo.Type.DIRECTORY && !other.isEmptyDirectory) {
                    emptyDirectoryMismatches.add("$d (empty in second, non-empty in first)")
                }
            }
        }

        val identical = missingInDir1.isEmpty() &&
            missingInDir2.isEmpty() &&
            contentMismatches.isEmpty() &&
            typeMismatches.isEmpty() &&
            emptyDirectoryMismatches.isEmpty() &&
            symlinkTargetMismatches.isEmpty() &&
            permissionMismatches.isEmpty() &&
            mtimeMismatches.isEmpty()

        return ComparisonResult(
            identical = identical,
            missingInFirst = missingInDir1,
            missingInSecond = missingInDir2,
            contentMismatches = contentMismatches,
            typeMismatches = typeMismatches,
            emptyDirectoryMismatches = emptyDirectoryMismatches,
            symlinkTargetMismatches = symlinkTargetMismatches,
            permissionMismatches = permissionMismatches,
            mtimeMismatches = mtimeMismatches,
        )
    }

    /**
     * Collects all entries (files, directories, symlinks) under a directory
     * into a map keyed by relative path.
     */
    private fun collectEntries(dir: Path): Map<String, EntryInfo> {
        val result = mutableMapOf<String, EntryInfo>()

        if (!dir.exists()) return result

        Files.walk(dir).use { stream ->
            stream.forEach { path ->
                if (path == dir) return@forEach
                val relativePath = dir.relativize(path).toString()

                val info = when {
                    path.isSymbolicLink() -> {
                        val target = path.readSymbolicLink().toString()
                        val mtime = try {
                            path.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS).toInstant()
                        } catch (_: Exception) {
                            null
                        }
                        EntryInfo(
                            type = EntryInfo.Type.SYMLINK,
                            symlinkTarget = target,
                            mtime = mtime,
                            permissions = readPosixPermissions(path),
                        )
                    }
                    path.isDirectory() -> {
                        val isEmpty = Files.list(path).use { s -> s.findFirst().isEmpty }
                        val mtime = try {
                            path.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS).toInstant()
                        } catch (_: Exception) {
                            null
                        }
                        EntryInfo(
                            type = EntryInfo.Type.DIRECTORY,
                            isEmptyDirectory = isEmpty,
                            mtime = mtime,
                            permissions = readPosixPermissions(path),
                        )
                    }
                    path.isRegularFile() -> {
                        val mtime = try {
                            path.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS).toInstant()
                        } catch (_: Exception) {
                            null
                        }
                        EntryInfo(
                            type = EntryInfo.Type.FILE,
                            content = path.readBytes(),
                            mtime = mtime,
                            permissions = readPosixPermissions(path),
                        )
                    }
                    else -> return@forEach
                }
                result[relativePath] = info
            }
        }

        return result
    }

    private fun readPosixPermissions(path: Path): Set<PosixFilePermission>? {
        if (!IS_POSIX) return null
        return try {
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun formatPermissions(perms: Set<PosixFilePermission>): String = perms.sorted().joinToString(",") { it.name }

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

        private val IS_POSIX = !System.getProperty("os.name").lowercase().contains("windows")

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
                "Go Kopia binary not available, skipping test",
            )
        }
    }
}

/**
 * Options controlling which metadata checks [CrossCompatibilityTestBase.compareDirectories] performs.
 *
 * By default type checking is enabled to catch file-vs-directory mismatches.
 * Other metadata checks are disabled by default for backward compatibility.
 */
data class ComparisonOptions(
    /** Compare entry types (file vs directory vs symlink). */
    val checkTypes: Boolean = true,
    /** Compare symlink targets. */
    val checkSymlinkTargets: Boolean = false,
    /** Check for empty directories present on one side but not the other. */
    val checkEmptyDirectories: Boolean = false,
    /** Compare POSIX file permissions. Skipped automatically on non-POSIX platforms. */
    val checkPermissions: Boolean = false,
    /** Compare modification times within [mtimeTolerance]. */
    val checkMtimes: Boolean = false,
    /** Maximum allowed difference in modification times. Default 2 seconds. */
    val mtimeTolerance: Duration = Duration.ofSeconds(2),
) {
    companion object {
        /** All metadata checks enabled with default tolerance values. */
        val ALL = ComparisonOptions(
            checkTypes = true,
            checkSymlinkTargets = true,
            checkEmptyDirectories = true,
            checkPermissions = true,
            checkMtimes = true,
        )
    }
}

/**
 * Internal representation of a filesystem entry collected during directory walking.
 */
data class EntryInfo(
    val type: Type,
    val content: ByteArray? = null,
    val symlinkTarget: String? = null,
    val isEmptyDirectory: Boolean = false,
    val mtime: Instant? = null,
    val permissions: Set<PosixFilePermission>? = null,
) {
    enum class Type { FILE, DIRECTORY, SYMLINK }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EntryInfo
        return type == other.type &&
            (content?.contentEquals(other.content ?: ByteArray(0)) ?: (other.content == null)) &&
            symlinkTarget == other.symlinkTarget &&
            isEmptyDirectory == other.isEmptyDirectory &&
            mtime == other.mtime &&
            permissions == other.permissions
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (content?.contentHashCode() ?: 0)
        result = 31 * result + (symlinkTarget?.hashCode() ?: 0)
        result = 31 * result + isEmptyDirectory.hashCode()
        result = 31 * result + (mtime?.hashCode() ?: 0)
        result = 31 * result + (permissions?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of comparing two directories.
 */
data class ComparisonResult(
    val identical: Boolean,
    val missingInFirst: List<String>,
    val missingInSecond: List<String>,
    val contentMismatches: List<String>,
    val typeMismatches: List<String> = emptyList(),
    val emptyDirectoryMismatches: List<String> = emptyList(),
    val symlinkTargetMismatches: List<String> = emptyList(),
    val permissionMismatches: List<String> = emptyList(),
    val mtimeMismatches: List<String> = emptyList(),
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
        if (typeMismatches.isNotEmpty()) {
            sb.append("  Type mismatches: ${typeMismatches.joinToString()}\n")
        }
        if (emptyDirectoryMismatches.isNotEmpty()) {
            sb.append("  Empty directory mismatches: ${emptyDirectoryMismatches.joinToString()}\n")
        }
        if (symlinkTargetMismatches.isNotEmpty()) {
            sb.append("  Symlink target mismatches: ${symlinkTargetMismatches.joinToString()}\n")
        }
        if (permissionMismatches.isNotEmpty()) {
            sb.append("  Permission mismatches: ${permissionMismatches.joinToString()}\n")
        }
        if (mtimeMismatches.isNotEmpty()) {
            sb.append("  Mtime mismatches: ${mtimeMismatches.joinToString()}\n")
        }
        return sb.toString()
    }
}

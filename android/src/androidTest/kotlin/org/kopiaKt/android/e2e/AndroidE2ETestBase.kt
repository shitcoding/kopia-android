package org.kopiaKt.android.e2e

import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Base class for Android E2E tests.
 *
 * Provides common setup, teardown, and utilities for testing
 * backup and restore operations on Android devices/emulators.
 *
 * Test directories are created in the app's internal storage
 * to avoid external storage permission requirements.
 */
abstract class AndroidE2ETestBase {

    protected lateinit var context: Context
    protected lateinit var testId: String

    // Test directories (in app's internal storage)
    protected lateinit var testRoot: File
    protected lateinit var repoDir: File
    protected lateinit var sourceDir: File
    protected lateinit var restoreDir: File

    protected val testPassword = "test-password-123"

    private val random = SecureRandom()

    @Before
    open fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testId = UUID.randomUUID().toString().substring(0, 8)

        // Create test directories in app's internal storage
        testRoot = File(context.filesDir, "e2e_test_$testId")
        testRoot.mkdirs()

        repoDir = File(testRoot, "repo")
        repoDir.mkdirs()

        sourceDir = File(testRoot, "source")
        sourceDir.mkdirs()

        restoreDir = File(testRoot, "restore")
        restoreDir.mkdirs()
    }

    @After
    open fun tearDown() {
        // Clean up test directories
        try {
            testRoot.deleteRecursively()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    /**
     * Create a repository using the Kotlin implementation.
     */
    protected fun createRepository(
        hash: String = "BLAKE2B-256-128",
        encryption: String = "AES256-GCM-HMAC-SHA256",
        splitter: String = "FIXED-1M",
    ): DirectRepository = runBlocking {
        val storage = createBlobStorage()
        val config = createRepositoryConfig(hash, encryption, splitter)
        DirectRepositoryImpl.create(storage, testPassword, config)
    }

    /**
     * Open an existing repository.
     */
    protected fun openRepository(): DirectRepository = runBlocking {
        val storage = createBlobStorage()
        DirectRepositoryImpl.open(storage, testPassword)
    }

    /**
     * Create blob storage for the test repository.
     */
    protected fun createBlobStorage(): BlobStorage = FilesystemBlobStorage(repoDir.toPath())

    /**
     * Create repository configuration.
     */
    protected fun createRepositoryConfig(
        hash: String,
        encryption: String,
        splitter: String,
    ): RepositoryConfig {
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

    // ===================
    // Test data generation
    // ===================

    /**
     * Creates a simple test directory with text files.
     */
    protected fun createSimpleTestData(): TestDataInfo {
        val files = mutableListOf<TestFile>()

        // Create text files
        val readme = File(sourceDir, "readme.txt")
        readme.writeText("This is a test README file.\n")
        files.add(TestFile(readme, readme.readBytes()))

        val data = File(sourceDir, "data.json")
        data.writeText("""{"name": "test", "value": 42}""")
        files.add(TestFile(data, data.readBytes()))

        // Create subdirectory with file
        val subDir = File(sourceDir, "subdir")
        subDir.mkdirs()

        val nested = File(subDir, "nested.txt")
        nested.writeText("Nested file content\n")
        files.add(TestFile(nested, nested.readBytes()))

        return TestDataInfo(
            root = sourceDir,
            files = files,
            dirs = listOf(subDir),
            totalSize = files.sumOf { it.content.size.toLong() },
        )
    }

    /**
     * Creates complex test data with various file types and sizes.
     */
    protected fun createComplexTestData(): TestDataInfo {
        val files = mutableListOf<TestFile>()
        val dirs = mutableListOf<File>()

        // 1. Small text files
        val docsDir = File(sourceDir, "docs")
        docsDir.mkdirs()
        dirs.add(docsDir)

        for (i in 1..5) {
            val doc = File(docsDir, "document$i.txt")
            val content = "Document $i content\n" + "Line ".repeat(i * 10)
            doc.writeText(content)
            files.add(TestFile(doc, doc.readBytes()))
        }

        // 2. Binary files of various sizes
        val binDir = File(sourceDir, "binary")
        binDir.mkdirs()
        dirs.add(binDir)

        // Small binary (1 KB)
        val smallBin = File(binDir, "small.bin")
        val smallContent = ByteArray(1024).also { random.nextBytes(it) }
        smallBin.writeBytes(smallContent)
        files.add(TestFile(smallBin, smallContent))

        // Medium binary (64 KB)
        val mediumBin = File(binDir, "medium.bin")
        val mediumContent = ByteArray(64 * 1024).also { random.nextBytes(it) }
        mediumBin.writeBytes(mediumContent)
        files.add(TestFile(mediumBin, mediumContent))

        // Large binary (512 KB)
        val largeBin = File(binDir, "large.bin")
        val largeContent = ByteArray(512 * 1024).also { random.nextBytes(it) }
        largeBin.writeBytes(largeContent)
        files.add(TestFile(largeBin, largeContent))

        // 3. Nested directory structure
        val nestedDir = File(sourceDir, "nested/level1/level2/level3")
        nestedDir.mkdirs()
        dirs.add(File(sourceDir, "nested"))

        val deep = File(nestedDir, "deep.txt")
        deep.writeText("Deeply nested file\n")
        files.add(TestFile(deep, deep.readBytes()))

        // 4. Files with repetitive content (dedup test)
        val dedupDir = File(sourceDir, "dedup")
        dedupDir.mkdirs()
        dirs.add(dedupDir)

        val repeatContent = "ABCDEFGHIJ".repeat(1000).toByteArray()
        for (i in 1..3) {
            val repeat = File(dedupDir, "repeat$i.txt")
            repeat.writeBytes(repeatContent)
            files.add(TestFile(repeat, repeatContent))
        }

        // 5. Empty file
        val emptyFile = File(sourceDir, "empty.txt")
        emptyFile.createNewFile()
        files.add(TestFile(emptyFile, ByteArray(0)))

        // 6. Empty directory
        val emptyDir = File(sourceDir, "empty_dir")
        emptyDir.mkdirs()
        dirs.add(emptyDir)

        return TestDataInfo(
            root = sourceDir,
            files = files,
            dirs = dirs,
            totalSize = files.sumOf { it.content.size.toLong() },
        )
    }

    /**
     * Creates large test data for stress testing.
     */
    protected fun createLargeTestData(
        fileCount: Int = 100,
        avgFileSize: Int = 10 * 1024,
    ): TestDataInfo {
        val files = mutableListOf<TestFile>()
        val dirs = mutableListOf<File>()

        // Create subdirectories
        val dirCount = (fileCount / 20).coerceAtLeast(1)
        for (i in 0 until dirCount) {
            val dir = File(sourceDir, "dir_$i")
            dir.mkdirs()
            dirs.add(dir)
        }

        // Create files distributed across directories
        for (i in 0 until fileCount) {
            val targetDir = dirs[i % dirs.size]

            // Vary file size around average
            val sizeVariation = 0.5 + random.nextDouble()
            val fileSize = (avgFileSize * sizeVariation).toInt()

            val file = File(targetDir, "file_$i.bin")
            val content = ByteArray(fileSize).also { random.nextBytes(it) }
            file.writeBytes(content)
            files.add(TestFile(file, content))
        }

        return TestDataInfo(
            root = sourceDir,
            files = files,
            dirs = dirs,
            totalSize = files.sumOf { it.content.size.toLong() },
        )
    }

    /**
     * Creates file with specific content pattern for compression testing.
     */
    protected fun createPatternFile(name: String, pattern: ContentPattern, size: Int): TestFile {
        val file = File(sourceDir, name)
        val content = when (pattern) {
            ContentPattern.ZEROS -> ByteArray(size)
            ContentPattern.ONES -> ByteArray(size) { 0xFF.toByte() }
            ContentPattern.SEQUENTIAL -> ByteArray(size) { (it % 256).toByte() }
            ContentPattern.RANDOM -> ByteArray(size).also { random.nextBytes(it) }
            ContentPattern.COMPRESSIBLE -> createCompressibleContent(size)
        }
        file.writeBytes(content)
        return TestFile(file, content)
    }

    private fun createCompressibleContent(size: Int): ByteArray {
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

    // ====================
    // Verification helpers
    // ====================

    /**
     * Verifies that restored data matches original source.
     */
    protected fun verifyRestoredData(testData: TestDataInfo): ComparisonResult {
        val missingFiles = mutableListOf<String>()
        val contentMismatches = mutableListOf<String>()
        val extraFiles = mutableListOf<String>()

        // Check all expected files
        for (testFile in testData.files) {
            val relativePath = testFile.file.toRelativeString(sourceDir)
            val restoredFile = File(restoreDir, relativePath)

            if (!restoredFile.exists()) {
                missingFiles.add(relativePath)
            } else if (!restoredFile.readBytes().contentEquals(testFile.content)) {
                contentMismatches.add(relativePath)
            }
        }

        // Check for extra files in restore directory
        restoreDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.toRelativeString(restoreDir)
                val sourceFile = File(sourceDir, relativePath)
                if (!sourceFile.exists()) {
                    extraFiles.add(relativePath)
                }
            }
        }

        return ComparisonResult(
            identical = missingFiles.isEmpty() && contentMismatches.isEmpty() && extraFiles.isEmpty(),
            missingFiles = missingFiles,
            contentMismatches = contentMismatches,
            extraFiles = extraFiles,
        )
    }

    /**
     * Verifies that a specific file was restored correctly.
     */
    protected fun verifyFile(testFile: TestFile, baseDir: File = restoreDir): Boolean {
        val relativePath = testFile.file.toRelativeString(sourceDir)
        val restoredFile = File(baseDir, relativePath)
        return restoredFile.exists() && restoredFile.readBytes().contentEquals(testFile.content)
    }

    // ===================
    // Device information
    // ===================

    /**
     * Returns device/emulator information for test context.
     */
    protected fun getDeviceInfo(): DeviceInfo = DeviceInfo(
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        apiLevel = Build.VERSION.SDK_INT,
        androidVersion = Build.VERSION.RELEASE,
        isEmulator = Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("unknown") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK"),
        availableStorage = getAvailableStorage(),
    )

    private fun getAvailableStorage(): Long {
        val stat = StatFs(context.filesDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * Skip test if storage is insufficient.
     */
    protected fun requireStorage(requiredBytes: Long) {
        val available = getAvailableStorage()
        Assume.assumeTrue(
            "Insufficient storage: need ${requiredBytes / 1024}KB, have ${available / 1024}KB",
            available >= requiredBytes,
        )
    }

    /**
     * Skip test if API level is below required.
     */
    protected fun requireApiLevel(minApi: Int) {
        Assume.assumeTrue(
            "Requires API $minApi, device is API ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= minApi,
        )
    }
}

/**
 * Information about a test file.
 */
data class TestFile(
    val file: File,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TestFile
        return file == other.file && content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = file.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

/**
 * Information about generated test data.
 */
data class TestDataInfo(
    val root: File,
    val files: List<TestFile>,
    val dirs: List<File>,
    val totalSize: Long,
) {
    val fileCount: Int get() = files.size
    val dirCount: Int get() = dirs.size
}

/**
 * Result of comparing source and restored directories.
 */
data class ComparisonResult(
    val identical: Boolean,
    val missingFiles: List<String>,
    val contentMismatches: List<String>,
    val extraFiles: List<String>,
) {
    override fun toString(): String {
        if (identical) return "Directories are identical"

        val sb = StringBuilder("Directories differ:\n")
        if (missingFiles.isNotEmpty()) {
            sb.append("  Missing: ${missingFiles.joinToString()}\n")
        }
        if (contentMismatches.isNotEmpty()) {
            sb.append("  Content mismatch: ${contentMismatches.joinToString()}\n")
        }
        if (extraFiles.isNotEmpty()) {
            sb.append("  Extra files: ${extraFiles.joinToString()}\n")
        }
        return sb.toString()
    }
}

/**
 * Content patterns for file generation.
 */
enum class ContentPattern {
    ZEROS,
    ONES,
    SEQUENTIAL,
    RANDOM,
    COMPRESSIBLE,
}

/**
 * Device information for test context.
 */
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val apiLevel: Int,
    val androidVersion: String,
    val isEmulator: Boolean,
    val availableStorage: Long,
) {
    override fun toString(): String {
        val deviceType = if (isEmulator) "Emulator" else "Device"
        return "$deviceType: $manufacturer $model, Android $androidVersion (API $apiLevel), " +
            "Storage: ${availableStorage / (1024 * 1024)}MB"
    }
}

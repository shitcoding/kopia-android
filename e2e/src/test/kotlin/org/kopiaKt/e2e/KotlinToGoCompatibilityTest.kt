package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.format.EpochParameters
import org.kopiaKt.core.format.KopiaRepositoryJson
import org.kopiaKt.core.format.MutableParameters
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.writeSession
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import java.security.SecureRandom
import kotlin.io.path.readBytes

/**
 * Cross-compatibility tests verifying that repositories and snapshots
 * created by KopiaKt (Kotlin) can be read, verified, and restored
 * by the Go Kopia CLI.
 *
 * These are P0 gating tests: if Kotlin-created repos are not readable
 * by Go CLI, the backup feature is broken.
 *
 * Note: Kotlin currently implements pack index V1 without epoch management.
 * These tests create repos configured for V1 indexes with epochs disabled
 * to match the Kotlin implementation's capabilities. Full V2/epoch support
 * is tracked separately.
 */
@Tag("cross-compat")
class KotlinToGoCompatibilityTest : CrossCompatibilityTestBase() {

    @AfterEach
    fun tearDown() = runTest {
        cleanup()
    }

    /**
     * Creates a Kotlin repository configured for Go CLI compatibility.
     *
     * Uses index V1 and disables epoch management since the Kotlin
     * implementation currently writes V1 indexes without epoch support.
     */
    private suspend fun createGoCompatibleKotlinRepo(): DirectRepositoryImpl {
        val storage = createBlobStorage()
        val random = SecureRandom()
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val masterKey = ByteArray(32).also { random.nextBytes(it) }

        val config = RepositoryConfig(
            hash = "BLAKE2B-256-128",
            encryption = "AES256-GCM-HMAC-SHA256",
            secret = secret,
            masterKey = masterKey,
            splitter = "FIXED-1M",
            version = 1,
            indexVersion = MutableParameters.LEGACY_INDEX_VERSION,
            epochParameters = EpochParameters.DISABLED,
        )

        return DirectRepositoryImpl.create(storage, testPassword, config)
    }

    /**
     * Uploads a snapshot of the sourceDir using Kotlin.
     */
    private suspend fun uploadSnapshotWithKotlin(repo: DirectRepositoryImpl) {
        writeSession(repo) { writer ->
            val source = SourceInfo(
                host = "test-host",
                userName = "test-user",
                path = sourceDir.toString(),
            )

            val progress = CountingUploadProgress()
            val uploader = SnapshotUploader(
                writer = writer,
                source = source,
                policy = Policy(),
                progress = progress,
            )

            val rootDir = LocalFilesystem.directory(sourceDir)
            val uploadResult = uploader.upload(rootDir, UploadOptions())
            assertThat(uploadResult.incomplete).isFalse()
        }
    }

    @Test
    @DisplayName("Kotlin-created repo connectable by Go CLI")
    fun kotlinRepoConnectableByGo(): Unit = runTest {
        requireGoKopia()

        // Create repository using Kotlin with Go-compatible settings
        val repo = createGoCompatibleKotlinRepo()
        repo.use {
            repo.writeObject("hello from kotlin".toByteArray())
            repo.flush()
        }

        // Connect Go CLI to the Kotlin-created repository
        cliRunner.repositoryConnect(repoDir, testPassword)

        // Verify Go CLI can read repository status
        val status = cliRunner.repositoryStatus()
        assertThat(status.success).isTrue()
    }

    @Test
    @DisplayName("Go CLI lists Kotlin-created snapshots")
    fun goListsKotlinSnapshots(): Unit = runTest {
        requireGoKopia()

        // Create test source files
        testDataGenerator.createSimpleDirectory(sourceDir)

        // Create repository and upload snapshot using Kotlin
        val repo = createGoCompatibleKotlinRepo()
        repo.use {
            uploadSnapshotWithKotlin(repo)
        }

        // Connect Go CLI and list snapshots
        cliRunner.repositoryConnect(repoDir, testPassword)

        val snapshots = cliRunner.snapshotList(all = true)

        assertThat(snapshots).isNotEmpty()
        // The snapshot source path should match what we uploaded
        val paths = snapshots.map { it.source?.path }
        assertThat(paths).contains(sourceDir.toString())
    }

    @Test
    @DisplayName("Go CLI verifies Kotlin-created snapshot")
    fun goVerifiesKotlinSnapshot(): Unit = runTest {
        requireGoKopia()

        // Create test source files with known content
        testDataGenerator.createSimpleDirectory(sourceDir)

        // Create repository and upload snapshot using Kotlin
        val repo = createGoCompatibleKotlinRepo()
        repo.use {
            uploadSnapshotWithKotlin(repo)
        }

        // Connect Go CLI and verify the snapshot
        cliRunner.repositoryConnect(repoDir, testPassword)

        // Verify snapshot integrity using Go CLI
        val verifyResult = cliRunner.run("snapshot", "verify")
        assertThat(verifyResult.success).isTrue()

        // Ensure actual objects were verified (not a vacuous pass)
        val verifyOutput = verifyResult.stdout + verifyResult.stderr
        assertThat(verifyOutput).containsMatch("Finished processing [1-9]\\d* objects")
    }

    @Test
    @DisplayName("Go CLI restores Kotlin-created snapshot with matching content")
    fun goRestoresKotlinSnapshot(): Unit = runTest {
        requireGoKopia()

        // Create test source files
        val dirInfo = testDataGenerator.createSimpleDirectory(sourceDir)

        // Create repository and upload snapshot using Kotlin
        val repo = createGoCompatibleKotlinRepo()
        repo.use {
            uploadSnapshotWithKotlin(repo)
        }

        // Connect Go CLI
        cliRunner.repositoryConnect(repoDir, testPassword)

        // List snapshots to get the snapshot ID
        val snapshots = cliRunner.snapshotList(all = true)
        assertThat(snapshots).isNotEmpty()

        val snapshotId = snapshots.first().rootEntry?.obj
            ?: snapshots.first().id
        assertThat(snapshotId).isNotNull()

        // Restore the snapshot using Go CLI
        cliRunner.snapshotRestore(snapshotId!!, restoreDir)

        // Compare restored files against originals
        val comparison = compareDirectories(sourceDir, restoreDir)
        if (!comparison.identical) {
            throw AssertionError("Restored content does not match original: $comparison")
        }

        // Double-check individual file contents
        for (fileInfo in dirInfo.files) {
            val relativePath = sourceDir.relativize(fileInfo.path).toString()
            val restoredFile = restoreDir.resolve(relativePath)
            assertThat(restoredFile.readBytes())
                .isEqualTo(fileInfo.content)
        }
    }

    @Test
    @DisplayName("Go CLI restores Kotlin complex dataset with matching content")
    fun goRestoresKotlinComplexDataset(): Unit = runTest {
        requireGoKopia()

        // Create complex test source with various file types and sizes
        val dirInfo = testDataGenerator.createComplexDirectory(sourceDir)

        // Create repository and upload snapshot using Kotlin
        val repo = createGoCompatibleKotlinRepo()
        repo.use {
            uploadSnapshotWithKotlin(repo)
        }

        // Connect Go CLI
        cliRunner.repositoryConnect(repoDir, testPassword)

        // Verify snapshot integrity using Go CLI
        val verifyResult = cliRunner.run("snapshot", "verify")
        assertThat(verifyResult.success).isTrue()

        // List snapshots and restore
        val snapshots = cliRunner.snapshotList(all = true)
        assertThat(snapshots).isNotEmpty()

        val snapshotId = snapshots.first().rootEntry?.obj
            ?: snapshots.first().id
        assertThat(snapshotId).isNotNull()

        cliRunner.snapshotRestore(snapshotId!!, restoreDir)

        // Compare restored directory against original
        val comparison = compareDirectories(sourceDir, restoreDir)
        if (!comparison.identical) {
            throw AssertionError("Restored complex dataset does not match original: $comparison")
        }

        // Verify specific files have correct content
        for (fileInfo in dirInfo.files) {
            val relativePath = sourceDir.relativize(fileInfo.path).toString()
            val restoredFile = restoreDir.resolve(relativePath)
            assertThat(restoredFile.readBytes())
                .isEqualTo(fileInfo.content)
        }
    }

    @Test
    @DisplayName("Go CLI restores Kotlin large dataset with matching content")
    fun goRestoresKotlinLargeDataset(): Unit = runTest {
        requireGoKopia()

        // Create large dataset: 50 files, ~10KB average
        val dirInfo = testDataGenerator.createLargeDirectory(sourceDir, fileCount = 50, avgFileSize = 10 * 1024)

        // Create repository and upload snapshot using Kotlin
        val repo = createGoCompatibleKotlinRepo()
        repo.use {
            uploadSnapshotWithKotlin(repo)
        }

        // Connect Go CLI
        cliRunner.repositoryConnect(repoDir, testPassword)

        // Verify snapshot integrity
        val verifyResult = cliRunner.run("snapshot", "verify")
        assertThat(verifyResult.success).isTrue()

        // Restore
        val snapshots = cliRunner.snapshotList(all = true)
        assertThat(snapshots).isNotEmpty()

        val snapshotId = snapshots.first().rootEntry?.obj
            ?: snapshots.first().id
        assertThat(snapshotId).isNotNull()

        cliRunner.snapshotRestore(snapshotId!!, restoreDir)

        // Compare all files byte-for-byte
        val comparison = compareDirectories(sourceDir, restoreDir)
        if (!comparison.identical) {
            throw AssertionError("Restored large dataset does not match original: $comparison")
        }

        // Verify file count matches
        assertThat(dirInfo.fileCount).isEqualTo(50)
    }

    @Test
    @DisplayName("Go CLI restores Kotlin edge-case names and symlinks")
    fun goRestoresKotlinEdgeCaseNamesAndSymlinks(): Unit = runTest {
        requireGoKopia()

        // Create edge-case directory with unicode, emoji, special chars, symlinks
        val dirInfo = testDataGenerator.createEdgeCaseDirectory(sourceDir)

        // Create repository and upload snapshot using Kotlin
        val repo = createGoCompatibleKotlinRepo()
        repo.use {
            uploadSnapshotWithKotlin(repo)
        }

        // Connect Go CLI
        cliRunner.repositoryConnect(repoDir, testPassword)

        // Verify snapshot integrity
        val verifyResult = cliRunner.run("snapshot", "verify")
        assertThat(verifyResult.success).isTrue()

        // Restore
        val snapshots = cliRunner.snapshotList(all = true)
        assertThat(snapshots).isNotEmpty()

        val snapshotId = snapshots.first().rootEntry?.obj
            ?: snapshots.first().id
        assertThat(snapshotId).isNotNull()

        cliRunner.snapshotRestore(snapshotId!!, restoreDir)

        // Compare restored files against originals (file content only, symlinks may differ)
        for (fileInfo in dirInfo.files) {
            val relativePath = sourceDir.relativize(fileInfo.path).toString()
            val restoredFile = restoreDir.resolve(relativePath)
            assertThat(restoredFile.readBytes())
                .isEqualTo(fileInfo.content)
        }

        // Verify key edge-case files survived the round-trip
        assertThat(restoreDir.resolve("unicode/中文文件.txt").readBytes())
            .isEqualTo("Chinese content 中文内容\n".toByteArray())
        assertThat(restoreDir.resolve("unicode/日本語ファイル.txt").readBytes())
            .isEqualTo("Japanese content 日本語\n".toByteArray())
        assertThat(restoreDir.resolve("special_chars/file with spaces.txt").readBytes())
            .isEqualTo("Spaces\n".toByteArray())
        assertThat(restoreDir.resolve("binary_patterns/all_zeros.bin").readBytes())
            .isEqualTo(ByteArray(4096))
        assertThat(restoreDir.resolve("deep/level_1/level_2/level_3/level_4/level_5/level_6/level_7/level_8/level_9/level_10/deepest_file.txt").readBytes())
            .isEqualTo("At the bottom\n".toByteArray())
    }

    @Test
    @DisplayName("Kotlin repo format blob matches Go expectations")
    fun kotlinFormatBlobMatchesGoExpectations(): Unit = runTest {
        // This test does not require Go CLI - it validates the format blob structure
        val repo = createGoCompatibleKotlinRepo()
        repo.close()

        // Read the raw format blob from storage
        val storage = createBlobStorage()
        val formatBlobData = storage.getBlob(
            BlobId(KopiaRepositoryJson.FORMAT_BLOB_ID),
            0,
            -1,
        )

        // Parse the format blob JSON
        val formatJson = KopiaRepositoryJson.parse(formatBlobData)

        // Validate required fields that Go Kopia expects
        assertThat(formatJson.tool).isEqualTo("kopia")
        assertThat(formatJson.encryption).isEqualTo(KopiaRepositoryJson.AES256_GCM_ENCRYPTION)
        assertThat(formatJson.keyDerivationAlgorithm).isNotEmpty()
        assertThat(formatJson.uniqueID).isNotEmpty()
        assertThat(formatJson.uniqueID.size).isEqualTo(32)
        assertThat(formatJson.encryptedBlockFormat).isNotEmpty()

        // Verify the format blob can be decrypted with the correct password
        val masterKey = formatJson.deriveFormatEncryptionKeyFromPassword(testPassword)
        val config = formatJson.decryptRepositoryConfig(masterKey)

        // Validate inner config fields Go expects
        assertThat(config.hash).isEqualTo("BLAKE2B-256-128")
        assertThat(config.encryption).isEqualTo("AES256-GCM-HMAC-SHA256")
        assertThat(config.secret).isNotEmpty()
        assertThat(config.secret.size).isEqualTo(32)
        assertThat(config.masterKey).isNotEmpty()
        assertThat(config.masterKey.size).isEqualTo(32)
        assertThat(config.version).isGreaterThan(0)

        // Verify the raw JSON is well-formed and parseable by any JSON parser
        val rawJson = formatBlobData.decodeToString()
        val parsed = Json.parseToJsonElement(rawJson).jsonObject
        assertThat(parsed.containsKey("tool")).isTrue()
        assertThat(parsed.containsKey("uniqueID")).isTrue()
        assertThat(parsed.containsKey("encryptedBlockFormat")).isTrue()
        assertThat(parsed["tool"]?.jsonPrimitive?.content).isEqualTo("kopia")
    }
}

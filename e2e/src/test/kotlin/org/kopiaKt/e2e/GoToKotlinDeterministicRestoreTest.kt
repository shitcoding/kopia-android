package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.encryption.Aes256GcmHmacSha256Encryptor
import org.kopiaKt.core.format.KopiaRepositoryJson
import org.kopiaKt.core.pack.PackBlobReader
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.restore.CountingRestoreProgress
import org.kopiaKt.snapshot.restore.FilesystemOutput
import org.kopiaKt.snapshot.restore.FilesystemOutputOptions
import org.kopiaKt.snapshot.restore.RestoreOptions
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * Deterministic JVM test verifying that snapshots created by Go Kopia CLI
 * can be fully restored by the Kotlin implementation.
 *
 * Unlike Android-only Go->Kotlin tests, this runs on plain JVM and uses
 * a controlled, known source directory so the comparison is deterministic.
 *
 * Requires Go kopia CLI in PATH or at /opt/homebrew/bin/kopia.
 * Tests are skipped (not failed) if Go CLI is unavailable.
 */
@Tag("cross-compat")
class GoToKotlinDeterministicRestoreTest : CrossCompatibilityTestBase() {

    @AfterEach
    fun tearDown() = runTest {
        cleanup()
    }

    @Test
    @DisplayName("Kotlin recoverIndex decrypts a Go-created pack blob's encrypted local index")
    fun goPackBlobLocalIndex_recoveredByKotlin() = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // 1. Go creates a repo + snapshot, writing pack blobs whose local (recovery) index is encrypted.
        createDeterministicSource(sourceDir)
        createRepositoryWithGo()
        cliRunner.repositoryConnect(repoDir, testPassword)
        cliRunner.snapshotCreate(sourceDir)
        cliRunner.repositoryDisconnect()

        // 2. Derive the repo's content-encryption key from the format blob + password (same as opening it).
        val storage = createBlobStorage()
        val formatJson = KopiaRepositoryJson.parse(
            storage.getBlob(BlobId(KopiaRepositoryJson.FORMAT_BLOB_ID), 0, -1),
        )
        val config = formatJson.decryptRepositoryConfig(
            formatJson.deriveFormatEncryptionKeyFromPassword(testPassword),
        )
        val encryptor = Aes256GcmHmacSha256Encryptor.create(config.masterKey)

        // 3. Grab a non-empty pack blob written by Go (data 'p' or metadata 'q').
        val packBlobs = (storage.listBlobs("p").toList() + storage.listBlobs("q").toList())
            .filter { it.length > 0 }
        assertThat(packBlobs).isNotEmpty()
        val packData = storage.getBlob(packBlobs.first().blobId, 0, -1)

        // 4. Kotlin must DECRYPT + parse Go's encrypted local index (task-13: disaster-recovery compat).
        val recovered = PackBlobReader.recoverIndex(packData, encryptor.overhead.toUInt()) { ct, iv ->
            encryptor.decryptWithRawId(ct, iv)
        }
        assertThat(recovered).isNotNull()
        assertThat(recovered!!).isNotEmpty()

        // Sanity: the local index really is encrypted — a plaintext parse must NOT recover it.
        assertThat(PackBlobReader.recoverIndex(packData, encryptor.overhead.toUInt())).isNull()
    }

    @Test
    @DisplayName("Go-created snapshot restored by Kotlin matches source byte-for-byte")
    fun goCreatedSnapshot_restoredByKotlin_matchesSourceByteForByte() = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // 1. Create a deterministic source directory with known files
        createDeterministicSource(sourceDir)

        // 2. Create Go repo and snapshot
        createRepositoryWithGo()
        cliRunner.repositoryConnect(repoDir, testPassword)
        val snapshotInfo = cliRunner.snapshotCreate(sourceDir)
        assertThat(snapshotInfo.rootEntry?.obj).isNotNull()
        cliRunner.repositoryDisconnect()

        // 3. Open the Go-created repo with Kotlin
        val repo = openRepositoryWithKotlin()
        repo.use {
            // 4. Find the snapshot manifest
            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            assertThat(manifests).isNotEmpty()

            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            assertThat(manifest.rootEntry).isNotNull()

            // 5. Restore using Kotlin's SnapshotRestorer
            val rootEntry = snapshotRoot(repo, manifest)
            val progress = CountingRestoreProgress()
            val output = FilesystemOutput(
                targetPath = restoreDir,
                options = FilesystemOutputOptions(
                    overwriteDirectories = true,
                    overwriteFiles = true,
                    overwriteSymlinks = true,
                ),
            )
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
                progress = progress,
            )
            val stats = restorer.restore(rootEntry)

            // Verify restore completed without errors
            assertThat(stats.ignoredErrorCount).isEqualTo(0)
            assertThat(stats.restoredFileCount).isGreaterThan(0)

            // 6. Compare original source with restored data using metadata-aware comparison
            val comparison = compareDirectories(
                sourceDir,
                restoreDir,
                ComparisonOptions(
                    checkTypes = true,
                    checkSymlinkTargets = true,
                    checkEmptyDirectories = true,
                ),
            )
            if (!comparison.identical) {
                throw AssertionError(
                    "Restored content does not match original source:\n$comparison",
                )
            }
        }
    }

    @Test
    @DisplayName("Go-created snapshot with nested dirs restored by Kotlin preserves structure")
    fun goCreatedSnapshot_nestedDirs_restoredByKotlin() = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // Create a more complex source with deep nesting
        val deepDir = sourceDir.resolve("a/b/c/d/e")
        deepDir.createDirectories()
        deepDir.resolve("deep-file.txt").writeText("deeply nested content")
        sourceDir.resolve("a/b/mid-level.txt").writeText("mid-level content")
        sourceDir.resolve("root-file.txt").writeText("root level content")

        // Create Go repo + snapshot
        createRepositoryWithGo()
        cliRunner.repositoryConnect(repoDir, testPassword)
        cliRunner.snapshotCreate(sourceDir)
        cliRunner.repositoryDisconnect()

        // Kotlin restore
        val repo = openRepositoryWithKotlin()
        repo.use {
            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            val rootEntry = snapshotRoot(repo, manifest)
            val output = FilesystemOutput(
                targetPath = restoreDir,
                options = FilesystemOutputOptions(
                    overwriteDirectories = true,
                    overwriteFiles = true,
                ),
            )
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            restorer.restore(rootEntry)

            val comparison = compareDirectories(
                sourceDir,
                restoreDir,
                ComparisonOptions(checkTypes = true),
            )
            if (!comparison.identical) {
                throw AssertionError(
                    "Nested directory structure mismatch:\n$comparison",
                )
            }
        }
    }

    @Test
    @DisplayName("Go-created snapshot with binary data restored by Kotlin preserves bytes")
    fun goCreatedSnapshot_binaryData_restoredByKotlin() = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // Create binary files with known deterministic content
        val allBytes = ByteArray(256) { it.toByte() }
        val zeros = ByteArray(4096)
        val ones = ByteArray(4096) { 0xFF.toByte() }
        val sequential = ByteArray(65536) { (it % 256).toByte() }

        sourceDir.resolve("all-bytes.bin").writeBytes(allBytes)
        sourceDir.resolve("zeros.bin").writeBytes(zeros)
        sourceDir.resolve("ones.bin").writeBytes(ones)
        sourceDir.resolve("sequential.bin").writeBytes(sequential)

        // Create Go repo + snapshot
        createRepositoryWithGo()
        cliRunner.repositoryConnect(repoDir, testPassword)
        cliRunner.snapshotCreate(sourceDir)
        cliRunner.repositoryDisconnect()

        // Kotlin restore
        val repo = openRepositoryWithKotlin()
        repo.use {
            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            val rootEntry = snapshotRoot(repo, manifest)
            val output = FilesystemOutput(
                targetPath = restoreDir,
                options = FilesystemOutputOptions(
                    overwriteDirectories = true,
                    overwriteFiles = true,
                ),
            )
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            restorer.restore(rootEntry)

            // Byte-level verification of each binary file
            assertThat(restoreDir.resolve("all-bytes.bin").readBytes()).isEqualTo(allBytes)
            assertThat(restoreDir.resolve("zeros.bin").readBytes()).isEqualTo(zeros)
            assertThat(restoreDir.resolve("ones.bin").readBytes()).isEqualTo(ones)
            assertThat(restoreDir.resolve("sequential.bin").readBytes()).isEqualTo(sequential)
        }
    }

    @Test
    @DisplayName("Go-created snapshot with empty files and dirs restored by Kotlin")
    fun goCreatedSnapshot_emptyFilesAndDirs_restoredByKotlin() = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // Create empty file and empty directory
        sourceDir.resolve("empty-file.txt").createFile()
        sourceDir.resolve("empty-dir").createDirectories()
        // Also a non-empty file to validate the snapshot isn't vacuous
        sourceDir.resolve("non-empty.txt").writeText("content")

        // Create Go repo + snapshot
        createRepositoryWithGo()
        cliRunner.repositoryConnect(repoDir, testPassword)
        cliRunner.snapshotCreate(sourceDir)
        cliRunner.repositoryDisconnect()

        // Kotlin restore
        val repo = openRepositoryWithKotlin()
        repo.use {
            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            val rootEntry = snapshotRoot(repo, manifest)
            val output = FilesystemOutput(
                targetPath = restoreDir,
                options = FilesystemOutputOptions(
                    overwriteDirectories = true,
                    overwriteFiles = true,
                ),
            )
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            restorer.restore(rootEntry)

            val comparison = compareDirectories(
                sourceDir,
                restoreDir,
                ComparisonOptions(
                    checkTypes = true,
                    checkEmptyDirectories = true,
                ),
            )
            if (!comparison.identical) {
                throw AssertionError(
                    "Empty files/dirs mismatch:\n$comparison",
                )
            }

            // Extra: verify the empty file is truly empty
            assertThat(restoreDir.resolve("empty-file.txt").readBytes()).isEmpty()
        }
    }

    @Test
    @DisplayName("Go-created snapshot with symlinks restored by Kotlin")
    fun goCreatedSnapshot_symlinks_restoredByKotlin() = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // Create source with symlinks
        sourceDir.resolve("target.txt").writeText("symlink target content")
        val subDir = sourceDir.resolve("subdir")
        subDir.createDirectories()
        subDir.resolve("other.txt").writeText("other content")

        try {
            sourceDir.resolve("link-to-target.txt")
                .createSymbolicLinkPointingTo(sourceDir.resolve("target.txt"))
            sourceDir.resolve("link-to-subdir")
                .createSymbolicLinkPointingTo(subDir)
        } catch (e: Exception) {
            // Symlinks not supported or not permitted on this filesystem
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlink creation failed: ${e.message}")
            return@runTest
        }

        // Create Go repo + snapshot
        createRepositoryWithGo()
        cliRunner.repositoryConnect(repoDir, testPassword)
        cliRunner.snapshotCreate(sourceDir)
        cliRunner.repositoryDisconnect()

        // Kotlin restore
        val repo = openRepositoryWithKotlin()
        repo.use {
            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            val rootEntry = snapshotRoot(repo, manifest)
            val output = FilesystemOutput(
                targetPath = restoreDir,
                options = FilesystemOutputOptions(
                    overwriteDirectories = true,
                    overwriteFiles = true,
                    overwriteSymlinks = true,
                ),
            )
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            restorer.restore(rootEntry)

            // Verify symlinks and regular files are all present
            val comparison = compareDirectories(
                sourceDir,
                restoreDir,
                ComparisonOptions(
                    checkTypes = true,
                    checkSymlinkTargets = true,
                ),
            )
            if (!comparison.identical) {
                throw AssertionError(
                    "Symlink restore mismatch:\n$comparison",
                )
            }
        }
    }

    /**
     * Creates a deterministic source directory with a variety of known files
     * for comprehensive round-trip testing.
     */
    private fun createDeterministicSource(root: Path) {
        root.createDirectories()

        // Text files
        root.resolve("readme.txt").writeText("This is a deterministic test file.\n")
        root.resolve("data.json").writeText("""{"key": "value", "number": 42}""")

        // Binary files
        root.resolve("all-bytes.bin").writeBytes(ByteArray(256) { it.toByte() })
        root.resolve("zeros.bin").writeBytes(ByteArray(1024))

        // Empty file
        root.resolve("empty.txt").createFile()

        // Nested directories
        val nested = root.resolve("nested/level1/level2")
        nested.createDirectories()
        nested.resolve("deep.txt").writeText("deeply nested content\n")
        root.resolve("nested/level1/mid.txt").writeText("mid-level content\n")

        // Empty directory
        root.resolve("empty-dir").createDirectories()

        // Subdirectory with multiple files
        val multi = root.resolve("multi")
        multi.createDirectories()
        for (i in 1..5) {
            multi.resolve("file-$i.txt").writeText("File $i content: ${"x".repeat(i * 100)}\n")
        }

        // Symlink (if supported)
        try {
            root.resolve("link-to-readme.txt")
                .createSymbolicLinkPointingTo(root.resolve("readme.txt"))
        } catch (_: Exception) {
            // Symlinks not supported or not permitted on this filesystem
        }
    }
}

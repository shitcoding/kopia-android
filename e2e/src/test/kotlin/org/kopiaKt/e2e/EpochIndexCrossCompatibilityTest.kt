package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.writeSession
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions

/**
 * Cross-compatibility tests for EPOCH-mode repositories (the default: FormatVersion 3, epoch index
 * management enabled).
 *
 * The sibling [KotlinToGoCompatibilityTest] deliberately forces a legacy V1 / epoch-DISABLED repo, so it
 * exercises only the legacy `n<hash>` index-blob path. These tests use the DEFAULT Kotlin config (V3,
 * epoch enabled) — the on-disk shape every real Kotlin/Go repo uses — and assert Go can see Kotlin's
 * index writes, which requires Kotlin to write Go's epoch blob names (`xn<epoch>_<hash>-s<session>-c<N>`)
 * rather than the flat `x<hash>-<session>` it wrote before. See backlog task-20 part 1.
 */
@Tag("cross-compat")
class EpochIndexCrossCompatibilityTest : CrossCompatibilityTestBase() {

    @AfterEach
    fun tearDown() = runTest {
        cleanup()
    }

    /** Uploads a snapshot of [sourceDir] using Kotlin into the given repo. */
    private suspend fun uploadSnapshotWithKotlin(repo: DirectRepositoryImpl) {
        writeSession(repo) { writer ->
            val source = SourceInfo(host = "test-host", userName = "test-user", path = sourceDir.toString())
            val uploader = SnapshotUploader(
                writer = writer,
                source = source,
                policy = Policy(),
                progress = CountingUploadProgress(),
            )
            val rootDir = LocalFilesystem.directory(sourceDir)
            val uploadResult = uploader.upload(rootDir, UploadOptions())
            assertThat(uploadResult.incomplete).isFalse()
        }
    }

    @Test
    @DisplayName("Go CLI lists a snapshot written by a default (epoch-mode) Kotlin repo")
    fun goListsKotlinEpochSnapshot() = runTest {
        requireGoKopia()
        testDataGenerator.createSimpleDirectory(sourceDir)

        // Default config => FormatVersion 3, epoch index management enabled.
        val repo = createRepositoryWithKotlin()
        repo.use { uploadSnapshotWithKotlin(repo) }

        cliRunner.repositoryConnect(repoDir, testPassword)
        val snapshots = cliRunner.snapshotList(all = true)

        // Go's epoch reader only sees Kotlin's index writes if they carry epoch blob names.
        assertThat(snapshots).isNotEmpty()
        assertThat(snapshots.map { it.source?.path }).contains(sourceDir.toString())
    }

    @Test
    @DisplayName("Go CLI restores a default (epoch-mode) Kotlin snapshot with matching content")
    fun goRestoresKotlinEpochSnapshot() = runTest {
        requireGoKopia()
        testDataGenerator.createSimpleDirectory(sourceDir)

        val repo = createRepositoryWithKotlin()
        repo.use { uploadSnapshotWithKotlin(repo) }

        cliRunner.repositoryConnect(repoDir, testPassword)
        val snapshots = cliRunner.snapshotList(all = true)
        assertThat(snapshots).isNotEmpty()
        val snapshotId = snapshots.first().id
        assertThat(snapshotId).isNotNull()

        cliRunner.snapshotRestore(snapshotId!!, restoreDir)
        val comparison = compareDirectories(sourceDir, restoreDir)
        assertThat(comparison.identical).isTrue()
    }

    @Test
    @DisplayName("Go sees a snapshot Kotlin writes INTO a Go-created epoch repo")
    fun goSeesKotlinWriteIntoGoEpochRepo() = runTest {
        requireGoKopia()
        testDataGenerator.createSimpleDirectory(sourceDir)

        // Go creates the repo (FormatVersion 3, epoch mode, current epoch 0 with no markers).
        createRepositoryWithGo()

        // Kotlin opens the SAME repo and writes a snapshot; it must land in the current epoch so Go sees it.
        val repo = openRepositoryWithKotlin()
        repo.use { uploadSnapshotWithKotlin(repo) }

        cliRunner.repositoryConnect(repoDir, testPassword)
        val snapshots = cliRunner.snapshotList(all = true)
        assertThat(snapshots.map { it.source?.path }).contains(sourceDir.toString())
    }
}

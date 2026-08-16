@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.RepositoryUnavailableException
import org.kopiaKt.core.format.KopiaRepositoryJson
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * task-65: a write session must refuse to start once the storage stops holding the repository it was
 * opened on.
 *
 * Measured on a phone. The repository directory was moved away while the app held it open; the write
 * path's `mkdir -p` recreated it; the run wrote 2.34 GB into it and reported "Backed up 200 files
 * (2.34 GB)"; and Go then answered "repository not initialized in the provided storage". The format
 * blob is read at connect and never again, and this app keeps one repository connection for a whole
 * session, so nothing noticed.
 *
 * These are at the repository level rather than in a backend's tests on purpose. A [BlobStorage] is
 * a plain blob store — several are used as exactly that, including by the contract suite, which
 * writes to storage holding no repository at all — so "is this still my repository" cannot live
 * there. Testing it here also covers **every** backend at once, including S3, which has no root
 * directory a backend-level guard could check.
 */
class RepositoryStorageSwappedTest {

    /**
     * The measured case: the storage no longer holds the repository. Deleting the format blob from
     * in-memory storage is the backend-agnostic equivalent of the directory being moved away.
     */
    @Test
    fun `opening a write session fails once the format blob has gone`(): Unit = runTest {
        val (repo, storage) = TestRepositoryFactory.createInMemory()

        // It works while the repository is there.
        repo.newDirectWriter(WriteSessionOptions(purpose = "before")).close()

        storage.deleteBlob(BlobId(KopiaRepositoryJson.FORMAT_BLOB_ID))

        val error = assertThrows<RepositoryUnavailableException> {
            repo.newDirectWriter(WriteSessionOptions(purpose = "after"))
        }
        // The message is persisted on the source and rendered on the dashboard (task-39), so it has
        // to be written for a person and tell them what to do.
        assertThat(error.message).contains("reconnect")
    }

    /**
     * The half a backend's own guard cannot catch: the directory is **still there**, just no longer
     * a repository — a sync client swapping in a fresh empty folder, a file manager recreating it,
     * the user making the folder again by hand. `FilesystemBlobStorage`'s guard only knows whether
     * its base path exists, and here it does; only the repository-level check sees the difference.
     *
     * Filesystem-backed on purpose: on in-memory storage "emptied" and "replaced" are the same
     * thing, so this distinction only exists against a real directory.
     */
    @Test
    fun `opening a write session fails when the directory survives but the repository does not`(
        @TempDir tempDir: Path,
    ): Unit = runTest {
        val repoDir = tempDir.resolve("repo").also { it.createDirectories() }
        val storage = FilesystemBlobStorage.create(repoDir, create = true)
        val repo = DirectRepositoryImpl.create(storage, "test-password", TestRepositoryFactory.createConfig())

        repo.newDirectWriter(WriteSessionOptions(purpose = "before")).close()

        // Swapped for a fresh, plausible-looking, empty directory — the root exists throughout.
        repoDir.listDirectoryEntries().forEach { it.deleteRecursively() }
        assertThat(repoDir.isDirectory()).isTrue()

        assertThrows<RepositoryUnavailableException> {
            repo.newDirectWriter(WriteSessionOptions(purpose = "after"))
        }
    }
}

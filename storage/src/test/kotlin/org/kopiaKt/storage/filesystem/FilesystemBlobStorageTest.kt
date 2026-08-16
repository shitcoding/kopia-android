@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.kopiaKt.storage.filesystem

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobStorageContractTest
import org.kopiaKt.core.blob.RepositoryUnavailableException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Tests for FilesystemBlobStorage using the contract test suite.
 */
class FilesystemBlobStorageTest : BlobStorageContractTest() {

    @TempDir
    lateinit var tempDir: Path

    private var storageDir: Path? = null

    override fun createStorage(): BlobStorage {
        // Create a unique directory for each test
        storageDir = tempDir.resolve("storage-${System.nanoTime()}")
        storageDir!!.createDirectories()
        return FilesystemBlobStorage.create(storageDir!!)
    }

    // cleanupStorage is invoked by the contract base's @AfterEach after every test.
    override fun cleanupStorage(storage: BlobStorage) {
        storageDir?.takeIf { it.exists() }?.deleteRecursively()
    }

    @Nested
    inner class FilesystemSpecificTests {

        @Test
        fun `should create sharded directory structure`(): Unit = runTest {
            val storage = createStorage() as FilesystemBlobStorage

            // Short blob IDs (< 20 chars) are stored at root with .f suffix
            val shortBlobId = org.kopiaKt.core.blob.BlobId("pabc123def")
            val shortData = "short data".toByteArray()
            storage.putBlob(shortBlobId, shortData)

            // Short blob should be at root with .f suffix
            val shortBlobFile = storageDir!!.resolve("pabc123def.f")
            assertTrue(shortBlobFile.exists(), "Short blob file should exist at root with .f suffix")

            // Long blob IDs (>= 20 chars) use sharding [1, 3]
            // For "pabc123def456789012345", sharding creates: p/abc/123def456789012345.f
            val longBlobId = org.kopiaKt.core.blob.BlobId("pabc123def456789012345")
            val longData = "long data".toByteArray()
            storage.putBlob(longBlobId, longData)

            // Should create sharded path: basePath/p/abc/123def456789012345.f
            val shardDir1 = storageDir!!.resolve("p")
            val shardDir2 = shardDir1.resolve("abc")
            val longBlobFile = shardDir2.resolve("123def456789012345.f")

            assertTrue(shardDir1.exists(), "First shard directory should exist")
            assertTrue(shardDir2.exists(), "Second shard directory should exist")
            assertTrue(longBlobFile.exists(), "Long blob file should exist with sharding")
        }

        /**
         * task-65: measured on a phone. The repository directory was moved away while the app held
         * it open; every subsequent write recreated it, the run wrote 2.34 GB into a directory with
         * no `kopia.repository.f` in it, and the backup reported SUCCESS. `createDirectories()` is
         * `mkdir -p`, so it does not just create the shard directory the comment claims — it
         * recreates every missing ancestor, the repository root included.
         */
        @Test
        fun `putBlob fails instead of recreating a repository root that has gone`(): Unit = runTest {
            val storage = createStorage() as FilesystemBlobStorage

            storage.putBlob(BlobId("pabc123def456789012345"), "before".toByteArray())

            storageDir!!.deleteRecursively()
            assertTrue(!storageDir!!.exists(), "precondition: the repository root is gone")

            assertThrows<RepositoryUnavailableException> {
                storage.putBlob(BlobId("pdef456789012345abcde"), "after".toByteArray())
            }

            assertTrue(
                !storageDir!!.exists(),
                "the repository root must not be recreated — that is what turned a dead write into a " +
                    "reported success",
            )
        }

        @Test
        fun `should return capacity information`(): Unit = runTest {
            val storage = createStorage() as FilesystemBlobStorage
            val capacity = storage.getCapacity()

            assertTrue(capacity.sizeBytes > 0, "Total size should be positive")
            assertTrue(capacity.freeBytes >= 0, "Free bytes should be non-negative")
            assertTrue(capacity.freeBytes <= capacity.sizeBytes, "Free bytes should not exceed total")
        }

        @Test
        fun `should report read-only mode correctly`() {
            val storage = createStorage() as FilesystemBlobStorage
            assertTrue(!storage.isReadOnly(), "Default storage should not be read-only")

            val readOnlyStorage = FilesystemBlobStorage.create(storageDir!!, readOnly = true)
            assertTrue(readOnlyStorage.isReadOnly(), "Read-only storage should report read-only")
        }

        @Test
        fun `putBlob is rejected in read-only mode`(): Unit = runTest {
            val readOnlyStorage = FilesystemBlobStorage.create(storageDir!!, readOnly = true)
            assertThrows<IllegalStateException> {
                readOnlyStorage.putBlob(org.kopiaKt.core.blob.BlobId("ro"), "data".toByteArray())
            }
        }

        @Test
        fun `deleteBlob is rejected in read-only mode`(): Unit = runTest {
            val readOnlyStorage = FilesystemBlobStorage.create(storageDir!!, readOnly = true)
            assertThrows<IllegalStateException> {
                readOnlyStorage.deleteBlob(org.kopiaKt.core.blob.BlobId("ro"))
            }
        }
    }
}

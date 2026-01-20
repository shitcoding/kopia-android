@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.kopiaKt.storage.filesystem

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobStorageContractTest
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

    override fun cleanupStorage(storage: BlobStorage) {
        storageDir?.deleteRecursively()
    }

    @AfterEach
    fun cleanup() {
        storageDir?.takeIf { it.exists() }?.deleteRecursively()
    }

    @Nested
    inner class FilesystemSpecificTests {

        @Test
        fun `should create sharded directory structure`() = runTest {
            val storage = createStorage() as FilesystemBlobStorage
            val blobId = org.kopiaKt.core.blob.BlobId("pabc123def")
            val data = "test data".toByteArray()

            storage.putBlob(blobId, data)

            // Should create sharded path: basePath/p/pabc123def
            val shardDir = storageDir!!.resolve("p")
            val blobFile = shardDir.resolve("pabc123def")

            assertTrue(shardDir.exists(), "Shard directory should exist")
            assertTrue(blobFile.exists(), "Blob file should exist")
        }

        @Test
        fun `should return capacity information`() = runTest {
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
    }
}

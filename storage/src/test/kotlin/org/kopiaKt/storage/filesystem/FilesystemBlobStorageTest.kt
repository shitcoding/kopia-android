@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.kopiaKt.storage.filesystem

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

        @Test
        fun `putBlob is rejected in read-only mode`() = runTest {
            val readOnlyStorage = FilesystemBlobStorage.create(storageDir!!, readOnly = true)
            assertThrows<IllegalStateException> {
                readOnlyStorage.putBlob(org.kopiaKt.core.blob.BlobId("ro"), "data".toByteArray())
            }
        }

        @Test
        fun `deleteBlob is rejected in read-only mode`() = runTest {
            val readOnlyStorage = FilesystemBlobStorage.create(storageDir!!, readOnly = true)
            assertThrows<IllegalStateException> {
                readOnlyStorage.deleteBlob(org.kopiaKt.core.blob.BlobId("ro"))
            }
        }
    }
}

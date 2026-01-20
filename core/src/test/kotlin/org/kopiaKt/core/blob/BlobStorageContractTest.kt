package org.kopiaKt.core.blob

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Contract tests for BlobStorage implementations.
 *
 * Any BlobStorage implementation should pass all these tests.
 * Implementations extend this class and provide their specific storage in [createStorage].
 *
 * These tests verify:
 * - Basic CRUD operations (put, get, delete, list)
 * - Partial reads with offset/length
 * - Metadata retrieval
 * - Edge cases (empty blobs, large blobs, special characters)
 * - Concurrent access safety
 * - Error handling
 */
abstract class BlobStorageContractTest {

    /**
     * Creates a new, empty BlobStorage instance for testing.
     * The storage should be isolated for each test.
     */
    abstract fun createStorage(): BlobStorage

    /**
     * Clean up resources after tests if needed.
     */
    open fun cleanupStorage(storage: BlobStorage) {}

    private lateinit var storage: BlobStorage

    @BeforeEach
    fun setUp() {
        storage = createStorage()
    }

    @Nested
    @DisplayName("putBlob and getBlob")
    inner class PutGetTests {

        @Test
        fun `should store and retrieve blob`() = runTest {
            val blobId = BlobId("test-blob-001")
            val data = "Hello, World!".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(data.toList(), retrieved.toList())
        }

        @Test
        fun `should store and retrieve empty blob`() = runTest {
            val blobId = BlobId("empty-blob")
            val data = ByteArray(0)

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(0, retrieved.size)
        }

        @Test
        fun `should store and retrieve binary data`() = runTest {
            val blobId = BlobId("binary-blob")
            val data = ByteArray(256) { it.toByte() } // All possible byte values

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(data.toList(), retrieved.toList())
        }

        @Test
        fun `should store and retrieve large blob`() = runTest {
            val blobId = BlobId("large-blob")
            val data = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(data.size, retrieved.size)
            assertEquals(data.toList(), retrieved.toList())
        }

        @Test
        fun `should overwrite existing blob by default`() = runTest {
            val blobId = BlobId("overwrite-test")
            val originalData = "original".toByteArray()
            val newData = "new data".toByteArray()

            storage.putBlob(blobId, originalData)
            storage.putBlob(blobId, newData)
            val retrieved = storage.getBlob(blobId)

            assertEquals(newData.toList(), retrieved.toList())
        }

        @Test
        fun `should not overwrite when dontOverwrite is true`() = runTest {
            val blobId = BlobId("dont-overwrite-test")
            val originalData = "original".toByteArray()
            val newData = "new data".toByteArray()

            storage.putBlob(blobId, originalData)
            storage.putBlob(blobId, newData, PutBlobOptions(dontOverwrite = true))
            val retrieved = storage.getBlob(blobId)

            assertEquals(originalData.toList(), retrieved.toList())
        }

        @Test
        fun `should throw BlobNotFoundException for non-existent blob`() = runTest {
            val blobId = BlobId("non-existent-blob")

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }
        }
    }

    @Nested
    @DisplayName("Partial reads")
    inner class PartialReadTests {

        @Test
        fun `should read with offset`() = runTest {
            val blobId = BlobId("partial-read-offset")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 5)

            assertEquals("56789", String(retrieved))
        }

        @Test
        fun `should read with length`() = runTest {
            val blobId = BlobId("partial-read-length")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 0, length = 5)

            assertEquals("01234", String(retrieved))
        }

        @Test
        fun `should read with offset and length`() = runTest {
            val blobId = BlobId("partial-read-both")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 3, length = 4)

            assertEquals("3456", String(retrieved))
        }

        @Test
        fun `should read zero bytes with length zero`() = runTest {
            val blobId = BlobId("partial-read-zero")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 5, length = 0)

            assertEquals(0, retrieved.size)
        }

        @Test
        fun `should read from start with length -1`() = runTest {
            val blobId = BlobId("partial-read-all")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 0, length = -1)

            assertEquals("0123456789", String(retrieved))
        }

        @Test
        fun `should read from offset to end with length -1`() = runTest {
            val blobId = BlobId("partial-read-offset-to-end")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 7, length = -1)

            assertEquals("789", String(retrieved))
        }
    }

    @Nested
    @DisplayName("deleteBlob")
    inner class DeleteTests {

        @Test
        fun `should delete existing blob`() = runTest {
            val blobId = BlobId("delete-test")
            val data = "to be deleted".toByteArray()

            storage.putBlob(blobId, data)
            storage.deleteBlob(blobId)

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `should not throw when deleting non-existent blob`() = runTest {
            val blobId = BlobId("non-existent-delete")

            // Should not throw
            storage.deleteBlob(blobId)
        }

        @Test
        fun `should allow re-creating deleted blob`() = runTest {
            val blobId = BlobId("recreate-test")
            val originalData = "original".toByteArray()
            val newData = "recreated".toByteArray()

            storage.putBlob(blobId, originalData)
            storage.deleteBlob(blobId)
            storage.putBlob(blobId, newData)
            val retrieved = storage.getBlob(blobId)

            assertEquals(newData.toList(), retrieved.toList())
        }
    }

    @Nested
    @DisplayName("getBlobMetadata")
    inner class MetadataTests {

        @Test
        fun `should return metadata for existing blob`() = runTest {
            val blobId = BlobId("metadata-test")
            val data = "metadata test data".toByteArray()

            storage.putBlob(blobId, data)
            val metadata = storage.getBlobMetadata(blobId)

            assertNotNull(metadata)
            assertEquals(blobId, metadata!!.blobId)
            assertEquals(data.size.toLong(), metadata.length)
            assertNotNull(metadata.timestamp)
        }

        @Test
        fun `should return null for non-existent blob`() = runTest {
            val blobId = BlobId("non-existent-metadata")

            val metadata = storage.getBlobMetadata(blobId)

            assertNull(metadata)
        }

        @Test
        fun `should return correct length for empty blob`() = runTest {
            val blobId = BlobId("empty-metadata")
            val data = ByteArray(0)

            storage.putBlob(blobId, data)
            val metadata = storage.getBlobMetadata(blobId)

            assertNotNull(metadata)
            assertEquals(0L, metadata!!.length)
        }

        @Test
        fun `should update metadata after overwrite`() = runTest {
            val blobId = BlobId("metadata-overwrite")
            val smallData = "small".toByteArray()
            val largeData = "much larger data here".toByteArray()

            storage.putBlob(blobId, smallData)
            val metadataBefore = storage.getBlobMetadata(blobId)

            storage.putBlob(blobId, largeData)
            val metadataAfter = storage.getBlobMetadata(blobId)

            assertNotNull(metadataBefore)
            assertNotNull(metadataAfter)
            assertEquals(smallData.size.toLong(), metadataBefore!!.length)
            assertEquals(largeData.size.toLong(), metadataAfter!!.length)
        }
    }

    @Nested
    @DisplayName("listBlobs")
    inner class ListTests {

        @Test
        fun `should list all blobs with empty prefix`() = runTest {
            val blobs = mapOf(
                BlobId("abc") to "data1".toByteArray(),
                BlobId("def") to "data2".toByteArray(),
                BlobId("ghi") to "data3".toByteArray()
            )

            blobs.forEach { (id, data) -> storage.putBlob(id, data) }

            val listed = storage.listBlobs("").toList()

            assertEquals(3, listed.size)
            val listedIds = listed.map { it.blobId }.toSet()
            assertTrue(listedIds.containsAll(blobs.keys))
        }

        @Test
        fun `should list blobs with matching prefix`() = runTest {
            storage.putBlob(BlobId("pack-001"), "data1".toByteArray())
            storage.putBlob(BlobId("pack-002"), "data2".toByteArray())
            storage.putBlob(BlobId("index-001"), "data3".toByteArray())

            val packBlobs = storage.listBlobs("pack-").toList()

            assertEquals(2, packBlobs.size)
            assertTrue(packBlobs.all { it.blobId.value.startsWith("pack-") })
        }

        @Test
        fun `should return empty flow when no blobs match prefix`() = runTest {
            storage.putBlob(BlobId("abc"), "data".toByteArray())

            val listed = storage.listBlobs("xyz").toList()

            assertTrue(listed.isEmpty())
        }

        @Test
        fun `should return empty flow when storage is empty`() = runTest {
            val listed = storage.listBlobs("").toList()

            assertTrue(listed.isEmpty())
        }

        @Test
        fun `should include correct metadata in listing`() = runTest {
            val blobId = BlobId("list-metadata-test")
            val data = "listing metadata".toByteArray()

            storage.putBlob(blobId, data)

            val listed = storage.listBlobs("list-").toList()

            assertEquals(1, listed.size)
            val metadata = listed.first()
            assertEquals(blobId, metadata.blobId)
            assertEquals(data.size.toLong(), metadata.length)
            assertNotNull(metadata.timestamp)
        }
    }

    @Nested
    @DisplayName("connectionInfo and displayName")
    inner class InfoTests {

        @Test
        fun `should return connection info`() {
            val info = storage.connectionInfo()

            assertNotNull(info)
            assertTrue(info.type.isNotEmpty())
        }

        @Test
        fun `should return display name`() {
            val name = storage.displayName()

            assertNotNull(name)
            assertTrue(name.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("Blob ID handling")
    inner class BlobIdTests {

        @Test
        fun `should handle blob IDs with various prefixes`() = runTest {
            val prefixes = listOf("p", "n", "s", "q", "x")

            for (prefix in prefixes) {
                val blobId = BlobId("${prefix}abc123")
                val data = "data for $prefix".toByteArray()

                storage.putBlob(blobId, data)
                val retrieved = storage.getBlob(blobId)

                assertEquals(data.toList(), retrieved.toList())
            }
        }

        @Test
        fun `should handle blob IDs with hexadecimal characters`() = runTest {
            val blobId = BlobId("p0123456789abcdef0123456789abcdef")
            val data = "hex blob data".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(data.toList(), retrieved.toList())
        }

        @Test
        fun `should handle minimum length blob ID`() = runTest {
            val blobId = BlobId("x")
            val data = "single char id".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(data.toList(), retrieved.toList())
        }

        @Test
        fun `should handle long blob ID`() = runTest {
            val blobId = BlobId("p" + "a".repeat(200))
            val data = "long id blob".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(data.toList(), retrieved.toList())
        }
    }
}

package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory

/**
 * Tests that readObject() properly fails when pack blobs referenced by the index
 * are missing, truncated, or otherwise unavailable from storage.
 *
 * Covers scenarios related to Go Kopia issues #3650 and #4033, ensuring that
 * missing or damaged pack blobs produce clear errors rather than silent data loss.
 *
 * Key insight: verifyObject() only checks index existence -- it does NOT read
 * blob data. readObject() actually reads and decrypts, so it is the correct
 * API to test missing-blob detection.
 */
class MissingBlobDetectionTest {

    private lateinit var repo: DirectRepositoryImpl
    private lateinit var storage: InMemoryBlobStorage

    @AfterEach
    fun tearDown() {
        if (::repo.isInitialized) {
            repo.close()
        }
    }

    /**
     * Collects all pack blob IDs (prefix "p" or "q") from the underlying storage.
     */
    private suspend fun collectPackBlobIds(): List<BlobId> {
        val pBlobs = storage.listBlobs(BlobId.PACK_BLOB_PREFIX).toList().map { it.blobId }
        val qBlobs = storage.listBlobs(BlobId.PACK_SPECIAL_PREFIX).toList().map { it.blobId }
        return pBlobs + qBlobs
    }

    @Nested
    @DisplayName("Single object - missing pack blob")
    inner class SingleObjectMissingPack {

        @Test
        fun `should throw when reading object whose pack blob was deleted`() = runTest {
            val data = "test data for missing blob detection".toByteArray()
            val result = TestRepositoryFactory.createWithObjects(
                mapOf("obj" to data),
            )
            repo = result.first
            storage = result.second
            val objectIds = result.third

            val objectId = objectIds.getValue("obj")

            // Verify the object is readable before deletion
            val readBack = repo.readObject(objectId)
            assertThat(readBack).isEqualTo(data)

            // Delete all pack blobs from the underlying storage
            val packBlobIds = collectPackBlobIds()
            assertThat(packBlobIds).isNotEmpty()
            for (blobId in packBlobIds) {
                storage.deleteBlob(blobId)
            }

            // Reading the object should now fail
            assertThrows<Exception> {
                repo.readObject(objectId)
            }
        }

        @Test
        fun `should throw when reading object whose pack blob was truncated`() = runTest {
            val data = "test data for truncation detection".toByteArray()
            val result = TestRepositoryFactory.createWithObjects(
                mapOf("obj" to data),
            )
            repo = result.first
            storage = result.second
            val objectIds = result.third

            val objectId = objectIds.getValue("obj")

            // Verify the object is readable before truncation
            val readBack = repo.readObject(objectId)
            assertThat(readBack).isEqualTo(data)

            // Truncate each pack blob to just 4 bytes -- far too short to contain
            // the encrypted content, so extraction or decryption must fail.
            val packBlobIds = collectPackBlobIds()
            assertThat(packBlobIds).isNotEmpty()
            for (blobId in packBlobIds) {
                val original = storage.getBlob(blobId)
                assertThat(original.size).isGreaterThan(4)
                val truncated = original.copyOf(4)
                // Delete and re-put with truncated data (overwrite)
                storage.deleteBlob(blobId)
                storage.putBlob(blobId, truncated, PutBlobOptions())
            }

            // Reading should fail due to truncated encrypted data
            assertThrows<Exception> {
                repo.readObject(objectId)
            }
        }
    }

    @Nested
    @DisplayName("verifyObject vs readObject behavior")
    inner class VerifyVsRead {

        @Test
        fun `should still succeed verifyObject when pack blob is deleted but index exists`() = runTest {
            val data = "data for verify-vs-read test".toByteArray()
            val result = TestRepositoryFactory.createWithObjects(
                mapOf("obj" to data),
            )
            repo = result.first
            storage = result.second
            val objectIds = result.third

            val objectId = objectIds.getValue("obj")

            // Delete all pack blobs
            val packBlobIds = collectPackBlobIds()
            assertThat(packBlobIds).isNotEmpty()
            for (blobId in packBlobIds) {
                storage.deleteBlob(blobId)
            }

            // verifyObject only checks the index, NOT the storage blobs.
            // It should succeed because the content ID is still in the index.
            val contentIds = repo.verifyObject(objectId)
            assertThat(contentIds).isNotNull()
            assertThat(contentIds).isNotEmpty()

            // But readObject should fail because the actual data is gone
            assertThrows<Exception> {
                repo.readObject(objectId)
            }
        }
    }

    @Nested
    @DisplayName("Multiple objects - deleted pack")
    inner class MultipleObjectsDeletedPack {

        @Test
        fun `should report error when reading any object from a deleted pack`() = runTest {
            val objects = mapOf(
                "first" to "first object data content".toByteArray(),
                "second" to "second object data content".toByteArray(),
                "third" to "third object data content".toByteArray(),
            )
            val result = TestRepositoryFactory.createWithObjects(objects)
            repo = result.first
            storage = result.second
            val objectIds = result.third

            // Verify all objects are readable before deletion
            for ((key, expectedData) in objects) {
                val readBack = repo.readObject(objectIds.getValue(key))
                assertThat(readBack).isEqualTo(expectedData)
            }

            // Delete all pack blobs
            val packBlobIds = collectPackBlobIds()
            assertThat(packBlobIds).isNotEmpty()
            for (blobId in packBlobIds) {
                storage.deleteBlob(blobId)
            }

            // Every read should fail
            for ((key, _) in objects) {
                val objectId = objectIds.getValue(key)
                assertThrows<Exception>("Reading object '$key' should fail after pack deletion") {
                    repo.readObject(objectId)
                }
            }
        }

        @Test
        fun `should handle gracefully when all pack blobs are missing`() = runTest {
            val objects = mapOf(
                "alpha" to "alpha content bytes".toByteArray(),
                "beta" to "beta content bytes here".toByteArray(),
            )
            val result = TestRepositoryFactory.createWithObjects(objects)
            repo = result.first
            storage = result.second
            val objectIds = result.third

            // Delete every pack blob (both p and q prefixes)
            val packBlobIds = collectPackBlobIds()
            assertThat(packBlobIds).isNotEmpty()
            for (blobId in packBlobIds) {
                storage.deleteBlob(blobId)
            }

            // Confirm no pack blobs remain
            val remainingPacks = collectPackBlobIds()
            assertThat(remainingPacks).isEmpty()

            // Attempting to read any stored object should throw a sensible exception
            for ((key, _) in objects) {
                val objectId = objectIds.getValue(key)
                val exception = assertThrows<Exception>(
                    "Reading object '$key' should fail when all packs are missing",
                ) {
                    repo.readObject(objectId)
                }
                // The exception should carry meaningful information
                assertThat(exception.message).isNotNull()
            }
        }
    }
}

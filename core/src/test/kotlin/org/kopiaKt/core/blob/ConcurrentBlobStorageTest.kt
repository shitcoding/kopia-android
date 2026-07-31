package org.kopiaKt.core.blob

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests that InMemoryBlobStorage handles concurrent operations safely.
 *
 * The BlobStorageContractTest claims to verify "concurrent access safety"
 * but contains no actual concurrency tests. This class fills that gap
 * by exercising simultaneous put, get, delete, and list operations
 * across multiple coroutines.
 */
class ConcurrentBlobStorageTest {

    private lateinit var storage: InMemoryBlobStorage

    @BeforeEach
    fun setUp() {
        storage = InMemoryBlobStorage("concurrent-test")
    }

    @Nested
    @DisplayName("Concurrent Writes")
    inner class ConcurrentWrites {

        @Test
        fun `should handle concurrent puts to different blob IDs`(): Unit = runTest {
            val count = 10

            val results = (0 until count).map { i ->
                async {
                    storage.putBlob(BlobId("blob-$i"), "data-$i".toByteArray(), PutBlobOptions())
                }
            }
            results.awaitAll()

            for (i in 0 until count) {
                val retrieved = storage.getBlob(BlobId("blob-$i"))
                assertThat(String(retrieved)).isEqualTo("data-$i")
            }
            assertThat(storage.size()).isEqualTo(count)
        }
    }

    @Nested
    @DisplayName("Concurrent Reads")
    inner class ConcurrentReads {

        @Test
        fun `should handle concurrent gets of same blob`(): Unit = runTest {
            val blobId = BlobId("shared-blob")
            val expectedData = "shared-data-content".toByteArray()
            storage.putBlob(blobId, expectedData, PutBlobOptions())

            val reads = (0 until 10).map {
                async {
                    storage.getBlob(blobId)
                }
            }
            val allResults = reads.awaitAll()

            for (result in allResults) {
                assertThat(result.toList()).isEqualTo(expectedData.toList())
            }
        }

        @Test
        fun `should handle concurrent put and get`(): Unit = runTest {
            // Pre-populate some blobs for reading
            for (i in 0 until 5) {
                storage.putBlob(BlobId("existing-$i"), "existing-data-$i".toByteArray(), PutBlobOptions())
            }

            val writer = async {
                for (i in 0 until 10) {
                    storage.putBlob(BlobId("new-$i"), "new-data-$i".toByteArray(), PutBlobOptions())
                }
            }

            val reader = async {
                val readResults = mutableListOf<ByteArray>()
                for (i in 0 until 5) {
                    readResults.add(storage.getBlob(BlobId("existing-$i")))
                }
                readResults
            }

            writer.await()
            val readResults = reader.await()

            // All pre-existing blobs should return correct data
            for (i in 0 until 5) {
                assertThat(String(readResults[i])).isEqualTo("existing-data-$i")
            }

            // All newly written blobs should be present
            for (i in 0 until 10) {
                val data = storage.getBlob(BlobId("new-$i"))
                assertThat(String(data)).isEqualTo("new-data-$i")
            }
        }
    }

    @Nested
    @DisplayName("Concurrent Modifications")
    inner class ConcurrentModifications {

        @Test
        fun `should handle concurrent put and delete`(): Unit = runTest {
            // Pre-populate blobs that will be deleted
            for (i in 0 until 10) {
                storage.putBlob(BlobId("delete-me-$i"), "to-delete-$i".toByteArray(), PutBlobOptions())
            }

            val writer = async {
                for (i in 0 until 10) {
                    storage.putBlob(BlobId("keep-$i"), "keep-data-$i".toByteArray(), PutBlobOptions())
                }
            }

            val deleter = async {
                for (i in 0 until 10) {
                    storage.deleteBlob(BlobId("delete-me-$i"))
                }
            }

            writer.await()
            deleter.await()

            // All "keep" blobs should be present with correct data
            for (i in 0 until 10) {
                val data = storage.getBlob(BlobId("keep-$i"))
                assertThat(String(data)).isEqualTo("keep-data-$i")
            }

            // All "delete-me" blobs should be gone
            for (i in 0 until 10) {
                assertThat(storage.contains(BlobId("delete-me-$i"))).isFalse()
            }
        }

        @Test
        fun `should handle concurrent list during modifications`(): Unit = runTest {
            // Pre-populate some blobs
            for (i in 0 until 5) {
                storage.putBlob(BlobId("list-test-$i"), "list-data-$i".toByteArray(), PutBlobOptions())
            }

            val writer = async {
                for (i in 5 until 15) {
                    storage.putBlob(BlobId("list-test-$i"), "list-data-$i".toByteArray(), PutBlobOptions())
                }
            }

            val lister = async {
                val snapshots = mutableListOf<List<BlobMetadata>>()
                repeat(5) {
                    snapshots.add(storage.listBlobs("list-test-").toList())
                }
                snapshots
            }

            writer.await()
            val snapshots = lister.await()

            // Each snapshot should contain valid metadata entries
            for (snapshot in snapshots) {
                for (metadata in snapshot) {
                    assertThat(metadata.blobId.value).startsWith("list-test-")
                    assertThat(metadata.length).isGreaterThan(0)
                }
            }

            // Final state: all 15 blobs should exist
            val finalList = storage.listBlobs("list-test-").toList()
            assertThat(finalList).hasSize(15)
        }
    }

    @Nested
    @DisplayName("Stress Test")
    inner class StressTest {

        @Test
        fun `should handle 100 concurrent operations without data corruption`(): Unit = runTest {
            val totalOps = 100
            // Pre-populate blobs for read and delete operations
            for (i in 0 until 50) {
                storage.putBlob(BlobId("stress-$i"), "stress-data-$i".toByteArray(), PutBlobOptions())
            }

            val jobs = (0 until totalOps).map { i ->
                async {
                    when (i % 4) {
                        0 -> {
                            // Put a new blob
                            val blobId = BlobId("stress-new-$i")
                            storage.putBlob(blobId, "new-stress-data-$i".toByteArray(), PutBlobOptions())
                        }
                        1 -> {
                            // Read an existing blob (may throw if deleted by another coroutine)
                            val target = i % 50
                            try {
                                storage.getBlob(BlobId("stress-$target"))
                            } catch (_: BlobNotFoundException) {
                                // Expected if a concurrent delete removed it
                            }
                        }
                        2 -> {
                            // Delete a pre-existing blob
                            val target = i % 50
                            storage.deleteBlob(BlobId("stress-$target"))
                        }
                        3 -> {
                            // List blobs
                            storage.listBlobs("stress-").toList()
                        }
                    }
                }
            }
            jobs.awaitAll()

            // Verify consistency: every blob that exists has correct, non-null data
            val remainingBlobs = storage.listBlobs("stress-").toList()
            for (metadata in remainingBlobs) {
                val data = storage.getBlob(metadata.blobId)
                assertThat(data).isNotNull()
                assertThat(data.size.toLong()).isEqualTo(metadata.length)
            }

            // Verify newly written blobs have correct content
            for (i in 0 until totalOps step 4) {
                val blobId = BlobId("stress-new-$i")
                assertThat(storage.contains(blobId)).isTrue()
                val data = storage.getBlob(blobId)
                assertThat(String(data)).isEqualTo("new-stress-data-$i")
            }
        }
    }
}

package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.testutil.CorruptionHelpers
import org.kopiaKt.core.testutil.LargeDataGenerator
import org.kopiaKt.core.testutil.TestRepositoryFactory

@DisplayName("Repository Verifier")
class SnapshotVerifierTest {
    private lateinit var repo: DirectRepositoryImpl
    private lateinit var storage: InMemoryBlobStorage

    @AfterEach
    fun tearDown() {
        if (::repo.isInitialized) repo.close()
    }

    private suspend fun collectPackBlobIds(): List<BlobId> {
        val pBlobs = storage.listBlobs(BlobId.PACK_BLOB_PREFIX).toList().map { it.blobId }
        val qBlobs = storage.listBlobs(BlobId.PACK_SPECIAL_PREFIX).toList().map { it.blobId }
        return pBlobs + qBlobs
    }

    @Nested
    @DisplayName("Intact Repository")
    inner class IntactRepository {
        @Test
        fun `should verify intact repository returns no errors`(): Unit = runTest {
            val objects = (1..5).associate { "obj$it" to "data for object $it".toByteArray() }
            val result = TestRepositoryFactory.createWithObjects(objects)
            repo = result.first
            storage = result.second
            val objectIds = result.third

            val verifier = RepositoryVerifier(repo)
            val verification = verifier.verifyObjects(objectIds.values.toList())

            assertThat(verification.isSuccess).isTrue()
            assertThat(verification.verifiedCount).isEqualTo(5)
            assertThat(verification.failedCount).isEqualTo(0)
            assertThat(verification.failedObjectIds).isEmpty()
        }
    }

    @Nested
    @DisplayName("Corrupted Content")
    inner class CorruptedContent {
        @Test
        fun `should report error when content blob is corrupted`(): Unit = runTest {
            val result = TestRepositoryFactory.createWithObjects(
                mapOf("obj" to "test data".toByteArray()),
            )
            repo = result.first
            storage = result.second
            val objectIds = result.third

            // Corrupt pack blob at offset 48+ (past 32-byte preamble + 12-byte GCM nonce)
            val packBlobIds = collectPackBlobIds()
            for (blobId in packBlobIds) {
                val data = storage.getBlob(blobId)
                if (data.size > 48) {
                    val corrupted = CorruptionHelpers.bitFlip(data, 48)
                    storage.deleteBlob(blobId)
                    storage.putBlob(blobId, corrupted, PutBlobOptions())
                }
            }

            val verifier = RepositoryVerifier(repo)
            val verification = verifier.verifyObjects(objectIds.values.toList())

            assertThat(verification.isSuccess).isFalse()
            assertThat(verification.failedCount).isGreaterThan(0)
        }

        @Test
        fun `should report error when content blob is missing`(): Unit = runTest {
            val result = TestRepositoryFactory.createWithObjects(
                mapOf("obj" to "test data".toByteArray()),
            )
            repo = result.first
            storage = result.second
            val objectIds = result.third

            // Delete all pack blobs
            for (blobId in collectPackBlobIds()) {
                storage.deleteBlob(blobId)
            }

            val verifier = RepositoryVerifier(repo)
            val verification = verifier.verifyObjects(objectIds.values.toList())

            assertThat(verification.isSuccess).isFalse()
            assertThat(verification.failedCount).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Multiple Objects")
    inner class MultipleObjects {
        @Test
        fun `should verify all objects are readable`(): Unit = runTest {
            val objects = (1..10).associate {
                "obj$it" to LargeDataGenerator.generate(1024, seed = it.toLong())
            }
            val result = TestRepositoryFactory.createWithObjects(objects)
            repo = result.first
            storage = result.second
            val objectIds = result.third

            val verifier = RepositoryVerifier(repo)
            val verification = verifier.verifyObjects(objectIds.values.toList())

            assertThat(verification.verifiedCount).isEqualTo(10)
            assertThat(verification.isSuccess).isTrue()
        }

        @Test
        fun `should report specific object IDs that failed verification`(): Unit = runTest {
            val objects = mapOf(
                "good1" to "good data 1".toByteArray(),
                "good2" to "good data 2".toByteArray(),
                "good3" to "good data 3".toByteArray(),
            )
            val result = TestRepositoryFactory.createWithObjects(objects)
            repo = result.first
            storage = result.second
            val objectIds = result.third

            // Corrupt all pack blobs to affect at least one object
            for (blobId in collectPackBlobIds()) {
                val data = storage.getBlob(blobId)
                if (data.size > 48) {
                    val corrupted = CorruptionHelpers.bitFlip(data, 48)
                    storage.deleteBlob(blobId)
                    storage.putBlob(blobId, corrupted, PutBlobOptions())
                }
            }

            val verifier = RepositoryVerifier(repo)
            val verification = verifier.verifyObjects(objectIds.values.toList())

            // At least one should fail (corruption of one pack may affect one or more objects)
            assertThat(verification.failedCount).isGreaterThan(0)
            assertThat(verification.failedObjectIds).isNotEmpty()
            // Each failed ID should have an associated error
            for (failedId in verification.failedObjectIds) {
                assertThat(verification.errors).containsKey(failedId)
            }
        }
    }

    @Nested
    @DisplayName("Error Reporting")
    inner class ErrorReporting {
        @Test
        fun `should return non-zero error count on failure`(): Unit = runTest {
            val result = TestRepositoryFactory.createWithObjects(
                mapOf("obj" to "data".toByteArray()),
            )
            repo = result.first
            storage = result.second
            val objectIds = result.third

            // Delete pack blobs to cause failure
            for (blobId in collectPackBlobIds()) {
                storage.deleteBlob(blobId)
            }

            val verifier = RepositoryVerifier(repo)
            val verification = verifier.verifyObjects(objectIds.values.toList())

            assertThat(verification.failedCount).isNotEqualTo(0)
            assertThat(verification.totalCount).isEqualTo(1)
            assertThat(verification.isSuccess).isFalse()
        }

        @Test
        fun `should include meaningful error messages`(): Unit = runTest {
            val result = TestRepositoryFactory.createWithObjects(
                mapOf("obj" to "data".toByteArray()),
            )
            repo = result.first
            storage = result.second
            val objectIds = result.third

            for (blobId in collectPackBlobIds()) {
                storage.deleteBlob(blobId)
            }

            val verifier = RepositoryVerifier(repo)
            val verification = verifier.verifyObjects(objectIds.values.toList())

            assertThat(verification.errors).isNotEmpty()
            for ((_, error) in verification.errors) {
                assertThat(error.message).isNotNull()
            }
        }
    }
}

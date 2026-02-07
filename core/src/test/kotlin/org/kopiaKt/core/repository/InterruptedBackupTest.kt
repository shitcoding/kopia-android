package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.testutil.TestRepositoryFactory

/**
 * Tests that a repository remains in a consistent state after a simulated crash
 * during backup. A "crash" is modeled by abandoning a write session without
 * calling flush() or close(), then reopening the repository from the same storage.
 *
 * Key invariant: only flushed data should be visible after reopening.
 * Unflushed writes must not corrupt the repository or affect previously committed data.
 */
@DisplayName("Interrupted Backup Recovery")
class InterruptedBackupTest {

    private lateinit var repo: DirectRepositoryImpl
    private lateinit var storage: InMemoryBlobStorage

    @AfterEach
    fun tearDown() {
        if (::repo.isInitialized) {
            try {
                repo.close()
            } catch (_: Exception) {
                // Already closed or in broken state -- ignore
            }
        }
    }

    @Nested
    @DisplayName("Unflushed Write Session")
    inner class UnflushedWriteSession {

        @Test
        fun `should open repository after pack written but index not flushed`() = runTest {
            val (createdRepo, createdStorage) = TestRepositoryFactory.createInMemory()
            repo = createdRepo
            storage = createdStorage

            // Write objects but do NOT flush -- simulating a crash between
            // writing pack data and committing the index
            val writer = repo.newDirectWriter()
            writer.writeObject("unflushed-data-1".toByteArray())
            writer.writeObject("unflushed-data-2".toByteArray())
            // Intentionally skip: writer.flush() and writer.close()

            // Close the repo (simulating app termination)
            repo.close()

            // Reopen the repository from the same storage -- must succeed
            repo = DirectRepositoryImpl.open(storage, "test-password")
            assertThat(repo).isNotNull()
        }

        @Test
        fun `should not see uncommitted objects after crash`() = runTest {
            val (createdRepo, createdStorage) = TestRepositoryFactory.createInMemory()
            repo = createdRepo
            storage = createdStorage

            // Write objects without flushing
            val writer = repo.newDirectWriter()
            val objectId1 = writer.writeObject("uncommitted-object-alpha".toByteArray())
            val objectId2 = writer.writeObject("uncommitted-object-beta".toByteArray())
            // No flush -- crash simulation

            // Close and reopen
            repo.close()
            repo = DirectRepositoryImpl.open(storage, "test-password")

            // The unflushed objects must NOT be readable from the reopened repo
            assertThrows<Exception> {
                repo.readObject(objectId1)
            }
            assertThrows<Exception> {
                repo.readObject(objectId2)
            }
        }

        @Test
        fun `should preserve previously committed objects after crash`() = runTest {
            val (createdRepo, createdStorage) = TestRepositoryFactory.createInMemory()
            repo = createdRepo
            storage = createdStorage

            // Batch 1: write and flush (committed)
            val batch1Data = mapOf(
                "committed-1" to "batch-one-first-object".toByteArray(),
                "committed-2" to "batch-one-second-object".toByteArray()
            )
            val batch1Ids = mutableMapOf<String, ObjectId>()
            val writer1 = repo.newDirectWriter()
            for ((key, data) in batch1Data) {
                batch1Ids[key] = writer1.writeObject(data)
            }
            writer1.flush()
            repo.refresh()

            // Verify batch 1 is readable before the crash
            for ((key, expectedData) in batch1Data) {
                val readBack = repo.readObject(batch1Ids.getValue(key))
                assertThat(readBack).isEqualTo(expectedData)
            }

            // Batch 2: write but do NOT flush (simulated crash)
            val writer2 = repo.newDirectWriter()
            val uncommittedId = writer2.writeObject("batch-two-uncommitted".toByteArray())
            // No flush -- crash

            // Close and reopen
            repo.close()
            repo = DirectRepositoryImpl.open(storage, "test-password")

            // Batch 1 objects must still be intact
            for ((key, expectedData) in batch1Data) {
                val readBack = repo.readObject(batch1Ids.getValue(key))
                assertThat(readBack).isEqualTo(expectedData)
            }

            // Batch 2 object must NOT be readable
            assertThrows<Exception> {
                repo.readObject(uncommittedId)
            }
        }
    }

    @Nested
    @DisplayName("Orphan Pack Blobs")
    inner class OrphanPackBlobs {

        @Test
        fun `should handle partial pack blob in storage`() = runTest {
            // Create a repository with real committed data
            val realData = "legitimate-committed-data".toByteArray()
            val (createdRepo, createdStorage, objectIds) = TestRepositoryFactory.createWithObjects(
                mapOf("real" to realData)
            )
            repo = createdRepo
            storage = createdStorage
            val realObjectId = objectIds.getValue("real")

            // Verify the real object is readable
            val readBefore = repo.readObject(realObjectId)
            assertThat(readBefore).isEqualTo(realData)

            // Inject a truncated/garbage pack blob directly into storage.
            // This simulates a partial write that was interrupted before completion.
            val orphanBlobId = BlobId("p_orphan_truncated_blob_0123456789abcdef")
            val truncatedData = ByteArray(16) { (it * 7).toByte() }
            storage.putBlob(orphanBlobId, truncatedData, PutBlobOptions())

            // Close and reopen -- the orphan blob should not prevent the repo from opening
            repo.close()
            repo = DirectRepositoryImpl.open(storage, "test-password")

            // Previously committed data must still be readable
            val readAfter = repo.readObject(realObjectId)
            assertThat(readAfter).isEqualTo(realData)
        }
    }

    @Nested
    @DisplayName("Resume After Crash")
    inner class ResumeAfterCrash {

        @Test
        fun `should resume backup after interrupted session`() = runTest {
            val (createdRepo, createdStorage) = TestRepositoryFactory.createInMemory()
            repo = createdRepo
            storage = createdStorage

            // Phase 1: successfully commit batch 1
            val batch1Data = "batch-one-committed-content".toByteArray()
            val writer1 = repo.newDirectWriter()
            val batch1Id = writer1.writeObject(batch1Data)
            writer1.flush()
            repo.refresh()

            // Verify batch 1 is readable
            assertThat(repo.readObject(batch1Id)).isEqualTo(batch1Data)

            // Phase 2: start writing batch 2, then "crash" (no flush)
            val batch2Data = "batch-two-will-be-retried".toByteArray()
            val writer2 = repo.newDirectWriter()
            writer2.writeObject(batch2Data) // written but not flushed
            // Crash -- no flush, no close on writer2

            // Close and reopen (simulating restart after crash)
            repo.close()
            repo = DirectRepositoryImpl.open(storage, "test-password")

            // Phase 3: resume by re-writing batch 2 and flushing this time
            val writer3 = repo.newDirectWriter()
            val batch2Id = writer3.writeObject(batch2Data)
            writer3.flush()
            repo.refresh()

            // Both batches should now be readable
            assertThat(repo.readObject(batch1Id)).isEqualTo(batch1Data)
            assertThat(repo.readObject(batch2Id)).isEqualTo(batch2Data)
        }
    }
}

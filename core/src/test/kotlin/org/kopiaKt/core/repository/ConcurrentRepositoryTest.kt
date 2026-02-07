package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.testutil.TestRepositoryFactory

/**
 * Tests for concurrent reads and writes to the same repository.
 *
 * Each writer is created independently via [DirectRepositoryImpl.newDirectWriter],
 * backed by an [InMemoryBlobStorage] (thread-safe via ConcurrentHashMap). After
 * concurrent writes finish, [DirectRepositoryImpl.refresh] is called before reading.
 */
@DisplayName("Concurrent Repository Operations")
class ConcurrentRepositoryTest {

    private lateinit var repo: DirectRepositoryImpl

    @AfterEach
    fun tearDown() {
        if (::repo.isInitialized) {
            try {
                repo.close()
            } catch (_: Exception) {
                // Already closed -- ignore
            }
        }
    }

    @Nested
    @DisplayName("Concurrent Writes")
    inner class ConcurrentWrites {

        @Test
        fun `should handle concurrent object writes from separate write sessions`() = runTest {
            val (createdRepo, _) = TestRepositoryFactory.createInMemory()
            repo = createdRepo

            val objectsPerWriter = 5
            val allObjectIds = mutableListOf<Pair<ObjectId, ByteArray>>()

            coroutineScope {
                val jobs = (0 until 2).map { writerIndex ->
                    async {
                        val writer = repo.newDirectWriter()
                        val localIds = mutableListOf<Pair<ObjectId, ByteArray>>()

                        for (objIndex in 0 until objectsPerWriter) {
                            val data = "writer-$writerIndex-object-$objIndex".toByteArray()
                            val objectId = writer.writeObject(data)
                            localIds.add(objectId to data)
                        }
                        writer.flush()
                        writer.close()
                        localIds
                    }
                }

                val results = jobs.awaitAll()
                for (result in results) {
                    allObjectIds.addAll(result)
                }
            }

            repo.refresh()

            assertThat(allObjectIds).hasSize(10)
            for ((objectId, expectedData) in allObjectIds) {
                val readBack = repo.readObject(objectId)
                assertThat(readBack).isEqualTo(expectedData)
            }
        }
    }

    @Nested
    @DisplayName("Concurrent Reads and Writes")
    inner class ConcurrentReadsAndWrites {

        @Test
        fun `should handle concurrent reads while write is in progress`() = runTest {
            val (createdRepo, _) = TestRepositoryFactory.createInMemory()
            repo = createdRepo

            // Pre-write 5 objects and make them readable
            val preWrittenObjects = mutableListOf<Pair<ObjectId, ByteArray>>()
            val writer1 = repo.newDirectWriter()
            for (i in 0 until 5) {
                val data = "pre-written-object-$i".toByteArray()
                val objectId = writer1.writeObject(data)
                preWrittenObjects.add(objectId to data)
            }
            writer1.flush()
            writer1.close()
            repo.refresh()

            // Launch concurrent reads and a concurrent write simultaneously
            coroutineScope {
                // One coroutine writes 5 more objects with small delays
                val writeJob = async {
                    val writer2 = repo.newDirectWriter()
                    val newIds = mutableListOf<Pair<ObjectId, ByteArray>>()
                    for (i in 0 until 5) {
                        val data = "concurrent-write-object-$i".toByteArray()
                        val objectId = writer2.writeObject(data)
                        newIds.add(objectId to data)
                        delay(5)
                    }
                    writer2.flush()
                    writer2.close()
                    newIds
                }

                // 5 coroutines reading the pre-written objects concurrently
                val readJobs = preWrittenObjects.map { (objectId, expectedData) ->
                    async {
                        val readBack = repo.readObject(objectId)
                        assertThat(readBack).isEqualTo(expectedData)
                        readBack
                    }
                }

                // All reads should succeed with correct data
                val readResults = readJobs.awaitAll()
                assertThat(readResults).hasSize(5)

                // The write should also complete successfully
                val newObjects = writeJob.await()
                assertThat(newObjects).hasSize(5)

                // After refresh, newly written objects should also be readable
                repo.refresh()
                for ((objectId, expectedData) in newObjects) {
                    val readBack = repo.readObject(objectId)
                    assertThat(readBack).isEqualTo(expectedData)
                }
            }
        }
    }

    @Nested
    @DisplayName("Concurrent Data Integrity")
    inner class ConcurrentDataIntegrity {

        @Test
        fun `should handle concurrent manifest operations`() = runTest {
            val (createdRepo, _) = TestRepositoryFactory.createInMemory()
            repo = createdRepo

            // Two writers writing objects concurrently -- verify they don't corrupt
            // each other's data
            val writerCount = 2
            val objectsPerWriter = 5

            val allObjectIds = mutableListOf<Pair<ObjectId, ByteArray>>()
            val lock = Any()

            coroutineScope {
                val jobs = (0 until writerCount).map { writerIndex ->
                    async {
                        val writer = repo.newDirectWriter()
                        val localIds = mutableListOf<Pair<ObjectId, ByteArray>>()

                        for (objIndex in 0 until objectsPerWriter) {
                            val data = "manifest-writer-$writerIndex-obj-$objIndex".toByteArray()
                            val objectId = writer.writeObject(data)
                            localIds.add(objectId to data)
                        }
                        writer.flush()
                        writer.close()
                        localIds
                    }
                }

                val results = jobs.awaitAll()
                for (result in results) {
                    synchronized(lock) {
                        allObjectIds.addAll(result)
                    }
                }
            }

            repo.refresh()

            assertThat(allObjectIds).hasSize(writerCount * objectsPerWriter)
            for ((objectId, expectedData) in allObjectIds) {
                val readBack = repo.readObject(objectId)
                assertThat(readBack).isEqualTo(expectedData)
            }
        }

        @Test
        fun `should not corrupt data under concurrent access`() = runTest {
            val (createdRepo, _) = TestRepositoryFactory.createInMemory()
            repo = createdRepo

            val writerCount = 5
            val objectsPerWriter = 10

            val allObjectIds = mutableListOf<Pair<ObjectId, ByteArray>>()
            val lock = Any()

            coroutineScope {
                val jobs = (0 until writerCount).map { writerIndex ->
                    async {
                        val writer = repo.newDirectWriter()
                        val localIds = mutableListOf<Pair<ObjectId, ByteArray>>()

                        for (objIndex in 0 until objectsPerWriter) {
                            val data = "writer-$writerIndex-object-$objIndex".toByteArray()
                            val objectId = writer.writeObject(data)
                            localIds.add(objectId to data)
                        }
                        writer.flush()
                        writer.close()
                        localIds
                    }
                }

                val results = jobs.awaitAll()
                for (result in results) {
                    synchronized(lock) {
                        allObjectIds.addAll(result)
                    }
                }
            }

            repo.refresh()

            // All 50 objects must be readable and have exactly the expected content
            assertThat(allObjectIds).hasSize(writerCount * objectsPerWriter)
            for ((objectId, expectedData) in allObjectIds) {
                val readBack = repo.readObject(objectId)
                assertThat(readBack).isEqualTo(expectedData)
            }
        }
    }
}

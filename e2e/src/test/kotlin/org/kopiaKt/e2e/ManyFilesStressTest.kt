package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.testutil.TestRepositoryFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * Stress tests that exercise the repository with large numbers of objects
 * to verify scalability of the pack index, content manager, and object manager.
 */
@DisplayName("Many Files Stress Tests")
class ManyFilesStressTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
        repo = null
    }

    @Nested
    @DisplayName("Object Count Scalability")
    inner class ObjectCountScalability {

        @Test
        @DisplayName("Should write and read 1000 objects")
        fun `should write and read 1000 objects`() = runTest(timeout = 3.minutes) {
            val count = 1000
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val contents = (0 until count).map { i -> "object-$i-data".toByteArray() }

            val writer = repository.newDirectWriter()
            val objectIds = contents.map { data -> writer.writeObject(data) }
            writer.flush()
            repository.refresh()

            for (i in 0 until count) {
                val restored = repository.readObject(objectIds[i])
                assertThat(restored).isEqualTo(contents[i])
            }
        }

        @Test
        @Tag("slow")
        @DisplayName("Should write and read 5000 objects")
        fun `should write and read 5000 objects`() = runTest(timeout = 10.minutes) {
            val count = 5000
            val flushInterval = 1000
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val contents = (0 until count).map { i -> "object-$i-data-payload".toByteArray() }
            val objectIds = mutableListOf<ObjectId>()

            val writer = repository.newDirectWriter()
            for (i in 0 until count) {
                objectIds.add(writer.writeObject(contents[i]))
                if ((i + 1) % flushInterval == 0) {
                    writer.flush()
                }
            }
            writer.flush()
            repository.refresh()

            // Verify all objects are readable and correct
            for (i in 0 until count) {
                val restored = repository.readObject(objectIds[i])
                assertThat(restored).isEqualTo(contents[i])
            }
        }
    }

    @Nested
    @DisplayName("Deep Nesting")
    inner class DeepNesting {

        @Test
        @DisplayName("Should handle 50 levels of object nesting")
        fun `should handle 50 levels of object nesting`() = runTest(timeout = 3.minutes) {
            val depth = 50
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()

            // Write 50 objects where each object's content references the next level.
            // Level 0 is the leaf; level N contains "level-N->objectId(N-1)".
            val objectIds = mutableListOf<ObjectId>()

            // Write leaf object first
            val leafContent = "leaf-level-0".toByteArray()
            objectIds.add(writer.writeObject(leafContent))

            // Build up from level 1 to level 49
            for (level in 1 until depth) {
                val previousId = objectIds[level - 1]
                val content = "level-$level->$previousId".toByteArray()
                objectIds.add(writer.writeObject(content))
            }

            writer.flush()
            repository.refresh()

            // Verify all 50 levels are readable
            val restoredLeaf = repository.readObject(objectIds[0])
            assertThat(restoredLeaf).isEqualTo(leafContent)

            for (level in 1 until depth) {
                val restored = repository.readObject(objectIds[level])
                val expectedContent = "level-$level->${objectIds[level - 1]}".toByteArray()
                assertThat(restored).isEqualTo(expectedContent)
            }
        }
    }

    @Nested
    @DisplayName("Index Scalability")
    inner class IndexScalability {

        @Test
        @DisplayName("Should handle 5000 index entries with random sample verification")
        fun `should handle 5000 index entries`() = runTest(timeout = 10.minutes) {
            val count = 5000
            val sampleSize = 50
            val flushInterval = 1000
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val contents = (0 until count).map { i -> "idx-entry-$i-content".toByteArray() }
            val objectIds = mutableListOf<ObjectId>()

            val writer = repository.newDirectWriter()
            for (i in 0 until count) {
                objectIds.add(writer.writeObject(contents[i]))
                if ((i + 1) % flushInterval == 0) {
                    writer.flush()
                }
            }
            writer.flush()
            repository.refresh()

            // Sample-verify: pick 50 random indices and confirm data integrity
            val random = Random(42)
            val sampledIndices = (0 until count).shuffled(random).take(sampleSize)

            for (i in sampledIndices) {
                val restored = repository.readObject(objectIds[i])
                assertThat(restored).isEqualTo(contents[i])
            }

            // Also verify first and last to ensure boundary correctness
            assertThat(repository.readObject(objectIds.first())).isEqualTo(contents.first())
            assertThat(repository.readObject(objectIds.last())).isEqualTo(contents.last())
        }
    }

    @Nested
    @DisplayName("Concurrent Writes")
    inner class ConcurrentWrites {

        @Test
        @Tag("slow")
        @DisplayName("Should handle concurrent writes of many objects from 10 coroutines")
        fun `should handle concurrent writes of many objects`() = runTest(timeout = 10.minutes) {
            val writerCount = 10
            val objectsPerWriter = 100
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            // Each coroutine gets its own writer and writes 100 objects.
            // Collect all (objectId, expectedContent) pairs.
            data class Written(val objectId: ObjectId, val content: ByteArray)

            val allWritten: List<List<Written>> = coroutineScope {
                (0 until writerCount).map { writerIndex ->
                    async {
                        val writer = repository.newDirectWriter()
                        val written = (0 until objectsPerWriter).map { objIndex ->
                            val content = "writer-$writerIndex-obj-$objIndex".toByteArray()
                            val objectId = writer.writeObject(content)
                            Written(objectId, content)
                        }
                        writer.flush()
                        written
                    }
                }.awaitAll()
            }

            repository.refresh()

            // Verify all 1000 objects are readable with correct content
            val flatWritten = allWritten.flatten()
            assertThat(flatWritten).hasSize(writerCount * objectsPerWriter)

            for (entry in flatWritten) {
                val restored = repository.readObject(entry.objectId)
                assertThat(restored).isEqualTo(entry.content)
            }
        }
    }
}

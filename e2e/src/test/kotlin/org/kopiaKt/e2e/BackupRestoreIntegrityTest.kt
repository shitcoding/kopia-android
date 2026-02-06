package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.testutil.LargeDataGenerator
import org.kopiaKt.core.testutil.TestRepositoryFactory
import kotlin.time.Duration.Companion.minutes

/**
 * End-to-end tests verifying that diverse data patterns survive a write/read
 * round-trip through the repository with byte-for-byte equality.
 *
 * Uses object-level write/read (not the full snapshot pipeline) to validate
 * content integrity, size preservation, and multi-object handling.
 */
class BackupRestoreIntegrityTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
        repo = null
    }

    @Nested
    @DisplayName("Content Integrity")
    inner class ContentIntegrity {

        @Test
        fun `should preserve all file content through write and read`() = runTest(timeout = 2.minutes) {
            val objects = mapOf(
                "plain-text" to "Hello, Kopia! This is a plain text payload.".toByteArray(),
                "unicode-text" to "\u00E9\u00E0\u00FC \u4F60\u597D \uD83D\uDE80 \u0410\u0411\u0412".toByteArray(Charsets.UTF_8),
                "empty" to ByteArray(0),
                "single-byte" to byteArrayOf(0x42),
                "all-zeros" to ByteArray(1024),
                "all-ones" to ByteArray(1024) { 0xFF.toByte() },
                "binary-random-small" to LargeDataGenerator.generate(512, seed = 1L),
                "binary-random-medium" to LargeDataGenerator.generate(8192, seed = 2L),
                "binary-random-large" to LargeDataGenerator.generate(65536, seed = 3L),
                "newlines-only" to "\n\n\n\r\n\r\n".toByteArray(),
                "null-terminated" to byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x00),
                "high-entropy" to LargeDataGenerator.generate(4096, seed = 99L)
            )

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(objects)
            repo = repository

            for ((key, originalData) in objects) {
                val restored = repository.readObject(objectIds.getValue(key))
                assertArrayEquals(
                    originalData,
                    restored,
                    "Content mismatch for object '$key'"
                )
            }
        }

        @Test
        fun `should preserve empty data through write and read`() = runTest {
            val emptyData = ByteArray(0)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                mapOf("empty-object" to emptyData)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("empty-object"))

            assertArrayEquals(emptyData, restored, "Empty object should remain empty after round-trip")
            assertThat(restored).hasLength(0)
        }

        @Test
        fun `should preserve binary content with all byte values`() = runTest {
            val allBytes = ByteArray(256) { it.toByte() }

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                mapOf("all-byte-values" to allBytes)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("all-byte-values"))

            assertArrayEquals(allBytes, restored, "All 256 byte values must survive round-trip")
            assertThat(restored).hasLength(256)
        }
    }

    @Nested
    @DisplayName("Size Preservation")
    inner class SizePreservation {

        @Test
        fun `should preserve exact file sizes`() = runTest(timeout = 2.minutes) {
            val sizes = listOf(1, 100, 4096, 65536, 1_048_576)
            val objects = sizes.associate { size ->
                "size-$size" to LargeDataGenerator.generate(size, seed = size.toLong())
            }

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(objects)
            repo = repository

            for ((key, originalData) in objects) {
                val restored = repository.readObject(objectIds.getValue(key))
                assertThat(restored.size)
                    .isEqualTo(originalData.size)
                assertArrayEquals(
                    originalData,
                    restored,
                    "Byte-level mismatch for object '$key' (size=${originalData.size})"
                )
            }
        }

        @Test
        fun `should preserve file sizes at splitter boundaries`() = runTest(timeout = 2.minutes) {
            val oneMb = 1_048_576
            val boundarySizes = listOf(oneMb - 1, oneMb, oneMb + 1)
            val objects = boundarySizes.associate { size ->
                "boundary-$size" to LargeDataGenerator.generate(size, seed = size.toLong())
            }

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(objects)
            repo = repository

            for ((key, originalData) in objects) {
                val restored = repository.readObject(objectIds.getValue(key))
                assertThat(restored.size)
                    .isEqualTo(originalData.size)
                assertArrayEquals(
                    originalData,
                    restored,
                    "Byte-level mismatch for object '$key' at splitter boundary (size=${originalData.size})"
                )
            }
        }
    }

    @Nested
    @DisplayName("Multiple Object Integrity")
    inner class MultipleObjectIntegrity {

        @Test
        fun `should preserve 50 objects with distinct content`() = runTest(timeout = 2.minutes) {
            val objects = (1..50).associate { i ->
                "obj-$i" to LargeDataGenerator.generate(1024 * i, seed = i.toLong())
            }

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(objects)
            repo = repository

            for ((key, originalData) in objects) {
                val restored = repository.readObject(objectIds.getValue(key))
                assertArrayEquals(
                    originalData,
                    restored,
                    "Content mismatch for object '$key'"
                )
            }
        }

        @Test
        fun `should preserve objects across different write sessions`() = runTest(timeout = 2.minutes) {
            val batch1 = (1..5).associate { i ->
                "batch1-obj-$i" to LargeDataGenerator.generate(2048 * i, seed = i.toLong())
            }
            val batch2 = (1..5).associate { i ->
                "batch2-obj-$i" to LargeDataGenerator.generate(2048 * i, seed = (i + 100).toLong())
            }

            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            // Batch 1
            val writer1 = repository.newDirectWriter()
            val ids1 = mutableMapOf<String, ObjectId>()
            for ((key, data) in batch1) {
                ids1[key] = writer1.writeObject(data)
            }
            writer1.flush()
            writer1.close()

            // Batch 2
            val writer2 = repository.newDirectWriter()
            val ids2 = mutableMapOf<String, ObjectId>()
            for ((key, data) in batch2) {
                ids2[key] = writer2.writeObject(data)
            }
            writer2.flush()
            writer2.close()

            repository.refresh()

            // Verify batch 1
            for ((key, originalData) in batch1) {
                val restored = repository.readObject(ids1.getValue(key))
                assertArrayEquals(
                    originalData,
                    restored,
                    "Content mismatch for batch 1 object '$key'"
                )
            }

            // Verify batch 2
            for ((key, originalData) in batch2) {
                val restored = repository.readObject(ids2.getValue(key))
                assertArrayEquals(
                    originalData,
                    restored,
                    "Content mismatch for batch 2 object '$key'"
                )
            }
        }
    }
}

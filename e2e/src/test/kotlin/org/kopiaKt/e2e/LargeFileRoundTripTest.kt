package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.testutil.LargeDataGenerator
import org.kopiaKt.core.testutil.TestRepositoryFactory
import kotlin.time.Duration.Companion.minutes

/**
 * Large file round-trip tests that exercise the splitter, compression,
 * and pack boundaries at various data sizes.
 *
 * Each test writes data through TestRepositoryFactory, reads it back via
 * readObject, and compares SHA-256 hashes to verify data integrity.
 */
class LargeFileRoundTripTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
        repo = null
    }

    @Nested
    @DisplayName("Standard Sizes")
    inner class StandardSizes {

        @Test
        @DisplayName("Should round-trip 10MB file with SHA-256 verification")
        fun `should round-trip 10MB file with SHA-256 verification`() = runTest(timeout = 3.minutes) {
            val size = 10 * 1024 * 1024
            val original = LargeDataGenerator.generate(size, seed = 10L)
            val originalHash = LargeDataGenerator.sha256(original)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                objects = mapOf("10mb" to original)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("10mb"))
            val restoredHash = LargeDataGenerator.sha256(restored)

            assertThat(restored.size).isEqualTo(size)
            assertThat(restoredHash).isEqualTo(originalHash)
        }
    }

    @Nested
    @DisplayName("Splitter Boundary")
    inner class SplitterBoundary {

        @Test
        @DisplayName("Should round-trip file exactly at splitter boundary (1MB)")
        fun `should round-trip file exactly at splitter boundary`() = runTest(timeout = 1.minutes) {
            val size = 1_048_576 // exactly 1MB, FIXED-1M splitter default
            val original = LargeDataGenerator.generate(size, seed = 100L)
            val originalHash = LargeDataGenerator.sha256(original)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                objects = mapOf("1mb-exact" to original)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("1mb-exact"))
            val restoredHash = LargeDataGenerator.sha256(restored)

            assertThat(restored.size).isEqualTo(size)
            assertThat(restoredHash).isEqualTo(originalHash)
        }

        @Test
        @DisplayName("Should round-trip file at splitter boundary plus one byte")
        fun `should round-trip file at splitter boundary plus one byte`() = runTest(timeout = 1.minutes) {
            val size = 1_048_577 // 1MB + 1 byte
            val original = LargeDataGenerator.generate(size, seed = 101L)
            val originalHash = LargeDataGenerator.sha256(original)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                objects = mapOf("1mb-plus-1" to original)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("1mb-plus-1"))
            val restoredHash = LargeDataGenerator.sha256(restored)

            assertThat(restored.size).isEqualTo(size)
            assertThat(restoredHash).isEqualTo(originalHash)
        }
    }

    @Nested
    @DisplayName("Compression Behavior")
    inner class CompressionBehavior {

        @Test
        @Tag("slow")
        @DisplayName("Should handle file with highly compressible content at 50MB")
        fun `should handle file with highly compressible content at 50MB`() = runTest(timeout = 5.minutes) {
            val size = 50 * 1024 * 1024
            val original = ByteArray(size) // all zeros -- highly compressible
            val originalHash = LargeDataGenerator.sha256(original)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                objects = mapOf("50mb-compressible" to original)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("50mb-compressible"))
            val restoredHash = LargeDataGenerator.sha256(restored)

            assertThat(restored.size).isEqualTo(size)
            assertThat(restoredHash).isEqualTo(originalHash)
        }

        @Test
        @Tag("slow")
        @DisplayName("Should handle file with incompressible content at 50MB")
        fun `should handle file with incompressible content at 50MB`() = runTest(timeout = 5.minutes) {
            val size = 50 * 1024 * 1024
            val original = LargeDataGenerator.generate(size, seed = 999L) // random data, incompressible
            val originalHash = LargeDataGenerator.sha256(original)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                objects = mapOf("50mb-random" to original)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("50mb-random"))
            val restoredHash = LargeDataGenerator.sha256(restored)

            assertThat(restored.size).isEqualTo(size)
            assertThat(restoredHash).isEqualTo(originalHash)
        }
    }

    @Nested
    @DisplayName("Stress Sizes")
    inner class StressSizes {

        @Test
        @Tag("slow")
        @DisplayName("Should round-trip 50MB file with SHA-256 verification")
        fun `should round-trip 50MB file with SHA-256 verification`() = runTest(timeout = 5.minutes) {
            val size = 50 * 1024 * 1024
            val original = LargeDataGenerator.generate(size, seed = 50L)
            val originalHash = LargeDataGenerator.sha256(original)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                objects = mapOf("50mb" to original)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("50mb"))
            val restoredHash = LargeDataGenerator.sha256(restored)

            assertThat(restored.size).isEqualTo(size)
            assertThat(restoredHash).isEqualTo(originalHash)
        }

        @Test
        @Tag("slow")
        @DisplayName("Should round-trip 100MB file with SHA-256 verification")
        fun `should round-trip 100MB file with SHA-256 verification`() = runTest(timeout = 10.minutes) {
            val size = 100 * 1024 * 1024
            val original = LargeDataGenerator.generate(size, seed = 100L)
            val originalHash = LargeDataGenerator.sha256(original)

            val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
                objects = mapOf("100mb" to original)
            )
            repo = repository

            val restored = repository.readObject(objectIds.getValue("100mb"))
            val restoredHash = LargeDataGenerator.sha256(restored)

            assertThat(restored.size).isEqualTo(size)
            assertThat(restoredHash).isEqualTo(originalHash)
        }
    }
}

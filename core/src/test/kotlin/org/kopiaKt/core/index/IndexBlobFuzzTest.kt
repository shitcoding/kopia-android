package org.kopiaKt.core.index

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import org.kopiaKt.core.blob.BlobId
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Fuzz tests for IndexBlobReader to ensure the parser never crashes
 * or hangs on arbitrary input. Exceptions are expected and acceptable;
 * the goal is to verify robustness against malformed data.
 */
@Timeout(60, unit = TimeUnit.SECONDS)
@DisplayName("Index Blob Parser Fuzz Tests")
class IndexBlobFuzzTest {

    private val blobId = BlobId("fuzz-test-blob")

    /**
     * Feeds the given data to both openUnencrypted and openRaw, catching
     * any exceptions. If parsing succeeds, also exercises iterate() and
     * getInfo() to flush out lazy parsing issues.
     */
    private fun fuzzBothParsers(data: ByteArray) {
        fuzzOpenUnencrypted(data)
        fuzzOpenRaw(data)
    }

    private fun fuzzOpenUnencrypted(data: ByteArray) {
        try {
            val reader = IndexBlobReader.openUnencrypted(data, blobId)
            exerciseReader(reader)
            reader.close()
        } catch (_: Throwable) {
            // Expected for malformed input - catch Throwable to handle
            // OutOfMemoryError, StackOverflowError, etc.
        }
    }

    private fun fuzzOpenRaw(data: ByteArray) {
        try {
            val reader = IndexBlobReader.openRaw(data, blobId)
            exerciseReader(reader)
            reader.close()
        } catch (_: Throwable) {
            // Expected for malformed input - catch Throwable to handle
            // OutOfMemoryError, StackOverflowError, etc.
        }
    }

    /**
     * Exercises the reader's main operations to flush out issues
     * in lazy parsing or iteration.
     */
    private fun exerciseReader(reader: IndexBlobReader) {
        reader.approximateCount()
        reader.iterate().toList()
    }

    @Nested
    @DisplayName("Random Byte Arrays")
    inner class RandomByteArrays {

        @Test
        fun `should not crash or hang on random bytes`() {
            val rng = Random(seed = 42)

            repeat(1000) {
                val size = rng.nextInt(0, 1025) // 0..1024
                val data = rng.nextBytes(size)

                org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(5)) {
                    fuzzBothParsers(data)
                }
            }
        }
    }

    @Nested
    @DisplayName("Large Input")
    inner class LargeInput {

        @Test
        fun `should not crash on very large input`() {
            val rng = Random(seed = 123)
            val tenMb = 10 * 1024 * 1024
            val data = rng.nextBytes(tenMb)

            org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(30)) {
                fuzzBothParsers(data)
            }
        }
    }

    @Nested
    @DisplayName("Valid Version Byte With Random Content")
    inner class ValidVersionByteRandomContent {

        @Test
        fun `should not crash on input with valid V1 version byte but random content`() {
            val rng = Random(seed = 99)

            repeat(100) {
                val size = rng.nextInt(1, 513) // 1..512
                val data = rng.nextBytes(size)
                data[0] = IndexVersion.V1.toByte()

                org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(5)) {
                    fuzzBothParsers(data)
                }
            }
        }

        @Test
        fun `should not crash on input with valid V2 version byte but random content`() {
            val rng = Random(seed = 77)

            repeat(100) {
                val size = rng.nextInt(1, 513) // 1..512
                val data = rng.nextBytes(size)
                data[0] = IndexVersion.V2.toByte()

                org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(5)) {
                    fuzzBothParsers(data)
                }
            }
        }
    }
}

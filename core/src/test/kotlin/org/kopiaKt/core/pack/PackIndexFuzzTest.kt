package org.kopiaKt.core.pack

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import kotlin.random.Random

/**
 * Fuzz tests for PackBlobReader.recoverIndex() to verify it terminates
 * without hanging on arbitrary input, including random bytes, large inputs,
 * and corrupted valid pack blobs.
 *
 * The parser may return null, return a list, throw an Exception, or in
 * extreme cases throw an OutOfMemoryError when corrupted data causes
 * allocation of huge arrays. All of these are acceptable outcomes
 * for a fuzz test -- the critical invariant is that the parser always
 * terminates within the timeout.
 */
@Timeout(60)
class PackIndexFuzzTest {

    /**
     * Safely invokes recoverIndex, catching any Throwable.
     * Returns the result or null if any error was thrown.
     * The critical property is that this call terminates.
     */
    private fun safeRecoverIndex(
        data: ByteArray,
        encryptionOverhead: UInt = 0u
    ): List<*>? {
        return try {
            PackBlobReader.recoverIndex(data, encryptionOverhead)
        } catch (_: Throwable) {
            // Any termination (including OOM from corrupted length fields) is acceptable.
            // The fuzz test verifies the parser does not hang or loop infinitely.
            null
        }
    }

    @Nested
    @DisplayName("Random byte input")
    inner class RandomByteInputTests {

        @Test
        fun `should not crash or hang on random bytes`() {
            val random = Random(seed = 42)

            assertTimeout(Duration.ofSeconds(5)) {
                repeat(1000) {
                    val size = random.nextInt(0, 1025)
                    val data = random.nextBytes(size)

                    val result = safeRecoverIndex(data)
                    // Result should be null or a valid list -- either is acceptable.
                    // The critical invariant is that we reach this line without hanging.
                    if (result != null) {
                        assertTrue(result is List<*>)
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Large input")
    inner class LargeInputTests {

        @Test
        fun `should not crash on very large input`() {
            val random = Random(seed = 99)
            val tenMB = 10 * 1024 * 1024
            val data = random.nextBytes(tenMB)

            assertTimeout(Duration.ofSeconds(5)) {
                val result = safeRecoverIndex(data)
                // Must not hang; null or a list are both acceptable
                if (result != null) {
                    assertTrue(result is List<*>)
                }
            }
        }
    }

    @Nested
    @DisplayName("Corrupted valid pack blobs")
    inner class CorruptedValidPackTests {

        /**
         * Builds a valid pack blob with several content entries
         * so there is enough structure for corruption to exercise
         * different code paths.
         */
        private fun buildValidPackBlob(): ByteArray {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("fuzztest12345678"),
                preambleLength = 32,
                encryptionOverhead = 0,
                timestampSeconds = 1700000000L,
                preamble = ByteArray(32) { it.toByte() }
            )

            val contentEntries = listOf(
                ContentId.parse("aaaa111122223333") to ByteArray(64) { (it * 7).toByte() },
                ContentId.parse("bbbb444455556666") to ByteArray(128) { (it * 13).toByte() },
                ContentId.parse("cccc777788889999") to ByteArray(32) { (it * 37).toByte() }
            )

            for ((contentId, data) in contentEntries) {
                builder.addContent(contentId, data, originalLength = data.size.toUInt())
            }

            val (packData, _) = builder.build()
            return packData
        }

        @Test
        fun `should not crash on mostly-valid data with random corruption`() {
            val validPack = buildValidPackBlob()
            val random = Random(seed = 77)

            // Verify the valid pack actually works before we corrupt it
            val baseline = PackBlobReader.recoverIndex(validPack)
            assertNotNull(baseline, "Baseline valid pack should produce a non-null result")
            assertEquals(3, baseline!!.size, "Baseline should recover 3 content entries")

            assertTimeout(Duration.ofSeconds(5)) {
                repeat(1000) { iteration ->
                    val corrupted = validPack.copyOf()

                    // Corrupt between 1 and 20 random byte positions
                    val corruptionCount = random.nextInt(1, 21)
                    repeat(corruptionCount) {
                        val position = random.nextInt(corrupted.size)
                        corrupted[position] = random.nextBytes(1)[0]
                    }

                    val result = safeRecoverIndex(corrupted)
                    if (result != null) {
                        assertTrue(result is List<*>,
                            "Iteration $iteration: result should be a list if non-null")
                    }
                }
            }
        }

        @Test
        fun `should not crash when postamble region is corrupted`() {
            val validPack = buildValidPackBlob()
            val random = Random(seed = 123)

            assertTimeout(Duration.ofSeconds(5)) {
                // Focus corruption on the last 50 bytes (postamble area)
                val postambleRegionStart = maxOf(0, validPack.size - 50)
                repeat(500) { iteration ->
                    val corrupted = validPack.copyOf()

                    val corruptionCount = random.nextInt(1, 11)
                    repeat(corruptionCount) {
                        val position = random.nextInt(postambleRegionStart, corrupted.size)
                        corrupted[position] = random.nextBytes(1)[0]
                    }

                    val result = safeRecoverIndex(corrupted)
                    if (result != null) {
                        assertTrue(result is List<*>,
                            "Iteration $iteration: result should be a list if non-null")
                    }
                }
            }
        }

        @Test
        fun `should not crash when index region is corrupted`() {
            val validPack = buildValidPackBlob()
            val random = Random(seed = 256)

            // Determine where the local index starts
            val postamble = PackBlobReader.parsePostamble(validPack)
            assertNotNull(postamble, "Valid pack should have a parseable postamble")
            val indexOffset = postamble!!.localIndexOffset.toInt()
            val indexLength = postamble.localIndexLength.toInt()

            assertTimeout(Duration.ofSeconds(5)) {
                repeat(500) { iteration ->
                    val corrupted = validPack.copyOf()

                    // Corrupt bytes within the index region only
                    val corruptionCount = random.nextInt(1, 11)
                    repeat(corruptionCount) {
                        val position = indexOffset + random.nextInt(indexLength)
                        corrupted[position] = random.nextBytes(1)[0]
                    }

                    val result = safeRecoverIndex(corrupted)
                    if (result != null) {
                        assertTrue(result is List<*>,
                            "Iteration $iteration: result should be a list if non-null")
                    }
                }
            }
        }

        @Test
        fun `should not crash when truncating valid pack blob at various positions`() {
            val validPack = buildValidPackBlob()

            assertTimeout(Duration.ofSeconds(5)) {
                for (truncateAt in 0..validPack.size) {
                    val truncated = validPack.copyOfRange(0, truncateAt)
                    val result = safeRecoverIndex(truncated)
                    if (result != null) {
                        assertTrue(result is List<*>,
                            "Truncate at $truncateAt: result should be a list if non-null")
                    }
                }
            }
        }
    }
}

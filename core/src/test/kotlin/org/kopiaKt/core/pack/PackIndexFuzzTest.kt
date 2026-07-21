package org.kopiaKt.core.pack

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.random.Random

/**
 * Fuzz tests for PackBlobReader.recoverIndex() to verify it terminates
 * without hanging on arbitrary input, including random bytes, large inputs,
 * and corrupted valid pack blobs.
 *
 * The parser may return null, return a list, or throw an Exception.
 * All of these are acceptable outcomes for a fuzz test -- the critical
 * invariant is that the parser always terminates within the timeout
 * without OOM or infinite loops.
 */
@Timeout(60)
class PackIndexFuzzTest {

    /**
     * Safely invokes recoverIndex, catching any Exception.
     * Returns the result or null if any error was thrown.
     * The critical property is that this call terminates without OOM.
     */
    private fun safeRecoverIndex(
        data: ByteArray,
        encryptionOverhead: UInt = 0u,
    ): List<*>? = try {
        PackBlobReader.recoverIndex(data, encryptionOverhead)
    } catch (_: Exception) {
        null
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
                preamble = ByteArray(32) { it.toByte() },
            )

            val contentEntries = listOf(
                ContentId.parse("aaaa111122223333") to ByteArray(64) { (it * 7).toByte() },
                ContentId.parse("bbbb444455556666") to ByteArray(128) { (it * 13).toByte() },
                ContentId.parse("cccc777788889999") to ByteArray(32) { (it * 37).toByte() },
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
                        assertTrue(
                            result is List<*>,
                            "Iteration $iteration: result should be a list if non-null",
                        )
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
                        assertTrue(
                            result is List<*>,
                            "Iteration $iteration: result should be a list if non-null",
                        )
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
                        assertTrue(
                            result is List<*>,
                            "Iteration $iteration: result should be a list if non-null",
                        )
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
                        assertTrue(
                            result is List<*>,
                            "Truncate at $truncateAt: result should be a list if non-null",
                        )
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Bounds check validation")
    inner class BoundsCheckTests {

        /**
         * Builds a V1 index header with the given parameters.
         */
        private fun buildV1Header(
            version: Int = 1,
            keySize: Int = 17,
            entrySize: Int = 20,
            entryCount: Int = 0,
        ): ByteArray {
            val header = ByteArray(8)
            header[0] = version.toByte()
            header[1] = keySize.toByte()
            ByteBuffer.wrap(header, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(entrySize.toShort())
            ByteBuffer.wrap(header, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(entryCount)
            return header
        }

        @Test
        fun `V1 should reject entryCount exceeding data capacity`() {
            // Header claims 1000 entries but data is only 8 bytes (header only)
            val data = buildV1Header(entryCount = 1000)
            assertThrows<IllegalArgumentException> {
                PackIndexV1.open(data, 0u)
            }
        }

        @Test
        fun `V1 should reject entrySize smaller than ENTRY_SIZE`() {
            // Header claims entrySize=5 (less than required 20), with 1 entry
            // Need enough data for the stride: keySize(17) + entrySize(5) = 22 bytes after header
            val data = buildV1Header(entrySize = 5, entryCount = 1) + ByteArray(30)
            assertThrows<IllegalArgumentException> {
                PackIndexV1.open(data, 0u)
            }
        }

        @Test
        fun `V1 should accept valid header with matching data size`() {
            // Header claims 1 entry, stride=37, need at least 8+37=45 bytes
            val data = buildV1Header(entryCount = 1) + ByteArray(37)
            assertDoesNotThrow {
                PackIndexV1.open(data, 0u)
            }
        }

        @Test
        fun `V1 should reject keySize exceeding max for a populated index`() {
            // keySize=200 (> MAX_KEY_SIZE 33) with entries -> corrupt header, iterate would slice
            // garbage content IDs. Provide enough bytes so parseHeader (not the capacity check) fails.
            val stride = 200 + PackIndexV1.ENTRY_SIZE
            val data = buildV1Header(keySize = 200, entryCount = 1) + ByteArray(stride)
            assertThrows<IllegalArgumentException> {
                PackIndexV1.open(data, 0u)
            }
        }

        @Test
        fun `V1 should accept the empty-index sentinel keySize`() {
            // The empty index is written with the keySize 0xFF sentinel and entryCount 0; the
            // upper-bound check must not reject it.
            val emptyIndex = PackIndexV1.build(emptyList())
            assertDoesNotThrow {
                PackIndexV1.open(emptyIndex, 0u)
            }
        }

        @Test
        fun `V2 should reject zero keySize and entrySize with nonzero entryCount`() {
            // V2 header with keySize=0, entrySize=0, entryCount=1000 -> stride=0, CPU DoS
            val data = ByteArray(17)
            data[0] = 2 // V2 version
            data[1] = 0 // keySize = 0
            // entrySize = 0 (bytes 2-3 already 0)
            ByteBuffer.wrap(data, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(1000) // entryCount
            // packCount = 0 (bytes 8-11 already 0)
            assertThrows<IllegalArgumentException> {
                PackIndexV2.open(data)
            }
        }

        @Test
        fun `V2 should reject packCount exceeding data capacity`() {
            val data = ByteArray(17)
            data[0] = 2 // V2 version
            data[1] = 17 // keySize
            ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(16) // entrySize
            // entryCount = 0
            ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(1000000) // packCount
            assertThrows<IllegalArgumentException> {
                PackIndexV2.open(data)
            }
        }

        @Test
        fun `V2 should accept zero entryCount with zero stride`() {
            // Zero entries with zero stride is valid (empty index)
            val data = ByteArray(17)
            data[0] = 2 // V2 version
            data[1] = 0 // keySize = 0
            // All other fields 0 - empty index
            assertDoesNotThrow {
                PackIndexV2.open(data)
            }
        }

        @Test
        fun `V2 should reject a truncated format region`() {
            // Header declares 10 format infos but there is no room for them after the (empty)
            // entry/pack regions -> must fail instead of silently defaulting the format metadata.
            val data = ByteArray(PackIndexV2.HEADER_SIZE)
            data[0] = 2 // V2 version
            data[1] = 17 // keySize
            ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(16) // entrySize
            // entryCount = 0, packCount = 0
            data[12] = 10 // numFormatInfos, but no bytes follow the header for them
            assertThrows<IllegalArgumentException> {
                PackIndexV2.open(data)
            }
        }

        @Test
        fun `V2 should not crash on a pack-blob nameOffset that overflows Int`() {
            // packCount=1 with nameOffset = Int.MAX. Int arithmetic in the bounds check would overflow
            // to a negative value, pass `<= data.size`, and crash String(); Long arithmetic must
            // reject it and yield an empty name instead.
            val data = ByteArray(PackIndexV2.HEADER_SIZE + PackIndexV2.PACK_INFO_SIZE)
            data[0] = 2 // V2 version
            data[1] = 17 // keySize
            ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(16) // entrySize
            // entryCount = 0
            ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(1) // packCount = 1
            // numFormatInfos = 0
            val packInfoOffset = PackIndexV2.HEADER_SIZE
            data[packInfoOffset] = 255.toByte() // nameLength
            ByteBuffer.wrap(data, packInfoOffset + 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(Int.MAX_VALUE)
            assertDoesNotThrow {
                PackIndexV2.open(data)
            }
        }
    }
}

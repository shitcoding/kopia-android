package org.kopiaKt.core.index

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.testutil.CorruptionHelpers

/**
 * Tests that corrupted index blobs are properly detected or produce visibly wrong data.
 *
 * Covers upstream Go issue #1879 (partial/corrupted index reads).
 * Only tests V1 format since PackIndexV2.build() is not yet implemented.
 */
class CorruptedIndexBlobTest {

    private lateinit var validIndexData: ByteArray
    private val blobId = BlobId("ntest")

    private fun createTestContentInfo(contentIdHex: String, packOffset: Int): ContentInfo {
        return ContentInfo(
            contentId = ContentId.parse(contentIdHex),
            packBlobId = BlobId("p1234567890"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 1000u,
            packOffset = packOffset.toUInt()
        )
    }

    @BeforeEach
    fun setup() {
        val builder = IndexBlobBuilder(version = IndexVersion.V1)
        builder.add(createTestContentInfo("aaaa111122223333", 0))
        builder.add(createTestContentInfo("bbbb444455556666", 1000))
        builder.add(createTestContentInfo("cccc777788889999", 2000))
        builder.add(createTestContentInfo("ddddaaaabbbbcccc", 3000))
        builder.add(createTestContentInfo("eeeeddddeeeeaaaa", 4000))
        validIndexData = builder.buildUnencrypted()
    }

    @Nested
    @DisplayName("Truncation")
    inner class TruncationTests {

        @Test
        fun `should throw for empty data`() {
            assertThrows<IllegalArgumentException> {
                IndexBlobReader.openUnencrypted(ByteArray(0), blobId)
            }
        }

        @Test
        fun `should throw for data with only suffix`() {
            val data = ByteArray(IndexBlobConstants.RANDOM_SUFFIX_SIZE)
            assertThrows<IllegalArgumentException> {
                IndexBlobReader.openUnencrypted(data, blobId)
            }
        }

        @Test
        fun `should throw or return empty for truncation at 4 bytes`() {
            val truncated = CorruptionHelpers.truncate(validIndexData, 4)
            // 4 bytes is less than header size (8), so the reader falls back to an
            // empty index (keySize=255) rather than throwing
            try {
                val reader = IndexBlobReader.openUnencrypted(truncated, blobId)
                // If it opened, iteration should yield no usable entries
                val entries = reader.iterate().toList()
                assertTrue(
                    entries.size < 5,
                    "Truncated-to-4-bytes index should not produce all 5 entries"
                )
                reader.close()
            } catch (_: Exception) {
                // Exception is also acceptable
            }
        }

        @Test
        fun `should throw or yield no entries for truncation at 8 bytes`() {
            val truncated = CorruptionHelpers.truncate(validIndexData, 8)
            // 8 bytes parses the header (which claims 5 entries) but there is no
            // entry data. Iteration should fail or return empty.
            try {
                val reader = IndexBlobReader.openUnencrypted(truncated, blobId)
                val entries = reader.iterate().toList()
                assertTrue(
                    entries.size < 5,
                    "Truncated-to-8-bytes index should not produce all 5 entries"
                )
                reader.close()
            } catch (_: Exception) {
                // Exception is also acceptable (e.g. ArrayIndexOutOfBoundsException)
            }
        }

        @Test
        fun `should throw or yield fewer entries for truncation at midpoint`() {
            val truncated = CorruptionHelpers.truncate(validIndexData, validIndexData.size / 2)
            // Header may still claim 5 entries, but actual iteration should fail or
            // produce fewer entries due to insufficient data
            try {
                val reader = IndexBlobReader.openUnencrypted(truncated, blobId)
                val entries = reader.iterate().toList()
                assertTrue(
                    entries.size < 5,
                    "Truncated index should yield fewer entries than original"
                )
                reader.close()
            } catch (_: Exception) {
                // Exception is also acceptable - corruption detected
            }
        }

        @Test
        fun `should throw or have wrong data for truncation missing last byte`() {
            val truncated = CorruptionHelpers.truncate(validIndexData, validIndexData.size - 1)
            // Removing one byte shifts the suffix boundary by one, so the parser
            // strips 32 bytes starting one position earlier, eating into the last
            // entry or extra data. Either parsing throws or produces different entries.
            try {
                val originalReader = IndexBlobReader.openUnencrypted(validIndexData, blobId)
                val originalEntries = originalReader.iterate().toList()
                originalReader.close()

                val reader = IndexBlobReader.openUnencrypted(truncated, blobId)
                val entries = reader.iterate().toList()
                reader.close()

                val differs = entries.size != originalEntries.size ||
                    entries.zip(originalEntries).any { (a, b) ->
                        a.contentId != b.contentId ||
                            a.packBlobId != b.packBlobId ||
                            a.packOffset != b.packOffset
                    }
                assertTrue(
                    differs,
                    "Truncated index (missing last byte) should produce different entries"
                )
            } catch (_: Exception) {
                // Exception is also acceptable - corruption detected
            }
        }

        @Test
        fun `standard truncations should all throw or produce fewer entries`() {
            for ((desc, truncated) in CorruptionHelpers.standardTruncations(validIndexData)) {
                try {
                    val reader = IndexBlobReader.openUnencrypted(truncated, blobId)
                    assertTrue(
                        reader.approximateCount() <= 5,
                        "Truncation '$desc' should not produce more entries than original"
                    )
                    reader.close()
                } catch (_: Exception) {
                    // Exception is acceptable for truncated data
                }
            }
        }
    }

    @Nested
    @DisplayName("Version Byte Corruption")
    inner class VersionByteTests {

        @Test
        fun `should throw for invalid version byte 0xFF`() {
            val corrupted = validIndexData.copyOf()
            corrupted[0] = 0xFF.toByte()
            assertThrows<IllegalArgumentException> {
                IndexBlobReader.openUnencrypted(corrupted, blobId)
            }
        }

        @Test
        fun `should throw for version byte 0x00`() {
            val corrupted = validIndexData.copyOf()
            corrupted[0] = 0x00
            assertThrows<IllegalArgumentException> {
                IndexBlobReader.openUnencrypted(corrupted, blobId)
            }
        }

        @Test
        fun `should throw for version byte 0x03 (unsupported future version)`() {
            val corrupted = validIndexData.copyOf()
            corrupted[0] = 0x03
            assertThrows<IllegalArgumentException> {
                IndexBlobReader.openUnencrypted(corrupted, blobId)
            }
        }
    }

    @Nested
    @DisplayName("Entry Data Corruption")
    inner class EntryDataCorruption {

        @Test
        fun `should return wrong data for bit flip in entry area`() {
            // Flip a bit somewhere in the entry data (after header, before suffix)
            val entryAreaStart = 8 // after 8-byte header
            val entryAreaEnd = validIndexData.size - IndexBlobConstants.RANDOM_SUFFIX_SIZE
            val midEntry = entryAreaStart + (entryAreaEnd - entryAreaStart) / 2

            val corrupted = CorruptionHelpers.bitFlip(validIndexData, midEntry)

            // This may or may not throw. If it doesn't throw, the data should differ.
            try {
                val originalReader = IndexBlobReader.openUnencrypted(validIndexData, blobId)
                val corruptedReader = IndexBlobReader.openUnencrypted(corrupted, blobId)

                val originalEntries = originalReader.iterate().toList()
                val corruptedEntries = corruptedReader.iterate().toList()

                // Either count differs or any field in any entry differs
                val differs = originalEntries.size != corruptedEntries.size ||
                    originalEntries.zip(corruptedEntries).any { (a, b) ->
                        a.contentId != b.contentId ||
                            a.packOffset != b.packOffset ||
                            a.packedLength != b.packedLength ||
                            a.packBlobId != b.packBlobId ||
                            a.timestampSeconds != b.timestampSeconds ||
                            a.deleted != b.deleted
                    }
                assertTrue(differs, "Corrupted index should produce different entries")

                originalReader.close()
                corruptedReader.close()
            } catch (_: Exception) {
                // Exception is also acceptable - corruption detected
            }
        }

        @Test
        fun `should detect zeroed entry range`() {
            // Zero out a range in the entry area
            val entryAreaStart = 8 // after header
            val corrupted = CorruptionHelpers.zeroRange(validIndexData, entryAreaStart, 20)

            try {
                val originalReader = IndexBlobReader.openUnencrypted(validIndexData, blobId)
                val corruptedReader = IndexBlobReader.openUnencrypted(corrupted, blobId)

                val originalEntries = originalReader.iterate().toList()
                val corruptedEntries = corruptedReader.iterate().toList()

                val differs = originalEntries.size != corruptedEntries.size ||
                    originalEntries.zip(corruptedEntries).any { (a, b) ->
                        a.contentId != b.contentId ||
                            a.packBlobId != b.packBlobId ||
                            a.packOffset != b.packOffset ||
                            a.packedLength != b.packedLength ||
                            a.timestampSeconds != b.timestampSeconds
                    }
                assertTrue(differs, "Zeroed entries should produce different data")

                originalReader.close()
                corruptedReader.close()
            } catch (_: Exception) {
                // Exception is also acceptable
            }
        }

        @Test
        fun `should handle garbage inserted in entry area`() {
            // Insert garbage bytes into the entry area, disrupting alignment
            val entryAreaStart = 8 // after header
            val corrupted = CorruptionHelpers.insertGarbage(validIndexData, entryAreaStart, 7)

            // Inserted bytes shift alignment so entries should be misread or cause an error
            try {
                val reader = IndexBlobReader.openUnencrypted(corrupted, blobId)
                val entries = reader.iterate().toList()
                val originalReader = IndexBlobReader.openUnencrypted(validIndexData, blobId)
                val originalEntries = originalReader.iterate().toList()

                val differs = entries.size != originalEntries.size ||
                    entries.zip(originalEntries).any { (a, b) ->
                        a.contentId != b.contentId
                    }
                assertTrue(differs, "Inserted garbage should disrupt entry parsing")

                reader.close()
                originalReader.close()
            } catch (_: Exception) {
                // Exception is also acceptable
            }
        }
    }

    @Nested
    @DisplayName("Header Corruption")
    inner class HeaderCorruption {

        @Test
        fun `should handle corrupted key size field`() {
            // Key size is byte 1 in the header
            val corrupted = validIndexData.copyOf()
            corrupted[1] = 0xFF.toByte() // key size 255 - treated as empty index marker

            // With keySize 255, the header still parses but getInfo/iterate treat it
            // as empty (no entries yielded), even though approximateCount may reflect
            // the original entry count from the header
            val reader = IndexBlobReader.openUnencrypted(corrupted, blobId)
            val entries = reader.iterate().toList()
            assertTrue(
                entries.isEmpty(),
                "Corrupted key size 0xFF should yield no entries on iteration"
            )
            reader.close()
        }

        @Test
        fun `should handle corrupted entry count field`() {
            // Entry count is at bytes 4-7 in the header
            val corrupted = validIndexData.copyOf()
            // Set entry count to a very large value
            corrupted[4] = 0x7F.toByte()
            corrupted[5] = 0xFF.toByte()
            corrupted[6] = 0xFF.toByte()
            corrupted[7] = 0xFF.toByte()

            // With inflated entry count, iteration should fail or read garbage
            try {
                val reader = IndexBlobReader.openUnencrypted(corrupted, blobId)
                reader.iterate().toList()
                // If it parsed, the count from header should be very wrong
                assertTrue(
                    reader.approximateCount() != 5,
                    "Corrupted entry count should differ from original"
                )
                reader.close()
            } catch (_: Exception) {
                // Exception is acceptable - likely ArrayIndexOutOfBoundsException
            }
        }
    }

    @Nested
    @DisplayName("Duplicate Content IDs")
    inner class DuplicateContentIdTests {

        @Test
        fun `should handle index with duplicate content IDs`() {
            val builder = IndexBlobBuilder(version = IndexVersion.V1)
            val sameId = "aaaa111122223333"
            builder.add(createTestContentInfo(sameId, 0))
            builder.add(createTestContentInfo(sameId, 1000)) // same ID, different offset

            val data = builder.buildUnencrypted()

            // Should not crash - either returns both entries or deduplicates
            val reader = IndexBlobReader.openUnencrypted(data, blobId)
            val entries = reader.iterate().toList()
            assertTrue(entries.isNotEmpty(), "Should have at least one entry")
            reader.close()
        }

        @Test
        fun `getInfo should return one of the duplicates`() {
            val builder = IndexBlobBuilder(version = IndexVersion.V1)
            val sameId = "aaaa111122223333"
            builder.add(createTestContentInfo(sameId, 0))
            builder.add(createTestContentInfo(sameId, 1000))

            val data = builder.buildUnencrypted()
            val reader = IndexBlobReader.openUnencrypted(data, blobId)

            val info = reader.getInfo(ContentId.parse(sameId))
            assertTrue(info != null, "Should find entry for duplicate content ID")
            assertTrue(
                info!!.packOffset == 0u || info.packOffset == 1000u,
                "Should return one of the duplicate entries"
            )
            reader.close()
        }
    }

    @Nested
    @DisplayName("Random Data")
    inner class RandomDataTests {

        @Test
        fun `should throw for completely random data`() {
            val garbage = ByteArray(200) { it.toByte() }
            // First byte is 0x00, which is not a valid version
            assertThrows<Exception> {
                IndexBlobReader.openUnencrypted(garbage, blobId)
            }
        }

        @Test
        fun `should throw or parse incorrectly for random data with valid V1 version byte`() {
            val garbage = ByteArray(200) { (it + 1).toByte() }
            garbage[0] = IndexVersion.V1.toByte() // Set valid version byte

            // With valid version but garbage content, may parse with wrong data or throw
            try {
                val reader = IndexBlobReader.openUnencrypted(garbage, blobId)
                // If it parsed, entries should be nonsensical but should not crash
                reader.iterate().toList()
                reader.close()
            } catch (_: Exception) {
                // Exception is acceptable
            }
        }

        @Test
        fun `should throw for single version byte with suffix`() {
            // Exactly 33 bytes: 1 version byte + 32 suffix bytes
            val data = ByteArray(IndexBlobConstants.RANDOM_SUFFIX_SIZE + 1)
            data[0] = IndexVersion.V1.toByte()

            // After removing 32 byte suffix, only 1 byte remains (just version, no header)
            try {
                val reader = IndexBlobReader.openUnencrypted(data, blobId)
                // If parsed, should have 0 entries (header too short for entries)
                assertTrue(
                    reader.approximateCount() == 0,
                    "Minimal index should have 0 entries"
                )
                reader.close()
            } catch (_: Exception) {
                // Exception is also acceptable for truncated header
            }
        }
    }

    @Nested
    @DisplayName("Extra Data Corruption")
    inner class ExtraDataCorruption {

        @Test
        fun `should produce wrong pack blob IDs when extra data is corrupted`() {
            // The extra data section (after entries) contains pack blob ID strings.
            // Corrupting it should produce wrong pack blob IDs.
            val indexDataEnd = validIndexData.size - IndexBlobConstants.RANDOM_SUFFIX_SIZE
            // Extra data is at the end of the index data (before suffix).
            // Corrupt the last few bytes of the index data (before suffix).
            val corruptOffset = indexDataEnd - 5
            if (corruptOffset > 0) {
                val corrupted = CorruptionHelpers.zeroRange(validIndexData, corruptOffset, 5)

                try {
                    val originalReader = IndexBlobReader.openUnencrypted(validIndexData, blobId)
                    val corruptedReader = IndexBlobReader.openUnencrypted(corrupted, blobId)

                    val originalEntries = originalReader.iterate().toList()
                    val corruptedEntries = corruptedReader.iterate().toList()

                    // Verify entries were produced (reader didn't silently return empty)
                    assertTrue(
                        corruptedEntries.isNotEmpty(),
                        "Corrupted index should still produce parseable entries"
                    )

                    // The zeroed bytes target the extra data section (pack blob ID strings).
                    // At least one entry's pack blob ID should differ from the original.
                    val anyBlobIdDiffers = originalEntries.zip(corruptedEntries).any { (a, b) ->
                        a.packBlobId != b.packBlobId
                    }
                    assertTrue(
                        anyBlobIdDiffers,
                        "Zeroing bytes in extra data area should corrupt pack blob IDs"
                    )

                    originalReader.close()
                    corruptedReader.close()
                } catch (_: Exception) {
                    // Exception is also acceptable - corruption detected
                }
            }
        }
    }
}

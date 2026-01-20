package org.kopiaKt.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.testvectors.TestVectorLoader

/**
 * Tests ContentId against Go-generated test vectors.
 */
class ContentIdVectorTest {

    private val vectors = TestVectorLoader.load()

    @Test
    fun `ContentId formation matches Go test vectors`() {
        for (testCase in vectors.contentId.formation) {
            val prefix = testCase.prefix.firstOrNull()

            // Check if this is an invalid prefix case (prefix < 'g')
            if (prefix != null && prefix < 'g') {
                // This should fail to parse when constructing from hash
                assertThrows<IllegalArgumentException>("Expected failure for invalid prefix '$prefix' in ${testCase.name}") {
                    ContentId.fromHash(prefix, testCase.hash)
                }
                continue
            }

            // Valid case - verify formation
            val id = if (testCase.prefix.isEmpty()) {
                ContentId.fromHash(null, testCase.hash)
            } else {
                ContentId.fromHash(testCase.prefix[0], testCase.hash)
            }

            assertEquals(testCase.contentId, id.toString(), "Content ID string mismatch for ${testCase.name}")

            if (testCase.prefix.isEmpty()) {
                assertEquals(null, id.prefix, "Prefix should be null for ${testCase.name}")
            } else {
                assertEquals(testCase.prefix[0], id.prefix, "Prefix mismatch for ${testCase.name}")
            }

            assertEquals(testCase.hash.toList(), id.hashBytes.toList(), "Hash mismatch for ${testCase.name}")
        }
    }

    @Test
    fun `ContentId parsing matches Go test vectors`() {
        for (testCase in vectors.contentId.formation) {
            val prefix = testCase.prefix.firstOrNull()

            // Check if this is an invalid prefix case
            // An invalid prefix is one where the string length is odd and first char < 'g'
            val isInvalidPrefix = prefix != null && prefix < 'g'

            if (isInvalidPrefix) {
                // For parsing, we need to check if the contentId string would trigger prefix detection
                // If length is odd and first char is < 'g', it should fail
                if (testCase.contentId.length % 2 == 1 && testCase.contentId.isNotEmpty()) {
                    val firstChar = testCase.contentId[0]
                    if (firstChar < 'g') {
                        assertThrows<IllegalArgumentException>("Expected parse failure for ${testCase.name}") {
                            ContentId.parse(testCase.contentId)
                        }
                        continue
                    }
                }
            }

            // Valid case - verify parsing
            val id = ContentId.parse(testCase.contentId)

            assertEquals(testCase.contentId, id.toString(), "Round-trip mismatch for ${testCase.name}")

            if (testCase.prefix.isEmpty()) {
                assertEquals(null, id.prefix, "Parsed prefix should be null for ${testCase.name}")
            } else {
                assertEquals(testCase.prefix[0], id.prefix, "Parsed prefix mismatch for ${testCase.name}")
            }

            assertEquals(testCase.hash.toList(), id.hashBytes.toList(), "Parsed hash mismatch for ${testCase.name}")
        }
    }

    @Test
    fun `ContentId round-trip for valid prefixes`() {
        val validPrefixes = listOf(null, 'g', 'k', 'm', 'p', 'x', 'z')
        val testHash = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte())

        for (prefix in validPrefixes) {
            val id = ContentId.fromHash(prefix, testHash)
            val parsed = ContentId.parse(id.toString())

            assertEquals(id, parsed, "Round-trip failed for prefix '$prefix'")
            assertEquals(prefix, parsed.prefix, "Prefix mismatch after round-trip for '$prefix'")
            assertEquals(testHash.toList(), parsed.hashBytes.toList(), "Hash mismatch after round-trip for '$prefix'")
        }
    }

    @Test
    fun `ContentId rejects prefixes outside g-z range`() {
        val invalidPrefixes = listOf('a', 'b', 'c', 'd', 'e', 'f', 'A', 'Z', '0', '!')
        val testHash = byteArrayOf(0x12, 0x34)

        for (prefix in invalidPrefixes) {
            assertThrows<IllegalArgumentException>("Expected failure for invalid prefix '$prefix'") {
                ContentId.fromHash(prefix, testHash)
            }
        }
    }
}

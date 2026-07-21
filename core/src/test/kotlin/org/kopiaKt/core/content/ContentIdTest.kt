package org.kopiaKt.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for ContentId - a hash-based identifier for content blocks.
 *
 * ContentId format (from Go implementation):
 * - Optional single-character prefix ('g' to 'z')
 * - Followed by hex-encoded hash bytes (up to 32 bytes = 64 hex chars)
 * - Empty string is valid (EmptyID)
 * - If string length is odd and first char is 'g'-'z', it's a prefix
 */
class ContentIdTest {

    // === Empty ID Tests ===

    @Test
    fun `EmptyID should have empty string representation`() {
        assertEquals("", ContentId.Empty.toString())
    }

    @Test
    fun `EmptyID should have no prefix`() {
        assertNull(ContentId.Empty.prefix)
    }

    @Test
    fun `EmptyID should have empty hash`() {
        assertEquals(0, ContentId.Empty.hashBytes.size)
    }

    @Test
    fun `parse empty string should return EmptyID`() {
        val id = ContentId.parse("")
        assertEquals(ContentId.Empty, id)
    }

    // === Parsing without prefix ===

    @Test
    fun `parse hex string without prefix`() {
        val id = ContentId.parse("0123456789abcdef")
        assertNull(id.prefix)
        assertEquals("0123456789abcdef", id.toString())
        assertEquals(8, id.hashBytes.size)
    }

    @Test
    fun `parse short hex string`() {
        val id = ContentId.parse("ab")
        assertNull(id.prefix)
        assertEquals("ab", id.toString())
        assertEquals(1, id.hashBytes.size)
        assertEquals(0xab.toByte(), id.hashBytes[0])
    }

    @Test
    fun `parse long hex string (32 bytes = 64 chars)`() {
        val hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val id = ContentId.parse(hex)
        assertNull(id.prefix)
        assertEquals(hex, id.toString())
        assertEquals(32, id.hashBytes.size)
    }

    // === Parsing with prefix ===

    @Test
    fun `parse with prefix g`() {
        val id = ContentId.parse("g0123456789abcdef")
        assertEquals('g', id.prefix)
        assertEquals("g0123456789abcdef", id.toString())
        assertEquals(8, id.hashBytes.size)
    }

    @Test
    fun `parse with prefix m (manifest)`() {
        val id = ContentId.parse("m0123456789abcdef")
        assertEquals('m', id.prefix)
        assertEquals("m0123456789abcdef", id.toString())
    }

    @Test
    fun `parse with prefix z`() {
        val id = ContentId.parse("z0123456789abcdef")
        assertEquals('z', id.prefix)
        assertEquals("z0123456789abcdef", id.toString())
    }

    @Test
    fun `parse with prefix and short hash`() {
        // Odd length (5) with valid prefix 'g' -> prefix is 'g', hash is "12ab"
        val id = ContentId.parse("g12ab")
        assertEquals('g', id.prefix)
        assertEquals("g12ab", id.toString())
        assertEquals(2, id.hashBytes.size)
    }

    @Test
    fun `parse determines prefix by odd length rule`() {
        // Even length (4) means no prefix, hash is "g12a" which starts with valid hex 'g'
        // However, Go uses odd-length rule: if odd and first char is g-z, it's a prefix
        // "g12a" is length 4 (even), so no prefix
        val id = ContentId.parse("abcd")
        assertNull(id.prefix)
        assertEquals("abcd", id.toString())
    }

    // === Prefix validation ===

    @Test
    fun `parse rejects invalid prefix character before g`() {
        // Odd length (3) but first char 'f' < 'g', so invalid prefix
        val ex = assertThrows<IllegalArgumentException> {
            ContentId.parse("fab")
        }
        assertTrue(ex.message!!.contains("invalid content prefix"))
    }

    @Test
    fun `parse rejects characters before g with odd length`() {
        // "aaa" has odd length, 'a' < 'g', so this is an invalid prefix
        val ex = assertThrows<IllegalArgumentException> {
            ContentId.parse("aaa")
        }
        assertTrue(ex.message!!.contains("invalid content prefix"))
    }

    @Test
    fun `even length with a-f first char is valid (no prefix)`() {
        // "aaaa" has even length, so no prefix check, just hex decode
        val id = ContentId.parse("aaaa")
        assertNull(id.prefix)
        assertEquals("aaaa", id.toString())
    }

    // === Hash validation ===

    @Test
    fun `parse rejects invalid hex characters`() {
        assertThrows<IllegalArgumentException> {
            ContentId.parse("xyz123") // 'x', 'y' are not valid hex
        }
    }

    @Test
    fun `parse rejects hash too long`() {
        // 65 hex chars = 32.5 bytes > 32 bytes max
        val hex = "g" + "0".repeat(65) // 66 chars total, odd, prefix 'g', hash 65 chars
        val ex = assertThrows<IllegalArgumentException> {
            ContentId.parse(hex)
        }
        assertTrue(ex.message!!.contains("hash too long"))
    }

    // === fromHash factory ===

    @Test
    fun `fromHash creates ID without prefix`() {
        val hash = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val id = ContentId.fromHash(null, hash)
        assertNull(id.prefix)
        assertEquals("12345678", id.toString())
    }

    @Test
    fun `fromHash creates ID with prefix`() {
        val hash = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val id = ContentId.fromHash('m', hash)
        assertEquals('m', id.prefix)
        assertEquals("m12345678", id.toString())
    }

    @Test
    fun `fromHash validates prefix range`() {
        val hash = byteArrayOf(0x12, 0x34)
        assertThrows<IllegalArgumentException> {
            ContentId.fromHash('a', hash) // 'a' < 'g'
        }
    }

    @Test
    fun `fromHash rejects empty hash`() {
        assertThrows<IllegalArgumentException> {
            ContentId.fromHash(null, byteArrayOf())
        }
    }

    // === hasPrefix ===

    @Test
    fun `hasPrefix returns true for prefixed ID`() {
        val id = ContentId.parse("mabcdef12")
        assertTrue(id.hasPrefix)
    }

    @Test
    fun `hasPrefix returns false for non-prefixed ID`() {
        val id = ContentId.parse("abcdef12")
        assertFalse(id.hasPrefix)
    }

    @Test
    fun `hasPrefix returns false for EmptyID`() {
        assertFalse(ContentId.Empty.hasPrefix)
    }

    // === Round-trip tests ===

    @Test
    fun `round trip without prefix`() {
        val original = "0123456789abcdef"
        val id = ContentId.parse(original)
        assertEquals(original, id.toString())
    }

    @Test
    fun `round trip with prefix`() {
        val original = "kabcdef1234567890"
        val id = ContentId.parse(original)
        assertEquals(original, id.toString())
    }

    @Test
    fun `round trip from bytes and back`() {
        val hash = byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x12)
        val id = ContentId.fromHash('k', hash)
        val parsed = ContentId.parse(id.toString())
        assertEquals(id, parsed)
        assertEquals('k', parsed.prefix)
        assertTrue(hash.contentEquals(parsed.hashBytes))
    }

    // === Equality ===

    @Test
    fun `equal IDs are equal`() {
        val id1 = ContentId.parse("kabcdef12")
        val id2 = ContentId.parse("kabcdef12")
        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
    }

    @Test
    fun `different prefix means different ID`() {
        val id1 = ContentId.parse("kabcdef12")
        val id2 = ContentId.parse("mabcdef12") // Note: this is odd length with 'm' prefix
        // Actually "mabcdef12" has 9 chars (odd), first char 'm' is in g-z range
        // So prefix='m', hash="abcdef12" (4 bytes)
        assertTrue(id1 != id2)
    }

    // === Edge cases ===

    @Test
    fun `single byte hash`() {
        val id = ContentId.parse("ab")
        assertEquals(1, id.hashBytes.size)
        assertEquals(0xab.toByte(), id.hashBytes[0])
    }

    @Test
    fun `single byte hash with prefix`() {
        val id = ContentId.parse("gab")
        assertEquals('g', id.prefix)
        assertEquals(1, id.hashBytes.size)
        assertEquals(0xab.toByte(), id.hashBytes[0])
    }

    @Test
    fun `uppercase hex is normalized to lowercase`() {
        val id = ContentId.parse("ABCDEF12")
        assertEquals("abcdef12", id.toString())
    }

    @Test
    fun `uppercase prefix with hex is handled`() {
        // Wait - uppercase letters like 'K' are NOT valid prefixes
        // Go only allows 'g' to 'z' (lowercase)
        // "KABCDEF1" has even length (8), so no prefix, parse as hex
        // But 'K' is not valid hex either... this should fail
        assertThrows<IllegalArgumentException> {
            ContentId.parse("KABCDEF1") // 'K' is not valid hex
        }
    }

    // === Go compatibility test vectors ===

    @Test
    fun `go compatibility - content id without prefix`() {
        // From Go: ParseID("0012abcd") -> prefix="", hash=[]byte{0x00, 0x12, 0xab, 0xcd}
        val id = ContentId.parse("0012abcd")
        assertNull(id.prefix)
        assertEquals(byteArrayOf(0x00, 0x12, 0xab.toByte(), 0xcd.toByte()).toList(), id.hashBytes.toList())
        assertEquals("0012abcd", id.toString())
    }

    @Test
    fun `go compatibility - content id with g prefix`() {
        // From Go: ParseID("g01234567") -> prefix="g", hash=[]byte{0x01, 0x23, 0x45, 0x67}
        val id = ContentId.parse("g01234567")
        assertEquals('g', id.prefix)
        assertEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67).toList(), id.hashBytes.toList())
        assertEquals("g01234567", id.toString())
    }

    @Test
    fun `go compatibility - maximum length hash`() {
        // 32 bytes = 64 hex chars
        val hashHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val id = ContentId.parse(hashHex)
        assertEquals(32, id.hashBytes.size)
        assertNull(id.prefix)
    }

    @Test
    fun `go compatibility - maximum length hash with prefix`() {
        // 32 bytes = 64 hex chars + 1 prefix = 65 chars total (odd)
        val hashHex = "z00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val id = ContentId.parse(hashHex)
        assertEquals(32, id.hashBytes.size)
        assertEquals('z', id.prefix)
    }
}

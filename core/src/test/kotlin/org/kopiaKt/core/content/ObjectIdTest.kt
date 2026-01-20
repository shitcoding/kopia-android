package org.kopiaKt.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for ObjectId - identifier for repository objects.
 *
 * ObjectId format (from Go implementation):
 * - Zero or more 'I' prefix chars for indirection level (one 'I' per level)
 * - Optional 'Z' for compression (mutually exclusive with indirection)
 * - Optional 'D' for legacy direct (no-op, parsed but not emitted)
 * - Followed by ContentId string
 *
 * Examples:
 * - "abcd" -> direct, no indirection, no compression
 * - "Iabcd" -> 1 level of indirection
 * - "IIabcd" -> 2 levels of indirection
 * - "Zabcd" -> compressed direct content
 * - "IIIgabcdef12" -> 3 levels with prefixed content ID
 */
class ObjectIdTest {

    // === Empty ObjectId Tests ===

    @Test
    fun `EmptyID should have empty string representation`() {
        assertEquals("", ObjectId.Empty.toString())
    }

    @Test
    fun `EmptyID should have zero indirection`() {
        assertEquals(0, ObjectId.Empty.indirection)
    }

    @Test
    fun `EmptyID should not be compressed`() {
        assertFalse(ObjectId.Empty.isCompressed)
    }

    @Test
    fun `EmptyID should have empty content ID`() {
        assertEquals(ContentId.Empty, ObjectId.Empty.contentId)
    }

    @Test
    fun `parse empty string should return EmptyID`() {
        val id = ObjectId.parse("")
        assertEquals(ObjectId.Empty, id)
    }

    // === Direct Object ID (no indirection, no compression) ===

    @Test
    fun `parse direct object ID`() {
        val id = ObjectId.parse("abcdef12")
        assertEquals(0, id.indirection)
        assertFalse(id.isCompressed)
        assertEquals("abcdef12", id.contentId.toString())
        assertEquals("abcdef12", id.toString())
    }

    @Test
    fun `parse direct object ID with content prefix`() {
        val id = ObjectId.parse("kabcdef12")
        assertEquals(0, id.indirection)
        assertFalse(id.isCompressed)
        assertEquals('k', id.contentId.prefix)
        assertEquals("kabcdef12", id.toString())
    }

    // === Indirect Object ID ===

    @Test
    fun `parse single level indirection`() {
        val id = ObjectId.parse("Iabcdef12")
        assertEquals(1, id.indirection)
        assertFalse(id.isCompressed)
        assertEquals("abcdef12", id.contentId.toString())
        assertEquals("Iabcdef12", id.toString())
    }

    @Test
    fun `parse double level indirection`() {
        val id = ObjectId.parse("IIabcdef12")
        assertEquals(2, id.indirection)
        assertFalse(id.isCompressed)
        assertEquals("abcdef12", id.contentId.toString())
        assertEquals("IIabcdef12", id.toString())
    }

    @Test
    fun `parse triple level indirection`() {
        val id = ObjectId.parse("IIIabcdef12")
        assertEquals(3, id.indirection)
        assertFalse(id.isCompressed)
        assertEquals("IIIabcdef12", id.toString())
    }

    @Test
    fun `parse indirection with content prefix`() {
        val id = ObjectId.parse("IImabcdef12")
        assertEquals(2, id.indirection)
        assertEquals('m', id.contentId.prefix)
        assertEquals("IImabcdef12", id.toString())
    }

    // === Compressed Object ID ===

    @Test
    fun `parse compressed object ID`() {
        val id = ObjectId.parse("Zabcdef12")
        assertEquals(0, id.indirection)
        assertTrue(id.isCompressed)
        assertEquals("abcdef12", id.contentId.toString())
        assertEquals("Zabcdef12", id.toString())
    }

    @Test
    fun `parse compressed object ID with content prefix`() {
        val id = ObjectId.parse("Zkabcdef12")
        assertEquals(0, id.indirection)
        assertTrue(id.isCompressed)
        assertEquals('k', id.contentId.prefix)
        assertEquals("Zkabcdef12", id.toString())
    }

    // === Compression and Indirection are Mutually Exclusive ===

    @Test
    fun `parse rejects combined indirection and compression`() {
        val ex = assertThrows<IllegalArgumentException> {
            ObjectId.parse("IZabcdef12")
        }
        assertTrue(ex.message!!.contains("mutually exclusive"))
    }

    @Test
    fun `parse rejects combined compression and indirection`() {
        // Note: Go parses I first, then Z, so "ZI..." would have Z parsed
        // then the "I" becomes part of content ID parsing, which fails
        // Let's verify our behavior matches
        val ex = assertThrows<IllegalArgumentException> {
            ObjectId.parse("IIZabcdef12")
        }
        assertTrue(ex.message!!.contains("mutually exclusive"))
    }

    // === Legacy 'D' Prefix ===

    @Test
    fun `parse legacy D prefix (no-op)`() {
        val id = ObjectId.parse("Dabcdef12")
        assertEquals(0, id.indirection)
        assertFalse(id.isCompressed)
        assertEquals("abcdef12", id.contentId.toString())
        // D is not emitted in output
        assertEquals("abcdef12", id.toString())
    }

    @Test
    fun `parse D after Z`() {
        val id = ObjectId.parse("ZDabcdef12")
        assertTrue(id.isCompressed)
        assertEquals("abcdef12", id.contentId.toString())
        assertEquals("Zabcdef12", id.toString())
    }

    @Test
    fun `parse D after I`() {
        val id = ObjectId.parse("IDabcdef12")
        assertEquals(1, id.indirection)
        assertEquals("abcdef12", id.contentId.toString())
        assertEquals("Iabcdef12", id.toString())
    }

    // === Factory Methods ===

    @Test
    fun `direct creates direct object ID`() {
        val contentId = ContentId.parse("kabcdef12")
        val objectId = ObjectId.direct(contentId)
        assertEquals(0, objectId.indirection)
        assertFalse(objectId.isCompressed)
        assertEquals(contentId, objectId.contentId)
    }

    @Test
    fun `compressed creates compressed object ID`() {
        val contentId = ContentId.parse("kabcdef12")
        val objectId = ObjectId.compressed(contentId)
        assertEquals(0, objectId.indirection)
        assertTrue(objectId.isCompressed)
        assertEquals(contentId, objectId.contentId)
    }

    @Test
    fun `indirect creates indirect object ID`() {
        val contentId = ContentId.parse("kabcdef12")
        val objectId = ObjectId.indirect(contentId, indirection = 2)
        assertEquals(2, objectId.indirection)
        assertFalse(objectId.isCompressed)
        assertEquals(contentId, objectId.contentId)
    }

    // === indexObjectId and contentId methods ===

    @Test
    fun `indexObjectId decrements indirection`() {
        val id = ObjectId.parse("IIabcdef12")
        val (indexId, ok) = id.indexObjectId()
        assertTrue(ok)
        assertEquals(1, indexId.indirection)
        assertEquals("abcdef12", indexId.contentId.toString())
    }

    @Test
    fun `indexObjectId fails for direct object`() {
        val id = ObjectId.parse("abcdef12")
        val (_, ok) = id.indexObjectId()
        assertFalse(ok)
    }

    @Test
    fun `getContentId succeeds for direct object`() {
        val id = ObjectId.parse("abcdef12")
        val (contentId, compressed, ok) = id.getContentId()
        assertTrue(ok)
        assertFalse(compressed)
        assertEquals("abcdef12", contentId.toString())
    }

    @Test
    fun `getContentId succeeds for compressed direct object`() {
        val id = ObjectId.parse("Zabcdef12")
        val (contentId, compressed, ok) = id.getContentId()
        assertTrue(ok)
        assertTrue(compressed)
        assertEquals("abcdef12", contentId.toString())
    }

    @Test
    fun `getContentId fails for indirect object`() {
        val id = ObjectId.parse("Iabcdef12")
        val (_, _, ok) = id.getContentId()
        assertFalse(ok)
    }

    // === Increment/Decrement Indirection ===

    @Test
    fun `incrementIndirection increases level`() {
        val id = ObjectId.parse("Iabcdef12")
        val incremented = id.incrementIndirection()
        assertEquals(2, incremented.indirection)
        assertEquals("IIabcdef12", incremented.toString())
    }

    @Test
    fun `incrementIndirection on direct creates indirect`() {
        val id = ObjectId.parse("abcdef12")
        val incremented = id.incrementIndirection()
        assertEquals(1, incremented.indirection)
        assertEquals("Iabcdef12", incremented.toString())
    }

    // === Round-trip Tests ===

    @Test
    fun `round trip direct object ID`() {
        val original = "kabcdef12"
        val id = ObjectId.parse(original)
        assertEquals(original, id.toString())
    }

    @Test
    fun `round trip indirect object ID`() {
        val original = "IIIkabcdef12"
        val id = ObjectId.parse(original)
        assertEquals(original, id.toString())
    }

    @Test
    fun `round trip compressed object ID`() {
        val original = "Zkabcdef12"
        val id = ObjectId.parse(original)
        assertEquals(original, id.toString())
    }

    // === Equality ===

    @Test
    fun `equal IDs are equal`() {
        val id1 = ObjectId.parse("IIkabcdef12")
        val id2 = ObjectId.parse("IIkabcdef12")
        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
    }

    @Test
    fun `different indirection means different ID`() {
        val id1 = ObjectId.parse("Iabcdef12")
        val id2 = ObjectId.parse("IIabcdef12")
        assertTrue(id1 != id2)
    }

    @Test
    fun `compressed vs non-compressed are different`() {
        val id1 = ObjectId.parse("abcdef12")
        val id2 = ObjectId.parse("Zabcdef12")
        assertTrue(id1 != id2)
    }

    // === Edge cases ===

    @Test
    fun `max indirection (255 I characters)`() {
        val prefix = "I".repeat(255)
        val id = ObjectId.parse("${prefix}abcdef12")
        assertEquals(255, id.indirection)
        assertEquals("${prefix}abcdef12", id.toString())
    }

    @Test
    fun `indirection beyond 255 should fail`() {
        val prefix = "I".repeat(256)
        val ex = assertThrows<IllegalArgumentException> {
            ObjectId.parse("${prefix}abcdef12")
        }
        assertTrue(ex.message!!.contains("too many"))
    }

    // === Go compatibility ===

    @Test
    fun `go compatibility - simple direct`() {
        val id = ObjectId.parse("abcd")
        assertEquals(0, id.indirection)
        assertFalse(id.isCompressed)
        assertEquals("abcd", id.toString())
    }

    @Test
    fun `go compatibility - indirect with prefix content ID`() {
        // "IIgabcdef0" after removing "II" is "gabcdef0" (8 chars, even) -> no prefix
        // We need odd length for prefix: "gabcdef0f" (9 chars, odd) -> prefix 'g'
        val id = ObjectId.parse("IIgabcdef0f")
        assertEquals(2, id.indirection)
        assertEquals('g', id.contentId.prefix)
        assertEquals("IIgabcdef0f", id.toString())
    }
}

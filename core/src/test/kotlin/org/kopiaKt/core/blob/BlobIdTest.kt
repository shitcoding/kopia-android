package org.kopiaKt.core.blob

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for BlobId - identifier for blobs in storage.
 *
 * BlobId format (from Go implementation):
 * - Simple string identifier
 * - Various prefixes indicate blob type (p, q, n, s, x)
 * - Well-known IDs for configuration blobs
 */
class BlobIdTest {

    // === Basic Properties ===

    @Test
    fun `BlobId wraps string value`() {
        val id = BlobId("p0123456789abcdef")
        assertEquals("p0123456789abcdef", id.value)
    }

    @Test
    fun `BlobId toString returns value`() {
        val id = BlobId("p0123456789abcdef")
        assertEquals("p0123456789abcdef", id.toString())
    }

    @Test
    fun `BlobId rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            BlobId("")
        }
    }

    // === Prefix Detection ===

    @Test
    fun `hasPrefix returns true for matching prefix`() {
        val id = BlobId("p0123456789abcdef")
        assertTrue(id.hasPrefix("p"))
        assertTrue(id.hasPrefix("p0"))
        assertTrue(id.hasPrefix("p0123"))
    }

    @Test
    fun `hasPrefix returns false for non-matching prefix`() {
        val id = BlobId("p0123456789abcdef")
        assertFalse(id.hasPrefix("n"))
        assertFalse(id.hasPrefix("q"))
        assertFalse(id.hasPrefix("x"))
    }

    @Test
    fun `isPackBlob detects pack blob prefix`() {
        assertTrue(BlobId("p0123456789abcdef").isPackBlob)
        assertTrue(BlobId("q0123456789abcdef").isPackBlob)
        assertFalse(BlobId("n0123456789abcdef").isPackBlob)
        assertFalse(BlobId("s0123456789abcdef").isPackBlob)
    }

    @Test
    fun `isIndexBlob detects index blob prefix`() {
        assertTrue(BlobId("n0123456789abcdef").isIndexBlob)
        assertTrue(BlobId("x0123456789abcdef").isIndexBlob)
        assertFalse(BlobId("p0123456789abcdef").isIndexBlob)
        assertFalse(BlobId("s0123456789abcdef").isIndexBlob)
    }

    @Test
    fun `isSessionBlob detects session blob prefix`() {
        assertTrue(BlobId("s0123456789abcdef").isSessionBlob)
        assertFalse(BlobId("p0123456789abcdef").isSessionBlob)
        assertFalse(BlobId("n0123456789abcdef").isSessionBlob)
    }

    // === Factory Methods ===

    @Test
    fun `packBlob factory creates blob with p prefix`() {
        val id = BlobId.packBlob("0123456789abcdef")
        assertEquals("p0123456789abcdef", id.value)
        assertTrue(id.isPackBlob)
    }

    @Test
    fun `packSpecialBlob factory creates blob with q prefix`() {
        val id = BlobId.packSpecialBlob("0123456789abcdef")
        assertEquals("q0123456789abcdef", id.value)
        assertTrue(id.isPackBlob)
    }

    @Test
    fun `indexBlob factory creates blob with n prefix`() {
        val id = BlobId.indexBlob("0123456789abcdef")
        assertEquals("n0123456789abcdef", id.value)
        assertTrue(id.isIndexBlob)
    }

    @Test
    fun `sessionBlob factory creates blob with s prefix`() {
        val id = BlobId.sessionBlob("0123456789abcdef")
        assertEquals("s0123456789abcdef", id.value)
        assertTrue(id.isSessionBlob)
    }

    // === Well-Known IDs ===

    @Test
    fun `REPOSITORY_FORMAT has correct value`() {
        assertEquals("kopia.repository", BlobId.REPOSITORY_FORMAT.value)
    }

    @Test
    fun `REPOSITORY_BLOB has correct value`() {
        assertEquals("kopia.blobcfg", BlobId.REPOSITORY_BLOB.value)
    }

    // === Equality ===

    @Test
    fun `equal BlobIds are equal`() {
        val id1 = BlobId("p0123456789abcdef")
        val id2 = BlobId("p0123456789abcdef")
        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
    }

    @Test
    fun `different BlobIds are not equal`() {
        val id1 = BlobId("p0123456789abcdef")
        val id2 = BlobId("p0123456789abcdeg")
        assertFalse(id1 == id2)
    }

    // === Go Compatibility ===

    @Test
    fun `go compatibility - pack blob format`() {
        // Go pack blobs: prefix "p" + hex content ID hash
        val id = BlobId.packBlob("0123456789abcdef0123456789abcdef")
        assertEquals("p0123456789abcdef0123456789abcdef", id.value)
    }

    @Test
    fun `go compatibility - index blob format`() {
        // Go index blobs: prefix "n" + hex timestamp + random
        val id = BlobId.indexBlob("0123456789abcdef0123456789abcdef")
        assertEquals("n0123456789abcdef0123456789abcdef", id.value)
    }

    @Test
    fun `go compatibility - well-known repository blob`() {
        // Go uses "kopia.repository" for format blob
        assertEquals("kopia.repository", BlobId.REPOSITORY_FORMAT.value)
    }
}

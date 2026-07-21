package org.kopiaKt.core.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for ManifestId - unique identifier for manifests.
 *
 * ManifestId format (from Go implementation):
 * - 16 bytes of random data
 * - Hex-encoded to 32 characters
 * - Generated using cryptographically secure random
 */
class ManifestIdTest {

    // === Basic Properties ===

    @Test
    fun `ManifestId wraps string value`() {
        val id = ManifestId("0123456789abcdef0123456789abcdef")
        assertEquals("0123456789abcdef0123456789abcdef", id.value)
    }

    @Test
    fun `ManifestId toString returns value`() {
        val id = ManifestId("0123456789abcdef0123456789abcdef")
        assertEquals("0123456789abcdef0123456789abcdef", id.toString())
    }

    // === Generation ===

    @Test
    fun `generate creates 32 character hex string`() {
        val id = ManifestId.generate()
        assertEquals(32, id.value.length)
    }

    @Test
    fun `generate creates valid hex characters`() {
        val id = ManifestId.generate()
        assertTrue(id.value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `generate creates unique IDs`() {
        val ids = (1..100).map { ManifestId.generate() }.toSet()
        assertEquals(100, ids.size, "Generated IDs should be unique")
    }

    // === Validation ===

    @Test
    fun `rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            ManifestId("")
        }
    }

    @Test
    fun `rejects too short string`() {
        assertThrows<IllegalArgumentException> {
            ManifestId("0123456789abcdef") // 16 chars, should be 32
        }
    }

    @Test
    fun `rejects too long string`() {
        assertThrows<IllegalArgumentException> {
            ManifestId("0123456789abcdef0123456789abcdef0") // 33 chars
        }
    }

    @Test
    fun `rejects invalid hex characters`() {
        assertThrows<IllegalArgumentException> {
            ManifestId("0123456789abcdef0123456789abcdeg") // 'g' is not hex
        }
    }

    @Test
    fun `accepts lowercase hex`() {
        val id = ManifestId("0123456789abcdef0123456789abcdef")
        assertEquals("0123456789abcdef0123456789abcdef", id.value)
    }

    @Test
    fun `normalizes uppercase to lowercase`() {
        val id = ManifestId("0123456789ABCDEF0123456789ABCDEF")
        assertEquals("0123456789abcdef0123456789abcdef", id.value)
    }

    // === Equality ===

    @Test
    fun `equal IDs are equal`() {
        val id1 = ManifestId("0123456789abcdef0123456789abcdef")
        val id2 = ManifestId("0123456789abcdef0123456789abcdef")
        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
    }

    @Test
    fun `different IDs are not equal`() {
        val id1 = ManifestId("0123456789abcdef0123456789abcdef")
        val id2 = ManifestId("fedcba9876543210fedcba9876543210")
        assertNotEquals(id1, id2)
    }

    // === Comparison ===

    @Test
    fun `IDs can be compared as strings`() {
        val id1 = ManifestId("00000000000000000000000000000001")
        val id2 = ManifestId("00000000000000000000000000000002")
        assertTrue(id1.value < id2.value)
    }

    // === Go Compatibility ===

    @Test
    fun `go compatibility - standard format`() {
        // Go generates: ID(hex.EncodeToString(random)) where random is 16 bytes
        // This results in a 32-character lowercase hex string
        val id = ManifestId.generate()
        assertEquals(32, id.value.length)
        assertTrue(id.value.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

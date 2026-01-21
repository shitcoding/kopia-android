package org.kopiaKt.core.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FormatVersionTest {

    @Test
    fun `version 1 is valid`() {
        val v = FormatVersion(1)
        assertEquals(1, v.value)
        assertEquals(FormatVersion.V1, v)
    }

    @Test
    fun `version 2 is valid`() {
        val v = FormatVersion(2)
        assertEquals(2, v.value)
        assertEquals(FormatVersion.V2, v)
    }

    @Test
    fun `version 3 is valid`() {
        val v = FormatVersion(3)
        assertEquals(3, v.value)
        assertEquals(FormatVersion.V3, v)
    }

    @Test
    fun `version 0 is invalid`() {
        assertThrows<IllegalArgumentException> {
            FormatVersion(0)
        }
    }

    @Test
    fun `version 4 is invalid`() {
        assertThrows<IllegalArgumentException> {
            FormatVersion(4)
        }
    }

    @Test
    fun `negative version is invalid`() {
        assertThrows<IllegalArgumentException> {
            FormatVersion(-1)
        }
    }

    @Test
    fun `versions are comparable`() {
        assertTrue(FormatVersion.V1 < FormatVersion.V2)
        assertTrue(FormatVersion.V2 < FormatVersion.V3)
        assertTrue(FormatVersion.V3 > FormatVersion.V1)
    }

    @Test
    fun `current version is V3`() {
        assertEquals(FormatVersion.V3, FormatVersion.CURRENT)
    }

    @Test
    fun `toString returns numeric value`() {
        assertEquals("1", FormatVersion.V1.toString())
        assertEquals("2", FormatVersion.V2.toString())
        assertEquals("3", FormatVersion.V3.toString())
    }

    @Test
    fun `constants are correct`() {
        assertEquals(3, FormatVersion.MAX_VERSION)
        assertEquals(FormatVersion.V1, FormatVersion.MIN_SUPPORTED_READ)
        assertEquals(FormatVersion.V3, FormatVersion.MAX_SUPPORTED_READ)
        assertEquals(FormatVersion.V1, FormatVersion.MIN_SUPPORTED_WRITE)
        assertEquals(FormatVersion.V3, FormatVersion.MAX_SUPPORTED_WRITE)
    }
}

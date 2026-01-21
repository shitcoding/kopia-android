package org.kopiaKt.core.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class MutableParametersTest {

    @Test
    fun `default parameters are valid`() {
        val params = MutableParameters()
        assertDoesNotThrow { params.validate() }
    }

    @Test
    fun `default version is current`() {
        val params = MutableParameters()
        assertEquals(FormatVersion.CURRENT.value, params.version)
    }

    @Test
    fun `default max pack size is 20 MB`() {
        val params = MutableParameters()
        assertEquals(20 * 1024 * 1024, params.maxPackSize)
    }

    @Test
    fun `default index version is 2`() {
        val params = MutableParameters()
        assertEquals(2, params.indexVersion)
    }

    @Test
    fun `version 0 is invalid`() {
        val params = MutableParameters(version = 0)
        assertThrows<IllegalArgumentException> { params.validate() }
    }

    @Test
    fun `version 4 is invalid`() {
        val params = MutableParameters(version = 4)
        assertThrows<IllegalArgumentException> { params.validate() }
    }

    @Test
    fun `pack size too small is invalid`() {
        val params = MutableParameters(maxPackSize = 5 * 1024 * 1024) // 5 MB
        assertThrows<IllegalArgumentException> { params.validate() }
    }

    @Test
    fun `pack size too large is invalid`() {
        val params = MutableParameters(maxPackSize = 200 * 1024 * 1024) // 200 MB
        assertThrows<IllegalArgumentException> { params.validate() }
    }

    @Test
    fun `pack size at minimum is valid`() {
        val params = MutableParameters(maxPackSize = MutableParameters.MIN_VALID_PACK_SIZE)
        assertDoesNotThrow { params.validate() }
    }

    @Test
    fun `pack size at maximum is valid`() {
        val params = MutableParameters(maxPackSize = MutableParameters.MAX_VALID_PACK_SIZE)
        assertDoesNotThrow { params.validate() }
    }

    @Test
    fun `index version 0 is invalid`() {
        val params = MutableParameters(indexVersion = 0)
        assertThrows<IllegalArgumentException> { params.validate() }
    }

    @Test
    fun `index version 3 is invalid`() {
        val params = MutableParameters(indexVersion = 3)
        assertThrows<IllegalArgumentException> { params.validate() }
    }

    @Test
    fun `index version 1 is valid`() {
        val params = MutableParameters(indexVersion = 1)
        assertDoesNotThrow { params.validate() }
    }

    @Test
    fun `index version 2 is valid`() {
        val params = MutableParameters(indexVersion = 2)
        assertDoesNotThrow { params.validate() }
    }
}

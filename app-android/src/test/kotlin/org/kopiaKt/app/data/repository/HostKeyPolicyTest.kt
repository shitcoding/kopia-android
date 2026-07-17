package org.kopiaKt.app.data.repository

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Locks the connect/factory-layer release-gate: the insecure "trust any SFTP host key" opt-in must be
 * rejected in release builds and permitted only in debug builds (dev/testing).
 */
class HostKeyPolicyTest {

    @Test
    fun `insecure host-key skip is rejected in release builds`() {
        assertThrows<IllegalArgumentException> {
            requireInsecureHostKeyAllowed(insecureSkipHostKeyVerification = true, isDebugBuild = false)
        }
    }

    @Test
    fun `insecure host-key skip is allowed in debug builds`() {
        assertDoesNotThrow {
            requireInsecureHostKeyAllowed(insecureSkipHostKeyVerification = true, isDebugBuild = true)
        }
    }

    @Test
    fun `a secure config is allowed in release builds`() {
        assertDoesNotThrow {
            requireInsecureHostKeyAllowed(insecureSkipHostKeyVerification = false, isDebugBuild = false)
        }
    }
}

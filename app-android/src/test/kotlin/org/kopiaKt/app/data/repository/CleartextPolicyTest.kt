package org.kopiaKt.app.data.repository

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Locks the connect/factory-layer cleartext gate: contacting a storage backend over plaintext http
 * (which sends WebDAV/S3 credentials in the clear) requires an explicit per-connection opt-in.
 *
 * The gate lives at the connect layer rather than only in the UI because a persisted or imported
 * `ConnectionConfig` can carry any values — the same reasoning as the SFTP host-key gate
 * ([requireInsecureHostKeyAllowed]). Unlike that one this is permitted in RELEASE builds when
 * acknowledged: a self-hosted LAN backend with no TLS is a legitimate release use case, it just has to
 * be deliberate.
 */
class CleartextPolicyTest {

    @Nested
    @DisplayName("cleartext endpoints require the opt-in")
    inner class CleartextRequiresOptIn {

        @Test
        fun `http endpoint without the opt-in is rejected`() {
            assertThrows<IllegalArgumentException> {
                requireCleartextAllowed("http://nas.local:9000", allowCleartextHttp = false)
            }
        }

        @Test
        fun `http endpoint with the opt-in is allowed`() {
            assertDoesNotThrow {
                requireCleartextAllowed("http://nas.local:9000", allowCleartextHttp = true)
            }
        }

        @Test
        fun `the scheme check is case-insensitive and ignores surrounding whitespace`() {
            assertThrows<IllegalArgumentException> {
                requireCleartextAllowed("  HTTP://nas.local/dav/ ", allowCleartextHttp = false)
            }
        }

        @Test
        fun `a lenient single-slash http URL is still treated as cleartext`() {
            // OkHttp accepts http:/host, so the gate must match the app's isCleartextUrl() helper
            // (which keys off the "http:" scheme prefix) rather than only "http://".
            assertThrows<IllegalArgumentException> {
                requireCleartextAllowed("http:/nas.local/dav", allowCleartextHttp = false)
            }
        }
    }

    @Nested
    @DisplayName("secure endpoints are unaffected")
    inner class SecureEndpoints {

        @Test
        fun `https endpoint needs no opt-in`() {
            assertDoesNotThrow {
                requireCleartextAllowed("https://nas.local/dav/", allowCleartextHttp = false)
            }
        }

        @Test
        fun `a scheme-less endpoint needs no opt-in`() {
            // The S3 backend defaults a scheme-less endpoint to https, so this is not cleartext.
            assertDoesNotThrow {
                requireCleartextAllowed("s3.amazonaws.com", allowCleartextHttp = false)
            }
        }

        @Test
        fun `an empty endpoint needs no opt-in`() {
            assertDoesNotThrow { requireCleartextAllowed("", allowCleartextHttp = false) }
        }

        @Test
        fun `https is not confused with http by prefix matching`() {
            assertDoesNotThrow {
                requireCleartextAllowed("https://s3.example.com", allowCleartextHttp = false)
            }
        }
    }
}

package org.kopiaKt.storage.tls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Unit tests for [TlsTrust] — the custom-CA / pinned-certificate trust material used by the WebDAV and
 * S3 backends so a self-hosted server with a private or self-signed certificate can be reached over
 * https instead of forcing the user onto cleartext http.
 *
 * The certificates below are throwaway self-signed test fixtures (private keys discarded).
 */
class TlsTrustTest {

    private companion object {
        val TEST_CERT_PEM = TestCertificates.TEST_CERT_PEM
        val TEST_CERT_SHA256 = TestCertificates.TEST_CERT_SHA256
        val OTHER_CERT_PEM = TestCertificates.OTHER_CERT_PEM

        fun parse(pem: String): X509Certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    }

    private val testCert = parse(TEST_CERT_PEM)
    private val otherCert = parse(OTHER_CERT_PEM)

    @Nested
    @DisplayName("normalizeSha256Fingerprint")
    inner class NormalizeTests {

        @Test
        fun `accepts the openssl colon-separated uppercase form`() {
            val colonForm = TEST_CERT_SHA256.chunked(2).joinToString(":").uppercase()
            assertEquals(TEST_CERT_SHA256, TlsTrust.normalizeSha256Fingerprint(colonForm))
        }

        @Test
        fun `accepts plain lowercase hex and tolerates surrounding whitespace`() {
            assertEquals(TEST_CERT_SHA256, TlsTrust.normalizeSha256Fingerprint("  $TEST_CERT_SHA256\n"))
        }

        @Test
        fun `rejects a fingerprint of the wrong length`() {
            // Must fail loudly: a malformed pin that silently never matches (or, worse, is ignored)
            // would either look like an unexplained connection failure or a silent downgrade.
            assertThrows<IllegalArgumentException> { TlsTrust.normalizeSha256Fingerprint("abcd") }
        }

        @Test
        fun `rejects a non-hex fingerprint`() {
            assertThrows<IllegalArgumentException> {
                TlsTrust.normalizeSha256Fingerprint("z".repeat(64))
            }
        }

        @Test
        fun `rejects a blank fingerprint`() {
            assertThrows<IllegalArgumentException> { TlsTrust.normalizeSha256Fingerprint("   ") }
        }
    }

    @Nested
    @DisplayName("trustManagerForFingerprint")
    inner class FingerprintTrustTests {

        @Test
        fun `accepts the pinned certificate`() {
            val tm = TlsTrust.trustManagerForFingerprint(TEST_CERT_SHA256)
            tm.checkServerTrusted(arrayOf(testCert), "RSA") // must not throw
        }

        @Test
        fun `REJECTS a chain whose leaf is not the pinned certificate even if the pin appears later`() {
            // The pin-bypass attack: a malicious server presents its OWN leaf (whose private key it
            // holds) and appends the victim's pinned certificate — public data anyone can download —
            // as an extra chain entry. The handshake only proves possession of the leaf's key, so
            // matching any-cert-in-chain (what Go does) would accept the attacker. Must reject.
            val tm = TlsTrust.trustManagerForFingerprint(TEST_CERT_SHA256)
            assertThrows<CertificateException> {
                tm.checkServerTrusted(arrayOf(otherCert, testCert), "RSA")
            }
        }

        @Test
        fun `rejects a certificate that does not match the pin`() {
            val tm = TlsTrust.trustManagerForFingerprint(TEST_CERT_SHA256)
            assertThrows<CertificateException> { tm.checkServerTrusted(arrayOf(otherCert), "RSA") }
        }

        @Test
        fun `rejects an empty chain`() {
            val tm = TlsTrust.trustManagerForFingerprint(TEST_CERT_SHA256)
            assertThrows<CertificateException> { tm.checkServerTrusted(emptyArray(), "RSA") }
        }

        @Test
        fun `reports no accepted issuers (pinning replaces chain validation)`() {
            assertEquals(0, TlsTrust.trustManagerForFingerprint(TEST_CERT_SHA256).acceptedIssuers.size)
        }
    }

    @Nested
    @DisplayName("trustManagerForRootCa")
    inner class RootCaTrustTests {

        @Test
        fun `trusts the supplied CA as an issuer`() {
            val tm = TlsTrust.trustManagerForRootCa(TEST_CERT_PEM.toByteArray())
            val issuers = tm.acceptedIssuers.map { it.subjectX500Principal.name }
            assertTrue(
                issuers.any { it.contains("kopia-kt-test-ca") },
                "expected the supplied CA among accepted issuers, got $issuers",
            )
        }

        @Test
        fun `does NOT fall back to the system trust store`() {
            // The pin must be exclusive: mixing in system CAs would silently weaken it back to
            // "any publicly-trusted cert", which is not what asking for a custom root CA means.
            val tm = TlsTrust.trustManagerForRootCa(TEST_CERT_PEM.toByteArray())
            assertEquals(1, tm.acceptedIssuers.size)
        }

        @Test
        fun `rejects a server certificate not issued by the supplied CA`() {
            // Without this the CA tests only inspect acceptedIssuers — a trust manager that accepted
            // everything would still pass them.
            val tm = TlsTrust.trustManagerForRootCa(TEST_CERT_PEM.toByteArray())
            assertThrows<CertificateException> { tm.checkServerTrusted(arrayOf(otherCert), "RSA") }
        }

        @Test
        fun `rejects a PEM containing no certificate`() {
            assertThrows<IllegalArgumentException> {
                TlsTrust.trustManagerForRootCa("not a certificate".toByteArray())
            }
        }

        @Test
        fun `rejects an empty rootCa`() {
            assertThrows<IllegalArgumentException> { TlsTrust.trustManagerForRootCa(ByteArray(0)) }
        }
    }

    @Nested
    @DisplayName("socketFactory")
    inner class SocketFactoryTests {

        @Test
        fun `builds a usable socket factory for a trust manager`() {
            val tm = TlsTrust.trustManagerForFingerprint(TEST_CERT_SHA256)
            assertTrue(TlsTrust.socketFactory(tm).supportedCipherSuites.isNotEmpty())
        }
    }
}

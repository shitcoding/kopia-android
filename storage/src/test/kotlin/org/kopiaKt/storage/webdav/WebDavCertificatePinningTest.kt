package org.kopiaKt.storage.webdav

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.security.MessageDigest

/**
 * End-to-end TLS tests for WebDAV certificate pinning, over a REAL https handshake against
 * [MockWebServer] with a runtime-generated self-signed certificate.
 *
 * These exist because the option-level tests inject a mock client and therefore never exercise the
 * actual wiring — a regression that dropped the trust manager (or failed to pass the fingerprint into
 * the client) would pass those and silently disable pinning. Requires no Docker, so it runs in the
 * local gate.
 */
@DisplayName("WebDAV certificate pinning (real TLS handshake)")
class WebDavCertificatePinningTest {

    private lateinit var server: MockWebServer

    /** The certificate the server actually presents, for "localhost". */
    private val serverCertificate: HeldCertificate = HeldCertificate.Builder()
        .commonName("kopia-kt-webdav-test")
        .addSubjectAlternativeName("localhost")
        .build()

    /** An unrelated certificate the client never expects to see. */
    private val unrelatedCertificate: HeldCertificate = HeldCertificate.Builder()
        .commonName("kopia-kt-unrelated")
        .addSubjectAlternativeName("localhost")
        .build()

    private fun sha256Hex(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun startServer(vararg extraChainCertificates: HeldCertificate) {
        val builder = HandshakeCertificates.Builder()
        val certificates = builder
            .heldCertificate(serverCertificate, *extraChainCertificates.map { it.certificate }.toTypedArray())
            .build()

        server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        server.start()
    }

    @BeforeEach
    fun setUp() {
        // each test starts its own server (chain contents differ)
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) {
            server.shutdown()
        }
    }

    @Test
    fun `connects when the pinned fingerprint matches the server's leaf certificate`() {
        startServer()
        val client = OkHttpWebDavClient(
            trustedServerCertificateFingerprint = sha256Hex(serverCertificate.certificate.encoded),
        )

        val body = client.get(server.url("/file.f").toString()).use { it.readBytes().decodeToString() }

        assertEquals("hello", body)
    }

    @Test
    fun `refuses to connect when the server presents a different certificate`() {
        startServer()
        val client = OkHttpWebDavClient(
            trustedServerCertificateFingerprint = sha256Hex(unrelatedCertificate.certificate.encoded),
        )

        // The handshake must fail — anything else means the pin is not enforced.
        assertThrows<IOException> { client.get(server.url("/file.f").toString()) }
    }

    @Test
    fun `refuses when the pin matches a NON-leaf certificate in the server's chain`() {
        // Only the leaf may satisfy the pin — the handshake proves key possession for chain[0] alone.
        // Go kopia matches against every certificate the server sends, which lets a malicious server
        // present its own leaf and append the victim's pinned certificate (public data) to pass the
        // pin. Here the server legitimately chains leaf -> CA and the client pins the CA; accepting
        // that would mean any certificate issued by that CA is accepted, which is not what pinning a
        // specific certificate promises.
        //
        // (The arbitrary "stuff an unrelated cert into the chain" variant cannot be built here — the
        // JVM keystore refuses to serve an invalid chain — so that exact shape is covered at the
        // trust-manager level in TlsTrustTest.)
        val ca = HeldCertificate.Builder()
            .commonName("kopia-kt-test-ca")
            .certificateAuthority(0)
            .build()
        val leafSignedByCa = HeldCertificate.Builder()
            .commonName("kopia-kt-leaf")
            .addSubjectAlternativeName("localhost")
            .signedBy(ca)
            .build()

        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(leafSignedByCa, ca.certificate)
            .build()
        server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        server.start()

        val client = OkHttpWebDavClient(
            trustedServerCertificateFingerprint = sha256Hex(ca.certificate.encoded),
        )

        assertThrows<IOException> { client.get(server.url("/file.f").toString()) }
    }

    @Test
    fun `refuses a self-signed server when no fingerprint is pinned`() {
        // Sanity check that the pinning path is what makes the self-signed server reachable at all —
        // without it the platform trust store correctly rejects the certificate.
        startServer()
        val client = OkHttpWebDavClient()

        assertThrows<IOException> { client.get(server.url("/file.f").toString()) }
    }
}

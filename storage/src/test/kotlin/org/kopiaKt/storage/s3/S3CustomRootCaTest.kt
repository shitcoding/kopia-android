package org.kopiaKt.storage.s3

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * End-to-end TLS tests for the S3 `rootCa` option, over a REAL https handshake against [MockWebServer]
 * presenting a certificate issued by a private CA.
 *
 * These exist because the option-level tests inject a mock `S3Client` and so never exercise the actual
 * wiring: deleting the `tlsTrustManagersProvider(...)` call would leave those green while silently
 * breaking every private-CA S3 endpoint — pushing those users back onto acknowledged cleartext, the
 * exact outcome this feature exists to prevent. Needs no Docker, so it runs in the local gate.
 */
@DisplayName("S3 custom root CA (real TLS handshake)")
class S3CustomRootCaTest {

    private lateinit var server: MockWebServer

    /** Private CA the client will be told to trust. */
    private val rootCa: HeldCertificate = HeldCertificate.Builder()
        .commonName("kopia-kt-private-ca")
        .certificateAuthority(0)
        .build()

    /** Server certificate issued by [rootCa] — not trusted by the system store. */
    private val serverCertificate: HeldCertificate = HeldCertificate.Builder()
        .commonName("kopia-kt-s3")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .signedBy(rootCa)
        .build()

    @BeforeEach
    fun setUp() {
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate, rootCa.certificate)
            .build()
        server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        // S3BlobStorage.create() reads the .storageconfig object; an empty config is a valid answer.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"blobOptions":[]}"""))
        server.start()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) {
            server.shutdown()
        }
    }

    private fun options(rootCaPem: ByteArray?) = S3Options(
        bucketName = "test-bucket",
        endpoint = "https://localhost:${server.port}",
        region = "us-east-1",
        accessKeyId = "key",
        secretAccessKey = "secret",
        rootCa = rootCaPem,
    )

    @Test
    fun `connects to a private-CA server when rootCa is supplied`(): Unit = runBlocking {
        val storage = S3BlobStorage.create(options(rootCa.certificatePem().toByteArray()))

        assertNotNull(storage)
        storage.close()
    }

    @Test
    fun `refuses a private-CA server when no rootCa is supplied`() {
        // Without the custom trust anchor the platform correctly rejects the certificate — this is what
        // makes the rootCa path meaningful rather than a no-op.
        val failure = assertThrows<Exception> {
            runBlocking { S3BlobStorage.create(options(null)) }
        }

        val chain = generateSequence<Throwable>(failure) { it.cause }.joinToString(" <- ") {
            "${it::class.simpleName}: ${it.message}"
        }
        val isTlsTrustFailure = listOf("SSL", "certification path", "trust").any {
            chain.contains(it, ignoreCase = true)
        }
        assertTrue(isTlsTrustFailure, "expected a TLS trust failure, got: $chain")
    }
}

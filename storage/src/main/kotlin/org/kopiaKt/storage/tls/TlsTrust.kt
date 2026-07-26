package org.kopiaKt.storage.tls

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust material for storage backends whose server certificate is not signed by a publicly
 * trusted CA — the common self-hosting case (a NAS or home server with a private CA or a self-signed
 * certificate).
 *
 * This exists so those users can use **https** instead of being pushed onto cleartext http: without
 * it, the only working configuration for a private-CA backend is plain HTTP, which would leak WebDAV
 * Basic-auth credentials. Mirrors Go kopia's S3 `RootCA` and WebDAV
 * `TrustedServerCertificateFingerprint` options.
 *
 * Deliberately NOT provided: a "trust everything / skip verification" mode (Go's `DoNotVerifyTLS`).
 * Pinning a fingerprint or supplying a root CA covers every legitimate self-hosted setup, while a
 * reachable trust-all switch is an unbounded MITM downgrade. The backends keep rejecting that option.
 */
object TlsTrust {

    private const val SHA256_HEX_LENGTH = 64

    /**
     * Normalizes a SHA-256 certificate fingerprint to lowercase hex with no separators.
     *
     * Accepts the form `openssl x509 -fingerprint -sha256` prints (colon-separated uppercase) as well
     * as plain hex, since that is what users copy. Throws on anything that is not exactly 32 hex
     * bytes: a malformed pin must fail loudly rather than silently never matching (which looks like an
     * unexplained network error) or, worse, being treated as "no pin".
     */
    fun normalizeSha256Fingerprint(fingerprint: String): String {
        val normalized = fingerprint.trim()
            .removePrefix("sha256:")
            .removePrefix("SHA256:")
            .replace(":", "")
            .replace(" ", "")
            .lowercase()

        require(normalized.length == SHA256_HEX_LENGTH && normalized.all { it in "0123456789abcdef" }) {
            "Invalid SHA-256 certificate fingerprint: expected $SHA256_HEX_LENGTH hex characters " +
                "(optionally colon-separated), got \"$fingerprint\""
        }
        return normalized
    }

    /**
     * A trust manager that trusts exactly one certificate, identified by the SHA-256 hash of its DER
     * encoding — certificate pinning.
     *
     * Like Go's `tlsutil.TLSConfigTrustingSingleCertificate`, this **replaces** chain validation rather
     * than adding to it (which is why a self-signed certificate works, and why OkHttp's
     * `CertificatePinner` is NOT the right tool — that pins *in addition to* chain validation, so a
     * self-signed certificate would still be rejected).
     *
     * **Only the LEAF certificate is compared — deliberately stricter than Go.** The TLS handshake
     * proves the peer holds the private key for `chain[0]` only; any further certificates it sends are
     * unverified bytes. Go's `verifyPeerCertificate` accepts a match against *any* presented cert,
     * which a malicious server can trivially defeat: it presents its own leaf (whose key it owns) and
     * appends the victim's pinned certificate — public data anyone can fetch — as a bogus extra entry,
     * and the pin passes. Matching the leaf is what "pin this server's certificate" has to mean.
     */
    fun trustManagerForFingerprint(sha256Fingerprint: String): X509TrustManager {
        val expected = normalizeSha256Fingerprint(sha256Fingerprint)

        return object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val presented = chain.orEmpty()
                if (presented.isEmpty()) {
                    throw CertificateException(
                        "Server presented no certificate to match against the pinned fingerprint",
                    )
                }
                // chain[0] is the peer's own certificate (JSSE orders the peer chain leaf-first), and
                // it is the only one the handshake proves key possession for.
                val actual = sha256Hex(presented[0].encoded)
                if (actual != expected) {
                    throw CertificateException(
                        "Server certificate does not match the pinned SHA-256 fingerprint " +
                            "$expected (server leaf certificate is $actual)",
                    )
                }
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // This app is a TLS client only; it never validates client certificates.
                throw CertificateException("Client certificate validation is not supported")
            }

            // Empty by design: pinning replaces chain building, so there are no issuers to advertise.
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    /**
     * A trust manager that validates the server chain against ONLY the supplied PEM root CA
     * certificate(s) — Go's S3 `RootCA` option.
     *
     * The system trust store is deliberately not merged in: asking for a custom root CA means "trust
     * this CA", and silently also accepting every public CA would weaken the pin back to the default.
     * (Publicly-signed servers need no custom CA in the first place.)
     */
    fun trustManagerForRootCa(rootCaPem: ByteArray): X509TrustManager {
        require(rootCaPem.isNotEmpty()) { "rootCa is empty" }

        val certificates = try {
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(rootCaPem))
        } catch (e: CertificateException) {
            throw IllegalArgumentException("rootCa is not a valid PEM/DER certificate: ${e.message}", e)
        }
        require(certificates.isNotEmpty()) { "rootCa contains no certificate" }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("rootCa-$index", certificate) }
        }

        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)

        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalArgumentException("No X509TrustManager available for the supplied rootCa")
    }

    /** Builds an [SSLSocketFactory] that validates server certificates with [trustManager]. */
    fun socketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)
        return context.socketFactory
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

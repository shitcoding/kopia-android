package org.kopiaKt.storage.homelab

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobStorageContractTest
import org.kopiaKt.core.blob.HostKeyNotTrustedException
import org.kopiaKt.storage.s3.S3BlobStorage
import org.kopiaKt.storage.s3.S3Options
import org.kopiaKt.storage.sftp.SftpBlobStorage
import org.kopiaKt.storage.sftp.SftpOptions
import org.kopiaKt.storage.webdav.WebDavBlobStorage
import org.kopiaKt.storage.webdav.WebDavOptions
import java.io.File
import java.util.UUID

/**
 * Opt-in tests against a real private-network host (task-23.8 AC #2).
 *
 * These cover what Docker-on-localhost cannot: a real SSH host key, and real TLS with a private CA.
 * Concretely they exercise the SECURE trust paths that every other test stubs out or opts out of —
 * SFTP `knownHostsData` (the CI flows use the insecure "trust any host key" escape hatch instead),
 * WebDAV certificate pinning, and the S3 custom root CA.
 *
 * Skipped unless the `KOPIA_HOMELAB_*` environment is present. To run:
 *   e2e/homelab/scripts/gen_certs.sh <host-address> [more names ...]
 *   e2e/homelab/scripts/deploy.sh <ssh-host> <bind-address>
 *   eval "$(e2e/homelab/scripts/trust_material.sh <ssh-host> <host-address>)"
 *   ./gradlew :storage:test --tests '*Homelab*'
 * See `e2e/homelab/README.md`.
 */
object Homelab {
    val host: String? = System.getenv("KOPIA_HOMELAB_HOST")

    fun env(name: String): String = System.getenv(name)
        ?: error("$name is unset — run e2e/homelab/scripts/trust_material.sh")

    /** Skips (rather than fails) the test when the homelab profile is not configured. */
    fun requireProfile() {
        assumeTrue(!host.isNullOrBlank(), "KOPIA_HOMELAB_HOST unset — homelab profile not deployed")
    }

    fun sftpOptions(path: String, knownHosts: String = env("KOPIA_HOMELAB_SFTP_KNOWN_HOSTS")) = SftpOptions(
        host = env("KOPIA_HOMELAB_HOST"),
        port = env("KOPIA_HOMELAB_SFTP_PORT").toInt(),
        username = env("KOPIA_HOMELAB_SFTP_USER"),
        password = env("KOPIA_HOMELAB_SFTP_PASSWORD"),
        path = path,
        // The point of this profile: a REAL pinned host key, and no insecure opt-in anywhere.
        knownHostsData = knownHosts,
        insecureSkipHostKeyVerification = false,
    )

    fun webDavOptions(fingerprint: String = env("KOPIA_HOMELAB_WEBDAV_CERT_SHA256")) = WebDavOptions(
        url = env("KOPIA_HOMELAB_WEBDAV_URL"),
        username = env("KOPIA_HOMELAB_WEBDAV_USER"),
        password = env("KOPIA_HOMELAB_WEBDAV_PASSWORD"),
        trustedServerCertificateFingerprint = fingerprint,
        atomicWrites = false, // temp file + MOVE — the production default, and the interesting path
    )

    fun defaultRootCa(): ByteArray = File(env("KOPIA_HOMELAB_S3_ROOT_CA_FILE")).readBytes()

    fun s3Options(prefix: String, rootCa: ByteArray? = defaultRootCa()): S3Options {
        val bucket = env("KOPIA_HOMELAB_S3_BUCKET")
        return S3Options(
            bucketName = bucket,
            endpoint = env("KOPIA_HOMELAB_S3_ENDPOINT"),
            region = "us-east-1",
            accessKeyId = env("KOPIA_HOMELAB_S3_ACCESS_KEY"),
            secretAccessKey = env("KOPIA_HOMELAB_S3_SECRET_KEY"),
            prefix = prefix,
            rootCa = rootCa,
        )
    }

    fun uniqueSuffix(): String = UUID.randomUUID().toString().take(8)
}

@Tag("homelab")
@DisplayName("SFTP against the homelab host, verified by a pinned host key")
class SftpHomelabContractTest : BlobStorageContractTest() {

    override fun createStorage(): BlobStorage {
        Homelab.requireProfile()
        return runBlocking {
            // atmoz/sftp chroots the user to /home/<user>, so the writable tree is /upload.
            SftpBlobStorage.create(
                Homelab.sftpOptions("/upload/contract-${Homelab.uniqueSuffix()}"),
                isCreate = true,
            )
        }
    }

    override fun cleanupStorage(storage: BlobStorage) {
        runBlocking {
            try {
                storage.listBlobs("").toList().forEach { storage.deleteBlob(it.blobId) }
            } finally {
                storage.close()
            }
        }
    }
}

@Tag("homelab")
@DisplayName("WebDAV over https against the homelab host, verified by a pinned certificate")
class WebDavHomelabContractTest : BlobStorageContractTest() {

    override fun createStorage(): BlobStorage {
        Homelab.requireProfile()
        return runBlocking { WebDavBlobStorage.create(Homelab.webDavOptions(), isCreate = true) }
    }

    override fun cleanupStorage(storage: BlobStorage) {
        runBlocking {
            try {
                storage.listBlobs("").toList().forEach { storage.deleteBlob(it.blobId) }
            } finally {
                storage.close()
            }
        }
    }
}

@Tag("homelab")
@DisplayName("S3/MinIO over https against the homelab host, verified by a custom root CA")
class S3HomelabContractTest : BlobStorageContractTest() {

    override fun createStorage(): BlobStorage {
        Homelab.requireProfile()
        return runBlocking { S3BlobStorage.create(Homelab.s3Options("contract-${Homelab.uniqueSuffix()}/")) }
    }

    override fun cleanupStorage(storage: BlobStorage) {
        runBlocking {
            try {
                storage.listBlobs("").toList().forEach { storage.deleteBlob(it.blobId) }
            } finally {
                storage.close()
            }
        }
    }
}

/**
 * Negative controls — the tests that actually prove the secure paths are ENFORCED.
 *
 * The contract tests above would still pass if verification were silently disabled (they connect to
 * a server that is, after all, the real one). These assert that the wrong trust material, or none,
 * is REFUSED — which is the only way to distinguish "verified" from "happened to work".
 */
@Tag("homelab")
@DisplayName("Homelab security controls (negative)")
class HomelabSecurityControlsTest {

    @Test
    fun `SFTP refuses a host whose key does not match the pin`() {
        Homelab.requireProfile()
        // Corrupt the pinned key material: same shape, different key.
        val real = Homelab.env("KOPIA_HOMELAB_SFTP_KNOWN_HOSTS")
        val tampered = real.lines().first().let { line ->
            val parts = line.split(" ")
            val key = StringBuilder(parts[2])
            // Flip a character in the base64 body so it is a different, still well-formed key blob.
            key[10] = if (key[10] == 'A') 'B' else 'A'
            "${parts[0]} ${parts[1]} $key"
        }

        assertThrows<Exception> {
            runBlocking {
                SftpBlobStorage.create(
                    Homelab.sftpOptions("/upload/negative-${Homelab.uniqueSuffix()}", knownHosts = tampered),
                    isCreate = false,
                )
            }
        }
    }

    @Test
    fun `SFTP refuses to connect with no trust material at all`() {
        Homelab.requireProfile()
        // No known_hosts, no fingerprint, no insecure opt-in => must fail CLOSED, before auth.
        val options = Homelab.sftpOptions("/upload/negative-${Homelab.uniqueSuffix()}", knownHosts = "")
            .copy(knownHostsFile = "/dev/null/nonexistent")

        assertThrows<HostKeyNotTrustedException> {
            runBlocking { SftpBlobStorage.create(options, isCreate = false) }
        }
    }

    @Test
    fun `WebDAV refuses a server whose certificate does not match the pin`() {
        Homelab.requireProfile()
        // A valid-shaped but wrong pin: the handshake must fail rather than fall back to the
        // platform trust store (which would reject this self-signed cert anyway — so a PASS here
        // with the pin removed would mean pinning had been replaced by plain validation).
        val wrongPin = "0".repeat(64)

        assertThrows<Exception> {
            runBlocking {
                WebDavBlobStorage.create(Homelab.webDavOptions(fingerprint = wrongPin), isCreate = false)
            }
        }
    }

    @Test
    fun `S3 refuses the private-CA server when only the system trust store is used`() {
        Homelab.requireProfile()
        // Without rootCa the certificate is untrusted, so this must fail — proving the passing
        // contract run above genuinely depends on the custom trust anchor.
        assertThrows<Exception> {
            runBlocking {
                S3BlobStorage.create(Homelab.s3Options("negative-${Homelab.uniqueSuffix()}/", rootCa = null))
            }
        }
    }

    @Test
    fun `WebDAV round-trips a large blob through the TLS terminator`() = runTest {
        Homelab.requireProfile()
        // Exercises the reverse-proxy path at a realistic pack-blob size: the MOVE/Destination
        // rewrite and the proxy's request-body limits only misbehave on real payloads.
        val storage = WebDavBlobStorage.create(Homelab.webDavOptions(), isCreate = true)
        val blobId = BlobId("large-${Homelab.uniqueSuffix()}")
        val data = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }

        try {
            storage.putBlob(blobId, data)
            assertArrayEquals(data, storage.getBlob(blobId))
        } finally {
            try {
                storage.deleteBlob(blobId)
            } finally {
                storage.close()
            }
        }
    }
}

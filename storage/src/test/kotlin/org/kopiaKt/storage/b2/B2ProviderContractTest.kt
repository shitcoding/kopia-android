package org.kopiaKt.storage.b2

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobStorageContractTest
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.storage.s3.S3BlobStorage
import org.kopiaKt.storage.s3.S3Options
import java.util.UUID

/**
 * Opt-in tests against a REAL S3 provider — Backblaze B2 (task-23.8 AC #1).
 *
 * MinIO in Docker covers our S3 code against one implementation on localhost. What it cannot
 * reproduce is an actual hosted provider: TLS to a public endpoint through the system trust store,
 * a real region/endpoint pair, a scoped application key, request signing against a third-party
 * implementation of the S3 protocol, and that provider's own error responses.
 *
 * Skipped unless the `KOPIA_B2_*` environment is present, so CI and the normal gate are unaffected.
 * Credentials are read from the environment only — never committed, never defaulted. See
 * `e2e/b2/README.md`.
 *
 * Cost: a run performs on the order of a hundred small operations. B2 bills uploads as free Class A
 * transactions and gives a daily free allowance for the download/list classes, and the test data is
 * deleted as it goes, so a run against the always-free 10 GB tier costs nothing.
 */
object B2 {
    val bucket: String? = System.getenv("KOPIA_B2_BUCKET")

    fun env(name: String): String = System.getenv(name)
        ?: error("$name is unset — see e2e/b2/README.md")

    /** Skips (rather than fails) when the B2 profile is not configured. */
    fun requireProfile() {
        assumeTrue(!bucket.isNullOrBlank(), "KOPIA_B2_BUCKET unset — real-provider S3 tests skipped")
    }

    fun options(prefix: String, secretKey: String = env("KOPIA_B2_APP_KEY")): S3Options {
        val bucketName = env("KOPIA_B2_BUCKET")
        return S3Options(
            bucketName = bucketName,
            // A hosted https endpoint validated by the SYSTEM trust store — the default TLS path,
            // which the homelab profile deliberately bypasses with a private CA.
            endpoint = env("KOPIA_B2_ENDPOINT"),
            region = env("KOPIA_B2_REGION"),
            accessKeyId = env("KOPIA_B2_KEY_ID"),
            secretAccessKey = secretKey,
            prefix = prefix,
        )
    }

    /** Unique per run so concurrent or interrupted runs can never collide in a shared bucket. */
    fun uniquePrefix(): String = "contract-${UUID.randomUUID().toString().take(8)}/"
}

@Tag("b2")
@DisplayName("Backblaze B2 (real S3 provider)")
class B2ProviderContractTest : BlobStorageContractTest() {

    override fun createStorage(): BlobStorage {
        B2.requireProfile()
        return runBlocking { S3BlobStorage.create(B2.options(B2.uniquePrefix())) }
    }

    override fun cleanupStorage(storage: BlobStorage) {
        // Leaving objects behind would accrue storage on a real, billed account — so let a cleanup
        // failure surface rather than silently orphaning data.
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
 * Provider-specific behaviour that a local MinIO cannot vouch for.
 */
@Tag("b2")
@DisplayName("Backblaze B2 provider specifics")
class B2ProviderSpecificsTest {

    @Test
    fun `rejects a bad application key as invalid credentials, not an opaque error`() {
        B2.requireProfile()
        // Our S3 error taxonomy classifies by the provider's error CODE. B2 is a third-party
        // implementation of the S3 protocol, so this checks the classification holds against
        // something other than MinIO — a wrong key must surface as InvalidCredentialsException
        // (so the UI can say "check your credentials") rather than leaking a raw S3Exception.
        assertThrows<InvalidCredentialsException> {
            runBlocking {
                val storage = S3BlobStorage.create(
                    B2.options(B2.uniquePrefix(), secretKey = "0".repeat(31)),
                )
                // create() only reads .storageconfig, which may be absent; force a real signed call.
                storage.use { it.listBlobs("").toList() }
            }
        }
    }

    @Test
    fun `round-trips a blob over TLS validated by the system trust store`() = runTest {
        B2.requireProfile()
        // The default trust path against a public endpoint: no rootCa, no pinning, no cleartext.
        val storage = S3BlobStorage.create(B2.options(B2.uniquePrefix()))
        val blobId = BlobId("p${UUID.randomUUID().toString().replace("-", "")}")
        val data = ByteArray(64 * 1024) { (it % 251).toByte() }

        try {
            storage.putBlob(blobId, data)
            val roundTripped = storage.getBlob(blobId)
            check(roundTripped.contentEquals(data)) { "blob did not round-trip byte-for-byte" }

            // Ranged reads are the read path kopia actually uses to fetch content from a pack blob.
            val slice = storage.getBlob(blobId, offset = 1024, length = 256)
            check(slice.contentEquals(data.copyOfRange(1024, 1280))) { "ranged read returned wrong bytes" }
        } finally {
            try {
                storage.deleteBlob(blobId)
            } finally {
                storage.close()
            }
        }
    }
}

/** Closes the storage after [block], mirroring `use` for a non-Closeable suspend API. */
private suspend inline fun <T> BlobStorage.use(block: (BlobStorage) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

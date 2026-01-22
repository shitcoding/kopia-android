package org.kopiaKt.storage.sftp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.PutBlobOptions
import java.util.UUID

/**
 * Integration tests for SftpBlobStorage.
 *
 * These tests require a running SFTP server. Configure using environment variables:
 * - SFTP_TEST_HOST: SFTP server hostname (default: localhost)
 * - SFTP_TEST_PORT: SFTP server port (default: 22)
 * - SFTP_TEST_USERNAME: SSH username
 * - SFTP_TEST_PASSWORD: SSH password (for password auth)
 * - SFTP_TEST_KEYFILE: Path to SSH private key (for key auth)
 * - SFTP_TEST_PATH: Remote path for testing (default: /tmp/kopia-test)
 *
 * Tests are skipped if SFTP_TEST_USERNAME is not set.
 *
 * Example using Docker:
 * ```
 * docker run -d --name sftp-test \
 *   -p 2222:22 \
 *   -e SFTP_USERS=test:test:::upload \
 *   atmoz/sftp
 *
 * SFTP_TEST_HOST=localhost \
 * SFTP_TEST_PORT=2222 \
 * SFTP_TEST_USERNAME=test \
 * SFTP_TEST_PASSWORD=test \
 * SFTP_TEST_PATH=/upload/kopia-test \
 * ./gradlew :storage:test --tests "SftpBlobStorageIntegrationTest"
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SftpBlobStorageIntegrationTest {

    private val host = System.getenv("SFTP_TEST_HOST") ?: "localhost"
    private val port = System.getenv("SFTP_TEST_PORT")?.toIntOrNull() ?: 22
    private val username = System.getenv("SFTP_TEST_USERNAME") ?: ""
    private val password = System.getenv("SFTP_TEST_PASSWORD") ?: ""
    private val keyfile = System.getenv("SFTP_TEST_KEYFILE") ?: ""
    private val basePath = System.getenv("SFTP_TEST_PATH") ?: "/tmp/kopia-test"

    private lateinit var storage: SftpBlobStorage
    private val testPrefix = UUID.randomUUID().toString().take(8)

    @BeforeAll
    fun setupAll() {
        assumeTrue(username.isNotEmpty()) {
            "SFTP integration tests require SFTP_TEST_USERNAME environment variable"
        }
    }

    @BeforeEach
    fun setup() = runTest {
        val options = SftpOptions(
            path = "$basePath/$testPrefix",
            host = host,
            port = port,
            username = username,
            password = password,
            keyfile = keyfile,
            directoryShards = listOf(1, 3)
        )

        storage = SftpBlobStorage.create(options, isCreate = true)
    }

    @AfterAll
    fun teardownAll() = runTest {
        if (::storage.isInitialized) {
            // Clean up test files
            try {
                storage.listBlobs("").toList().forEach { metadata ->
                    storage.deleteBlob(metadata.blobId)
                }
            } catch (_: Exception) {
            }
            storage.close()
        }
    }

    @Test
    @Order(1)
    @DisplayName("put and get blob")
    fun putAndGetBlob() = runTest {
        val blobId = BlobId("test-blob")
        val data = "Hello, SFTP!".toByteArray()

        storage.putBlob(blobId, data)

        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    @Order(2)
    @DisplayName("put and get binary blob")
    fun putAndGetBinaryBlob() = runTest {
        val blobId = BlobId("binary-blob")
        val data = ByteArray(1024) { it.toByte() }

        storage.putBlob(blobId, data)

        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    @Order(3)
    @DisplayName("partial read")
    fun partialRead() = runTest {
        val blobId = BlobId("partial-blob")
        val data = "0123456789".toByteArray()

        storage.putBlob(blobId, data)

        val partial = storage.getBlob(blobId, offset = 3, length = 4)

        assertThat(partial).isEqualTo("3456".toByteArray())
    }

    @Test
    @Order(4)
    @DisplayName("get blob metadata")
    fun getBlobMetadata() = runTest {
        val blobId = BlobId("metadata-blob")
        val data = "metadata test".toByteArray()

        storage.putBlob(blobId, data)

        val metadata = storage.getBlobMetadata(blobId)

        assertThat(metadata).isNotNull()
        assertThat(metadata!!.blobId).isEqualTo(blobId)
        assertThat(metadata.length).isEqualTo(data.size.toLong())
    }

    @Test
    @Order(5)
    @DisplayName("returns null for non-existent blob metadata")
    fun nonExistentBlobMetadata() = runTest {
        val blobId = BlobId("non-existent-metadata")

        val metadata = storage.getBlobMetadata(blobId)

        assertThat(metadata).isNull()
    }

    @Test
    @Order(6)
    @DisplayName("delete blob")
    fun deleteBlob() = runTest {
        val blobId = BlobId("delete-blob")
        val data = "to be deleted".toByteArray()

        storage.putBlob(blobId, data)
        storage.deleteBlob(blobId)

        val metadata = storage.getBlobMetadata(blobId)
        assertThat(metadata).isNull()
    }

    @Test
    @Order(7)
    @DisplayName("dontOverwrite option")
    fun dontOverwrite() = runTest {
        val blobId = BlobId("dont-overwrite-blob")
        val originalData = "original".toByteArray()
        val newData = "new".toByteArray()

        storage.putBlob(blobId, originalData)
        storage.putBlob(blobId, newData, PutBlobOptions(dontOverwrite = true))

        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(originalData)
    }

    @Test
    @Order(8)
    @DisplayName("list blobs")
    fun listBlobs() = runTest {
        // Create some blobs
        storage.putBlob(BlobId("list-a"), "a".toByteArray())
        storage.putBlob(BlobId("list-b"), "b".toByteArray())
        storage.putBlob(BlobId("list-c"), "c".toByteArray())

        val allBlobs = storage.listBlobs("list-").toList()

        assertThat(allBlobs.map { it.blobId.value }).containsAtLeast("list-a", "list-b", "list-c")
    }

    @Test
    @Order(9)
    @DisplayName("sharded blob storage")
    fun shardedBlobStorage() = runTest {
        // Blob ID longer than 20 chars triggers sharding
        val blobId = BlobId("pack-abcdef1234567890abcdef")
        val data = "sharded content".toByteArray()

        storage.putBlob(blobId, data)

        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    @Order(10)
    @DisplayName("connection info")
    fun connectionInfo() = runTest {
        val info = storage.connectionInfo()

        assertThat(info.type).isEqualTo("sftp")
        assertThat(info.config["host"]).isEqualTo(host)
        assertThat(info.config["username"]).isEqualTo(username)
    }

    @Test
    @Order(11)
    @DisplayName("display name")
    fun displayName() = runTest {
        val name = storage.displayName()

        assertThat(name).contains("SFTP")
        assertThat(name).contains(username)
        assertThat(name).contains(host)
    }

    @Test
    @Order(12)
    @DisplayName("empty blob")
    fun emptyBlob() = runTest {
        val blobId = BlobId("empty-blob")
        val data = ByteArray(0)

        storage.putBlob(blobId, data)

        val result = storage.getBlob(blobId)

        assertThat(result).isEmpty()
    }
}

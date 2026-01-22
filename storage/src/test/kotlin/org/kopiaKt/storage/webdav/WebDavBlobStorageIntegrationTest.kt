package org.kopiaKt.storage.webdav

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom

/**
 * Integration tests for WebDavBlobStorage that require a real WebDAV server.
 *
 * These tests are skipped unless a WebDAV server is available.
 * Set the following environment variables to enable:
 * - WEBDAV_TEST_URL: Base URL of the WebDAV server (e.g., "http://localhost:8080/remote.php/dav/files/user/")
 * - WEBDAV_TEST_USERNAME: Username for authentication (optional)
 * - WEBDAV_TEST_PASSWORD: Password for authentication (optional)
 *
 * Example with Nextcloud:
 * ```
 * docker run -d -p 8080:80 nextcloud
 * # Create a user and folder, then:
 * export WEBDAV_TEST_URL="http://localhost:8080/remote.php/dav/files/user/kopia-test/"
 * export WEBDAV_TEST_USERNAME="user"
 * export WEBDAV_TEST_PASSWORD="password"
 * ```
 *
 * Example with Apache WebDAV:
 * ```
 * docker run -d -p 8080:80 -e USERNAME=user -e PASSWORD=password bytemark/webdav
 * export WEBDAV_TEST_URL="http://localhost:8080/"
 * export WEBDAV_TEST_USERNAME="user"
 * export WEBDAV_TEST_PASSWORD="password"
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebDavBlobStorageIntegrationTest {

    private var storage: WebDavBlobStorage? = null
    private var testPrefix: String = ""

    @BeforeAll
    fun checkEnvironment() {
        val url = System.getenv("WEBDAV_TEST_URL")
        assumeTrue(url != null && url.isNotEmpty()) {
            "Skipping WebDAV integration tests: WEBDAV_TEST_URL not set"
        }
    }

    @BeforeEach
    fun setup() = runTest {
        val url = System.getenv("WEBDAV_TEST_URL") ?: return@runTest
        val username = System.getenv("WEBDAV_TEST_USERNAME") ?: ""
        val password = System.getenv("WEBDAV_TEST_PASSWORD") ?: ""

        // Generate unique prefix for this test run to avoid conflicts
        testPrefix = "test-${System.currentTimeMillis()}-"

        val options = WebDavOptions(
            url = url,
            username = username,
            password = password,
            atomicWrites = false, // Use temp+rename for safety
            directoryShards = listOf(1, 3),
            maxNonShardedLength = 20
        )

        storage = WebDavBlobStorage.create(options, isCreate = true, readOnly = false)
    }

    @Test
    @Order(1)
    @DisplayName("Can put and get a blob")
    fun putAndGetBlob() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}simple-blob")
        val data = "Hello, WebDAV!".toByteArray()

        s.putBlob(blobId, data)
        val retrieved = s.getBlob(blobId)

        assertThat(retrieved).isEqualTo(data)

        // Cleanup
        s.deleteBlob(blobId)
    }

    @Test
    @Order(2)
    @DisplayName("Can put and get binary blob")
    fun putAndGetBinaryBlob() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}binary-blob")

        // Generate random binary data
        val random = SecureRandom()
        val data = ByteArray(1024)
        random.nextBytes(data)

        s.putBlob(blobId, data)
        val retrieved = s.getBlob(blobId)

        assertThat(retrieved).isEqualTo(data)

        // Cleanup
        s.deleteBlob(blobId)
    }

    @Test
    @Order(3)
    @DisplayName("Can get partial blob with offset")
    fun getPartialBlob() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}partial-blob")
        val data = "0123456789ABCDEF".toByteArray()

        s.putBlob(blobId, data)

        // Read from offset 5 to end
        val partial1 = s.getBlob(blobId, offset = 5)
        assertThat(String(partial1)).isEqualTo("56789ABCDEF")

        // Read specific range
        val partial2 = s.getBlob(blobId, offset = 5, length = 5)
        assertThat(String(partial2)).isEqualTo("56789")

        // Cleanup
        s.deleteBlob(blobId)
    }

    @Test
    @Order(4)
    @DisplayName("Can get blob metadata")
    fun getBlobMetadata() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}metadata-blob")
        val data = "metadata test".toByteArray()

        s.putBlob(blobId, data)
        val metadata = s.getBlobMetadata(blobId)

        assertThat(metadata).isNotNull()
        assertThat(metadata!!.blobId).isEqualTo(blobId)
        assertThat(metadata.length).isEqualTo(data.size.toLong())
        assertThat(metadata.timestamp).isNotNull()

        // Cleanup
        s.deleteBlob(blobId)
    }

    @Test
    @Order(5)
    @DisplayName("Returns null metadata for non-existent blob")
    fun getNonExistentBlobMetadata() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}non-existent")

        val metadata = s.getBlobMetadata(blobId)

        assertThat(metadata).isNull()
    }

    @Test
    @Order(6)
    @DisplayName("Throws BlobNotFoundException for non-existent blob")
    fun getNonExistentBlob() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}non-existent")

        assertThrows<BlobNotFoundException> {
            s.getBlob(blobId)
        }
    }

    @Test
    @Order(7)
    @DisplayName("Can delete blob")
    fun deleteBlob() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}to-delete")
        val data = "delete me".toByteArray()

        s.putBlob(blobId, data)

        // Verify blob exists
        assertThat(s.getBlobMetadata(blobId)).isNotNull()

        s.deleteBlob(blobId)

        // Verify blob no longer exists
        assertThat(s.getBlobMetadata(blobId)).isNull()
    }

    @Test
    @Order(8)
    @DisplayName("Delete non-existent blob does not throw")
    fun deleteNonExistentBlob() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}never-existed")

        // Should not throw
        s.deleteBlob(blobId)
    }

    @Test
    @Order(9)
    @DisplayName("dontOverwrite prevents overwriting existing blob")
    fun dontOverwrite() = runTest {
        val s = storage ?: return@runTest
        val blobId = BlobId("${testPrefix}dont-overwrite")
        val data1 = "original".toByteArray()
        val data2 = "replacement".toByteArray()

        s.putBlob(blobId, data1)
        s.putBlob(blobId, data2, PutBlobOptions(dontOverwrite = true))

        // Should still have original data
        val retrieved = s.getBlob(blobId)
        assertThat(retrieved).isEqualTo(data1)

        // Cleanup
        s.deleteBlob(blobId)
    }

    @Test
    @Order(10)
    @DisplayName("Can list blobs with prefix")
    fun listBlobs() = runTest {
        val s = storage ?: return@runTest

        // Create several blobs with common prefix
        val prefix = "${testPrefix}list-"
        val ids = listOf(
            BlobId("${prefix}blob1"),
            BlobId("${prefix}blob2"),
            BlobId("${prefix}blob3")
        )
        val otherBlob = BlobId("${testPrefix}other-blob")

        for (id in ids) {
            s.putBlob(id, "data".toByteArray())
        }
        s.putBlob(otherBlob, "other".toByteArray())

        // List with prefix
        val results = s.listBlobs(prefix).toList()

        assertThat(results.map { it.blobId.value }).containsExactlyElementsIn(ids.map { it.value })

        // Cleanup
        for (id in ids) {
            s.deleteBlob(id)
        }
        s.deleteBlob(otherBlob)
    }

    @Test
    @Order(11)
    @DisplayName("Sharding works for long blob IDs")
    fun shardedBlobs() = runTest {
        val s = storage ?: return@runTest

        // Create a blob ID that triggers sharding (length > 20)
        val blobId = BlobId("${testPrefix}pack-longhexstring12345678")
        val data = "sharded content".toByteArray()

        s.putBlob(blobId, data)
        val retrieved = s.getBlob(blobId)

        assertThat(retrieved).isEqualTo(data)

        // Verify metadata works too
        val metadata = s.getBlobMetadata(blobId)
        assertThat(metadata).isNotNull()
        assertThat(metadata!!.length).isEqualTo(data.size.toLong())

        // Cleanup
        s.deleteBlob(blobId)
    }

    @Test
    @Order(12)
    @DisplayName("Connection info and display name work")
    fun connectionInfo() = runTest {
        val s = storage ?: return@runTest

        val info = s.connectionInfo()
        assertThat(info.type).isEqualTo("webdav")
        assertThat(info.config["url"]).isNotEmpty()

        val displayName = s.displayName()
        assertThat(displayName).startsWith("WebDAV:")
    }

    @Test
    @Order(100)
    @DisplayName("Can close storage")
    fun closeStorage() = runTest {
        val s = storage ?: return@runTest
        s.close()
        // Storage is closed, don't use it after this
        storage = null
    }
}

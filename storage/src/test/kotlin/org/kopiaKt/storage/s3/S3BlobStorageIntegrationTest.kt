package org.kopiaKt.storage.s3

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobStorageContractTest
import org.kopiaKt.core.blob.PutBlobOptions
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import java.net.URI
import java.util.UUID

/**
 * Integration tests for S3BlobStorage using MinIO.
 *
 * These tests require a running MinIO instance. They are skipped if MinIO
 * is not available. To run these tests:
 *
 * 1. Start MinIO using Docker:
 *    docker run -p 9000:9000 -p 9001:9001 \
 *      -e MINIO_ROOT_USER=minioadmin \
 *      -e MINIO_ROOT_PASSWORD=minioadmin \
 *      minio/minio server /data --console-address ":9001"
 *
 * 2. Set environment variables (optional, defaults work with above command):
 *    export S3_TEST_ENDPOINT=localhost:9000
 *    export S3_TEST_ACCESS_KEY=minioadmin
 *    export S3_TEST_SECRET_KEY=minioadmin
 *
 * 3. Run the tests.
 *
 * To run these tests with contract tests, use S3BlobStorageContractIntegrationTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3BlobStorageIntegrationTest {

    companion object {
        private val endpoint = System.getenv("S3_TEST_ENDPOINT") ?: "localhost:9000"
        private val accessKey = System.getenv("S3_TEST_ACCESS_KEY") ?: "minioadmin"
        private val secretKey = System.getenv("S3_TEST_SECRET_KEY") ?: "minioadmin"
        private val bucketName = "kopia-kt-test-${UUID.randomUUID().toString().take(8)}"
    }

    private lateinit var syncClient: S3Client
    private lateinit var storage: S3BlobStorage
    private var minioAvailable = false

    @BeforeAll
    fun setupClass() {
        // Try to connect to MinIO
        try {
            syncClient = S3Client.builder()
                .endpointOverride(URI.create("http://$endpoint"))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                    )
                )
                .forcePathStyle(true)
                .build()

            // Test connection by listing buckets
            syncClient.listBuckets()
            minioAvailable = true

            // Create test bucket
            syncClient.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())
        } catch (e: Exception) {
            minioAvailable = false
        }
    }

    @AfterAll
    fun teardownClass() {
        if (minioAvailable) {
            try {
                // Delete all objects in bucket
                deleteAllObjectsInBucket()
                // Delete bucket
                syncClient.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            syncClient.close()
        }
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(minioAvailable, "MinIO is not available, skipping integration tests")

        runBlocking {
            storage = S3BlobStorage.create(
                S3Options(
                    bucketName = bucketName,
                    endpoint = endpoint,
                    accessKeyId = accessKey,
                    secretAccessKey = secretKey,
                    doNotUseTls = true,
                    region = "us-east-1"
                )
            )
        }
    }

    @AfterEach
    fun tearDown() {
        if (minioAvailable && ::storage.isInitialized) {
            runBlocking {
                // Clean up all test blobs
                deleteAllObjectsInBucket()
                storage.close()
            }
        }
    }

    private fun deleteAllObjectsInBucket() {
        val response = syncClient.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucketName).build()
        )
        if (response.contents().isNotEmpty()) {
            val objectIdentifiers = response.contents().map {
                ObjectIdentifier.builder().key(it.key()).build()
            }
            syncClient.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objectIdentifiers).build())
                    .build()
            )
        }
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    inner class CrudTests {

        @Test
        fun `should put and get blob`() = runTest {
            val blobId = BlobId("test-blob-001")
            val data = "Hello, MinIO!".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertArrayEquals(data, retrieved)
        }

        @Test
        fun `should handle empty blob`() = runTest {
            val blobId = BlobId("empty-blob")
            val data = ByteArray(0)

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertEquals(0, retrieved.size)
        }

        @Test
        fun `should handle binary data`() = runTest {
            val blobId = BlobId("binary-blob")
            val data = ByteArray(256) { it.toByte() }

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertArrayEquals(data, retrieved)
        }

        @Test
        fun `should handle large blob`() = runTest {
            val blobId = BlobId("large-blob")
            val data = ByteArray(5 * 1024 * 1024) { (it % 256).toByte() } // 5MB

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId)

            assertArrayEquals(data, retrieved)
        }

        @Test
        fun `should overwrite existing blob`() = runTest {
            val blobId = BlobId("overwrite-blob")
            val original = "original".toByteArray()
            val updated = "updated data".toByteArray()

            storage.putBlob(blobId, original)
            storage.putBlob(blobId, updated)
            val retrieved = storage.getBlob(blobId)

            assertArrayEquals(updated, retrieved)
        }

        @Test
        fun `should not overwrite when dontOverwrite is true`() = runTest {
            val blobId = BlobId("no-overwrite-blob")
            val original = "original".toByteArray()
            val newData = "new data".toByteArray()

            storage.putBlob(blobId, original)
            storage.putBlob(blobId, newData, PutBlobOptions(dontOverwrite = true))
            val retrieved = storage.getBlob(blobId)

            assertArrayEquals(original, retrieved)
        }

        @Test
        fun `should throw BlobNotFoundException for non-existent blob`() = runTest {
            val blobId = BlobId("non-existent")

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `should delete blob`() = runTest {
            val blobId = BlobId("to-delete")
            val data = "to be deleted".toByteArray()

            storage.putBlob(blobId, data)
            storage.deleteBlob(blobId)

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `should not throw when deleting non-existent blob`() = runTest {
            val blobId = BlobId("never-existed")

            // Should not throw
            storage.deleteBlob(blobId)
        }
    }

    @Nested
    @DisplayName("Partial reads")
    inner class PartialReadTests {

        @Test
        fun `should read with offset`() = runTest {
            val blobId = BlobId("partial-offset")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 5)

            assertEquals("56789", String(retrieved))
        }

        @Test
        fun `should read with length`() = runTest {
            val blobId = BlobId("partial-length")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 0, length = 5)

            assertEquals("01234", String(retrieved))
        }

        @Test
        fun `should read with offset and length`() = runTest {
            val blobId = BlobId("partial-both")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 3, length = 4)

            assertEquals("3456", String(retrieved))
        }

        @Test
        fun `should return empty for zero-length read`() = runTest {
            val blobId = BlobId("partial-zero")
            val data = "0123456789".toByteArray()

            storage.putBlob(blobId, data)
            val retrieved = storage.getBlob(blobId, offset = 5, length = 0)

            assertEquals(0, retrieved.size)
        }
    }

    @Nested
    @DisplayName("Metadata operations")
    inner class MetadataTests {

        @Test
        fun `should get metadata for existing blob`() = runTest {
            val blobId = BlobId("metadata-test")
            val data = "metadata test data".toByteArray()

            storage.putBlob(blobId, data)
            val metadata = storage.getBlobMetadata(blobId)

            assertNotNull(metadata)
            assertEquals(blobId, metadata!!.blobId)
            assertEquals(data.size.toLong(), metadata.length)
            assertNotNull(metadata.timestamp)
        }

        @Test
        fun `should return null for non-existent blob metadata`() = runTest {
            val blobId = BlobId("no-metadata")

            val metadata = storage.getBlobMetadata(blobId)

            assertNull(metadata)
        }
    }

    @Nested
    @DisplayName("List operations")
    inner class ListTests {

        @Test
        fun `should list all blobs`() = runTest {
            storage.putBlob(BlobId("list-a"), "a".toByteArray())
            storage.putBlob(BlobId("list-b"), "b".toByteArray())
            storage.putBlob(BlobId("list-c"), "c".toByteArray())

            val blobs = storage.listBlobs("list-").toList()

            assertEquals(3, blobs.size)
            assertTrue(blobs.all { it.blobId.value.startsWith("list-") })
        }

        @Test
        fun `should filter by prefix`() = runTest {
            storage.putBlob(BlobId("pack-001"), "a".toByteArray())
            storage.putBlob(BlobId("pack-002"), "b".toByteArray())
            storage.putBlob(BlobId("index-001"), "c".toByteArray())

            val packBlobs = storage.listBlobs("pack-").toList()

            assertEquals(2, packBlobs.size)
            assertTrue(packBlobs.all { it.blobId.value.startsWith("pack-") })
        }

        @Test
        fun `should return empty for non-matching prefix`() = runTest {
            storage.putBlob(BlobId("exists"), "data".toByteArray())

            val blobs = storage.listBlobs("nonexistent-").toList()

            assertTrue(blobs.isEmpty())
        }
    }

    @Nested
    @DisplayName("Connection info")
    inner class InfoTests {

        @Test
        fun `should return correct connection info`() {
            val info = storage.connectionInfo()

            assertEquals("s3", info.type)
            assertEquals(bucketName, info.config["bucket"])
        }

        @Test
        fun `should return display name with bucket`() {
            val name = storage.displayName()

            assertTrue(name.contains(bucketName))
            assertTrue(name.contains("S3"))
        }
    }
}

/**
 * Contract tests for S3BlobStorage with MinIO.
 *
 * This runs all the standard BlobStorage contract tests against a real
 * MinIO instance to ensure full compatibility.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3BlobStorageContractIntegrationTest : BlobStorageContractTest() {

    companion object {
        private val endpoint = System.getenv("S3_TEST_ENDPOINT") ?: "localhost:9000"
        private val accessKey = System.getenv("S3_TEST_ACCESS_KEY") ?: "minioadmin"
        private val secretKey = System.getenv("S3_TEST_SECRET_KEY") ?: "minioadmin"
    }

    private lateinit var syncClient: S3Client
    private var minioAvailable = false
    private var currentBucket: String? = null

    @BeforeAll
    fun setupClass() {
        try {
            syncClient = S3Client.builder()
                .endpointOverride(URI.create("http://$endpoint"))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                    )
                )
                .forcePathStyle(true)
                .build()

            syncClient.listBuckets()
            minioAvailable = true
        } catch (e: Exception) {
            minioAvailable = false
        }
    }

    @AfterAll
    fun teardownClass() {
        if (minioAvailable) {
            syncClient.close()
        }
    }

    override fun createStorage(): BlobStorage {
        assumeTrue(minioAvailable, "MinIO is not available, skipping integration tests")

        // Create unique bucket for each test
        currentBucket = "kopia-kt-contract-${UUID.randomUUID().toString().take(8)}"
        syncClient.createBucket(CreateBucketRequest.builder().bucket(currentBucket).build())

        return runBlocking {
            S3BlobStorage.create(
                S3Options(
                    bucketName = currentBucket!!,
                    endpoint = endpoint,
                    accessKeyId = accessKey,
                    secretAccessKey = secretKey,
                    doNotUseTls = true,
                    region = "us-east-1"
                )
            )
        }
    }

    override fun cleanupStorage(storage: BlobStorage) {
        if (minioAvailable && currentBucket != null) {
            try {
                // Delete all objects
                val response = syncClient.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(currentBucket).build()
                )
                if (response.contents().isNotEmpty()) {
                    val objectIdentifiers = response.contents().map {
                        ObjectIdentifier.builder().key(it.key()).build()
                    }
                    syncClient.deleteObjects(
                        DeleteObjectsRequest.builder()
                            .bucket(currentBucket)
                            .delete(Delete.builder().objects(objectIdentifiers).build())
                            .build()
                    )
                }
                // Delete bucket
                syncClient.deleteBucket(DeleteBucketRequest.builder().bucket(currentBucket).build())
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            currentBucket = null
        }
        runBlocking { storage.close() }
    }
}

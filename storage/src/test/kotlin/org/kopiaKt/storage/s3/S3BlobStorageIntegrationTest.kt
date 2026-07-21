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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobStorageContractTest
import org.kopiaKt.core.blob.PutBlobOptions
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
 * Integration tests for S3BlobStorage using MinIO via Testcontainers.
 *
 * These tests automatically start a MinIO container using Testcontainers.
 * They are skipped if Docker is not available on the host.
 *
 * Run with: ./gradlew :storage:integrationTest --tests "*S3BlobStorageIntegrationTest*"
 */
@Tag("integration")
@Tag("s3")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3BlobStorageIntegrationTest {

    companion object {
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"
        private const val REGION = "us-east-1"

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer("minio/minio:latest")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .waitingFor(
                HttpWaitStrategy()
                    .forPath("/minio/health/ready")
                    .forPort(9000),
            )
    }

    private val bucketName = "kopia-kt-test-${UUID.randomUUID().toString().take(8)}"
    private lateinit var syncClient: S3Client
    private lateinit var storage: S3BlobStorage

    private fun minioEndpoint(): String = "${minio.host}:${minio.getMappedPort(9000)}"

    @BeforeAll
    fun setupClass() {
        syncClient = S3Client.builder()
            .endpointOverride(URI.create("http://${minioEndpoint()}"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY),
                ),
            )
            .forcePathStyle(true)
            .build()

        syncClient.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())
    }

    @AfterAll
    fun teardownClass() {
        try {
            deleteAllObjectsInBucket()
            syncClient.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
        syncClient.close()
    }

    @BeforeEach
    fun setUp() {
        runBlocking {
            storage = S3BlobStorage.create(
                S3Options(
                    bucketName = bucketName,
                    endpoint = minioEndpoint(),
                    accessKeyId = ACCESS_KEY,
                    secretAccessKey = SECRET_KEY,
                    doNotUseTls = true,
                    region = REGION,
                ),
            )
        }
    }

    @AfterEach
    fun tearDown() {
        if (::storage.isInitialized) {
            runBlocking {
                deleteAllObjectsInBucket()
                storage.close()
            }
        }
    }

    private fun deleteAllObjectsInBucket() {
        val response = syncClient.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucketName).build(),
        )
        if (response.contents().isNotEmpty()) {
            val objectIdentifiers = response.contents().map {
                ObjectIdentifier.builder().key(it.key()).build()
            }
            syncClient.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objectIdentifiers).build())
                    .build(),
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

    @Nested
    @DisplayName("Extended operations")
    inner class ExtendedTests {

        @Test
        @DisplayName("listBlobs handles many objects correctly")
        fun listBlobs_handlesManyObjects() = runTest {
            val prefix = "p_"
            repeat(50) { i ->
                storage.putBlob(BlobId("${prefix}blob_${i.toString().padStart(4, '0')}"), "data$i".toByteArray())
            }
            val listed = storage.listBlobs(prefix).toList()
            assertEquals(50, listed.size)
        }

        @Test
        @DisplayName("create with doNotUseTls connects over HTTP")
        fun create_withDoNotUseTls_connectsOverHttp() = runTest {
            val s3 = S3BlobStorage.create(
                S3Options(
                    bucketName = bucketName,
                    endpoint = minioEndpoint(),
                    region = REGION,
                    accessKeyId = ACCESS_KEY,
                    secretAccessKey = SECRET_KEY,
                    doNotUseTls = true,
                ),
            )
            val blobs = s3.listBlobs("").toList()
            assertNotNull(blobs)
            s3.close()
        }
    }
}

/**
 * Contract tests for S3BlobStorage with MinIO via Testcontainers.
 *
 * This runs all the standard BlobStorage contract tests against a real
 * MinIO instance to ensure full compatibility. The container is managed
 * automatically by Testcontainers.
 */
@Tag("integration")
@Tag("s3")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3BlobStorageContractIntegrationTest : BlobStorageContractTest() {

    companion object {
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"
        private const val REGION = "us-east-1"

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer("minio/minio:latest")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .waitingFor(
                HttpWaitStrategy()
                    .forPath("/minio/health/ready")
                    .forPort(9000),
            )
    }

    private lateinit var syncClient: S3Client
    private var currentBucket: String? = null

    private fun minioEndpoint(): String = "${minio.host}:${minio.getMappedPort(9000)}"

    @BeforeAll
    fun setupClass() {
        syncClient = S3Client.builder()
            .endpointOverride(URI.create("http://${minioEndpoint()}"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY),
                ),
            )
            .forcePathStyle(true)
            .build()
    }

    @AfterAll
    fun teardownClass() {
        syncClient.close()
    }

    override fun createStorage(): BlobStorage {
        currentBucket = "kopia-kt-contract-${UUID.randomUUID().toString().take(8)}"
        syncClient.createBucket(CreateBucketRequest.builder().bucket(currentBucket).build())

        return runBlocking {
            S3BlobStorage.create(
                S3Options(
                    bucketName = currentBucket!!,
                    endpoint = minioEndpoint(),
                    accessKeyId = ACCESS_KEY,
                    secretAccessKey = SECRET_KEY,
                    doNotUseTls = true,
                    region = REGION,
                ),
            )
        }
    }

    override fun cleanupStorage(storage: BlobStorage) {
        if (currentBucket != null) {
            try {
                val response = syncClient.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(currentBucket).build(),
                )
                if (response.contents().isNotEmpty()) {
                    val objectIdentifiers = response.contents().map {
                        ObjectIdentifier.builder().key(it.key()).build()
                    }
                    syncClient.deleteObjects(
                        DeleteObjectsRequest.builder()
                            .bucket(currentBucket)
                            .delete(Delete.builder().objects(objectIdentifiers).build())
                            .build(),
                    )
                }
                syncClient.deleteBucket(DeleteBucketRequest.builder().bucket(currentBucket).build())
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
            currentBucket = null
        }
        runBlocking { storage.close() }
    }
}

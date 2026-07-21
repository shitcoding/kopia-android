package org.kopiaKt.storage.s3

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Instant

/**
 * Unit tests for S3BlobStorage using mocked S3 client.
 *
 * These tests verify the S3 storage implementation without requiring
 * an actual S3 service. Integration tests with MinIO are in S3BlobStorageIntegrationTest.
 */
class S3BlobStorageTest {

    private lateinit var mockClient: S3Client
    private lateinit var storage: S3BlobStorage
    private val testBucket = "test-bucket"
    private val testPrefix = "test-prefix/"

    @BeforeEach
    fun setUp() {
        mockClient = mockk(relaxed = true)
        storage = S3BlobStorage.createWithClient(
            client = mockClient,
            options = S3Options(
                bucketName = testBucket,
                prefix = testPrefix,
            ),
        )
    }

    @Nested
    @DisplayName("getBlob")
    inner class GetBlobTests {

        @Test
        fun `should retrieve blob successfully`() = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "Hello, World!".toByteArray()

            val responseBytes = mockk<ResponseBytes<GetObjectResponse>>()
            every { responseBytes.asByteArray() } returns expectedData

            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } returns responseBytes

            val result = storage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
        }

        @Test
        fun `should throw BlobNotFoundException when blob does not exist`() = runTest {
            val blobId = BlobId("non-existent")

            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } throws NoSuchKeyException.builder().message("Key not found").build()

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `should return empty array for zero-length read`() = runTest {
            val blobId = BlobId("test-blob")

            val result = storage.getBlob(blobId, offset = 0, length = 0)

            assertEquals(0, result.size)
        }

        @Test
        fun `should set range header for partial read with offset`() = runTest {
            val blobId = BlobId("test-blob")
            val requestSlot = slot<GetObjectRequest>()

            val responseBytes = mockk<ResponseBytes<GetObjectResponse>>()
            every { responseBytes.asByteArray() } returns "partial".toByteArray()

            every {
                mockClient.getObject(capture(requestSlot), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } returns responseBytes

            storage.getBlob(blobId, offset = 10, length = 5)

            assertEquals("bytes=10-14", requestSlot.captured.range())
        }

        @Test
        fun `should set open-ended range for offset without length`() = runTest {
            val blobId = BlobId("test-blob")
            val requestSlot = slot<GetObjectRequest>()

            val responseBytes = mockk<ResponseBytes<GetObjectResponse>>()
            every { responseBytes.asByteArray() } returns "rest".toByteArray()

            every {
                mockClient.getObject(capture(requestSlot), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } returns responseBytes

            storage.getBlob(blobId, offset = 100, length = -1)

            assertEquals("bytes=100-", requestSlot.captured.range())
        }
    }

    @Nested
    @DisplayName("getBlobMetadata")
    inner class GetBlobMetadataTests {

        @Test
        fun `should return metadata for existing blob`() = runTest {
            val blobId = BlobId("test-blob")
            val timestamp = Instant.now()

            val response = HeadObjectResponse.builder()
                .contentLength(1024L)
                .lastModified(timestamp)
                .build()

            every {
                mockClient.headObject(any<HeadObjectRequest>())
            } returns response

            val metadata = storage.getBlobMetadata(blobId)

            assertNotNull(metadata)
            assertEquals(blobId, metadata!!.blobId)
            assertEquals(1024L, metadata.length)
            assertEquals(timestamp, metadata.timestamp)
        }

        @Test
        fun `should return null for non-existent blob`() = runTest {
            val blobId = BlobId("non-existent")

            every {
                mockClient.headObject(any<HeadObjectRequest>())
            } throws NoSuchKeyException.builder().message("Not found").build()

            val metadata = storage.getBlobMetadata(blobId)

            assertNull(metadata)
        }
    }

    @Nested
    @DisplayName("putBlob")
    inner class PutBlobTests {

        @Test
        fun `should put blob successfully`() = runTest {
            val blobId = BlobId("new-blob")
            val data = "test data".toByteArray()
            val requestSlot = slot<PutObjectRequest>()

            every {
                mockClient.putObject(capture(requestSlot), any<RequestBody>())
            } returns PutObjectResponse.builder().build()

            storage.putBlob(blobId, data)

            assertEquals(testBucket, requestSlot.captured.bucket())
            assertEquals("${testPrefix}new-blob", requestSlot.captured.key())
            assertEquals("application/x-kopia", requestSlot.captured.contentType())
            assertEquals(data.size.toLong(), requestSlot.captured.contentLength())
        }

        @Test
        fun `should skip put when dontOverwrite is true and blob exists`() = runTest {
            val blobId = BlobId("existing-blob")
            val data = "test data".toByteArray()

            // Mock metadata check to return existing blob
            every {
                mockClient.headObject(any<HeadObjectRequest>())
            } returns HeadObjectResponse.builder()
                .contentLength(100L)
                .lastModified(Instant.now())
                .build()

            storage.putBlob(blobId, data, PutBlobOptions(dontOverwrite = true))

            // putObject should not be called
            coVerify(exactly = 0) {
                mockClient.putObject(any<PutObjectRequest>(), any<RequestBody>())
            }
        }

        @Test
        fun `should throw UnsupportedPutOptionException for setModTime`() = runTest {
            val blobId = BlobId("test-blob")
            val data = "test data".toByteArray()

            assertThrows<UnsupportedPutOptionException> {
                storage.putBlob(blobId, data, PutBlobOptions(setModTime = Instant.now()))
            }
        }
    }

    @Nested
    @DisplayName("deleteBlob")
    inner class DeleteBlobTests {

        @Test
        fun `should delete blob successfully`() = runTest {
            val blobId = BlobId("to-delete")
            val requestSlot = slot<DeleteObjectRequest>()

            every {
                mockClient.deleteObject(capture(requestSlot))
            } returns DeleteObjectResponse.builder().build()

            storage.deleteBlob(blobId)

            assertEquals(testBucket, requestSlot.captured.bucket())
            assertEquals("${testPrefix}to-delete", requestSlot.captured.key())
        }

        @Test
        fun `should not throw when deleting non-existent blob`() = runTest {
            val blobId = BlobId("non-existent")

            every {
                mockClient.deleteObject(any<DeleteObjectRequest>())
            } throws NoSuchKeyException.builder().message("Not found").build()

            // Should not throw
            storage.deleteBlob(blobId)
        }
    }

    @Nested
    @DisplayName("connectionInfo and displayName")
    inner class InfoTests {

        @Test
        fun `should return correct connection info`() {
            val info = storage.connectionInfo()

            assertEquals("s3", info.type)
            assertEquals(testBucket, info.config["bucket"])
            assertEquals(testPrefix, info.config["prefix"])
        }

        @Test
        fun `should return display name with bucket`() {
            val name = storage.displayName()

            assert(name.contains(testBucket))
            assert(name.contains("S3"))
        }
    }

    @Nested
    @DisplayName("Error handling")
    inner class ErrorHandlingTests {

        @Test
        fun `should throw InvalidCredentialsException for invalid access key`() = runTest {
            val blobId = BlobId("test-blob")

            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } throws S3Exception.builder()
                .message("InvalidAccessKeyId")
                .statusCode(403)
                .build()

            assertThrows<InvalidCredentialsException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `should throw InvalidBlobRangeException for a 416 range-not-satisfiable response`() = runTest {
            val blobId = BlobId("test-blob")

            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } throws S3Exception.builder()
                .message("Requested Range Not Satisfiable")
                .statusCode(416)
                .build()

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = 1_000_000, length = 10)
            }
        }

        @Test
        fun `should throw InvalidCredentialsException for expired token`() = runTest {
            val blobId = BlobId("test-blob")

            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } throws S3Exception.builder()
                .message("ExpiredToken")
                .statusCode(403)
                .build()

            assertThrows<InvalidCredentialsException> {
                storage.getBlob(blobId)
            }
        }
    }

    @Nested
    @DisplayName("Storage class configuration")
    inner class StorageClassTests {

        @Test
        fun `should use storage class from config for matching prefix`() = runTest {
            val storageWithConfig = S3BlobStorage.createWithClient(
                client = mockClient,
                options = S3Options(bucketName = testBucket, prefix = testPrefix),
                storageConfig = S3StorageConfig(
                    blobOptions = listOf(
                        PrefixAndStorageClass(prefix = "p", storageClass = "GLACIER"),
                        PrefixAndStorageClass(prefix = "n", storageClass = "STANDARD_IA"),
                    ),
                ),
            )

            val requestSlot = slot<PutObjectRequest>()

            every {
                mockClient.putObject(capture(requestSlot), any<RequestBody>())
            } returns PutObjectResponse.builder().build()

            storageWithConfig.putBlob(BlobId("pabc123"), "data".toByteArray())

            assertEquals("GLACIER", requestSlot.captured.storageClass()?.toString())
        }
    }

    @Nested
    @DisplayName("unsupported options")
    inner class UnsupportedOptionsTests {
        // These options were silently ignored; the backend now fails fast so a caller can't believe
        // they took effect (a security downgrade for rootCa, which the user would think pins/uses a
        // custom CA).
        @Test
        fun `rejects doNotVerifyTls`() {
            assertThrows<IllegalArgumentException> {
                S3BlobStorage.createWithClient(
                    mockClient,
                    S3Options(bucketName = testBucket, doNotVerifyTls = true),
                )
            }
        }

        @Test
        fun `rejects a custom rootCa`() {
            assertThrows<IllegalArgumentException> {
                S3BlobStorage.createWithClient(
                    mockClient,
                    S3Options(bucketName = testBucket, rootCa = byteArrayOf(1, 2, 3)),
                )
            }
        }

        @Test
        fun `rejects AssumeRole roleArn`() {
            assertThrows<IllegalArgumentException> {
                S3BlobStorage.createWithClient(
                    mockClient,
                    S3Options(bucketName = testBucket, roleArn = "arn:aws:iam::123:role/x"),
                )
            }
        }
    }

    @Nested
    @DisplayName("loadStorageConfig error handling")
    inner class LoadStorageConfigTests {
        private val opts = S3Options(bucketName = testBucket, prefix = testPrefix)

        @Test
        fun `surfaces auth errors instead of silently using defaults`() = runTest {
            // Reverting to the old broad catch would return defaults here (create() falsely succeeds
            // with bad creds); the fix lets the auth error propagate.
            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } throws S3Exception.builder().message("InvalidAccessKeyId").statusCode(403).build()

            assertThrows<S3Exception> {
                S3BlobStorage.loadStorageConfig(mockClient, opts)
            }
        }

        @Test
        fun `uses defaults when no config blob exists`() = runTest {
            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } throws NoSuchKeyException.builder().message("NoSuchKey").build()

            assertEquals(S3StorageConfig(), S3BlobStorage.loadStorageConfig(mockClient, opts))
        }

        @Test
        fun `uses defaults when the config blob is unparseable`() = runTest {
            val responseBytes = mockk<ResponseBytes<GetObjectResponse>> {
                every { asUtf8String() } returns "not valid json {{"
            }
            every {
                mockClient.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
            } returns responseBytes

            assertEquals(S3StorageConfig(), S3BlobStorage.loadStorageConfig(mockClient, opts))
        }
    }

    @Nested
    @DisplayName("read-only enforcement")
    inner class ReadOnlyTests {
        private fun readOnlyStorage() = S3BlobStorage.createWithClient(
            client = mockClient,
            options = S3Options(bucketName = testBucket, prefix = testPrefix),
            readOnly = true,
        )

        @Test
        fun `putBlob is rejected in read-only mode`() = runTest {
            assertThrows<IllegalStateException> {
                readOnlyStorage().putBlob(BlobId("ro"), "data".toByteArray())
            }
        }

        @Test
        fun `deleteBlob is rejected in read-only mode`() = runTest {
            assertThrows<IllegalStateException> {
                readOnlyStorage().deleteBlob(BlobId("ro"))
            }
        }
    }
}

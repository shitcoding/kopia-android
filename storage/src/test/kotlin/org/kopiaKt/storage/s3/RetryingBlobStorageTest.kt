package org.kopiaKt.storage.s3

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.RepositoryUnavailableException
import org.kopiaKt.storage.webdav.WebDavException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for RetryingBlobStorage.
 */
class RetryingBlobStorageTest {

    private lateinit var mockDelegate: BlobStorage
    private lateinit var retryingStorage: RetryingBlobStorage

    @BeforeEach
    fun setUp() {
        mockDelegate = mockk(relaxed = true)
        retryingStorage = RetryingBlobStorage(
            delegate = mockDelegate,
            maxRetries = 3,
            initialDelayMs = 10, // Short delays for tests
            maxDelayMs = 100,
        )
    }

    @Nested
    @DisplayName("Successful operations")
    inner class SuccessTests {

        /**
         * A destination that has gone must fail at once, not over a backoff schedule.
         *
         * `RepositoryUnavailableException` is only ever *not* retried by falling through
         * `isRetryable`'s final default — there is no clause naming it — so nothing has been pinning
         * the behaviour task-65 and task-69 both depend on. Broadening the IOException or
         * message-matching heuristics above that default would silently turn a dead destination into
         * ten backed-off attempts per blob, for the rest of the walk.
         */
        @Test
        fun `a destination that has gone is not retried`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val data = "test data".toByteArray()
            coEvery { mockDelegate.putBlob(blobId, data, any()) } throws
                RepositoryUnavailableException("The backup destination is gone: /nowhere")

            assertThrows<RepositoryUnavailableException> { retryingStorage.putBlob(blobId, data) }

            coVerify(exactly = 1) { mockDelegate.putBlob(blobId, data, any()) }
        }

        @Test
        fun `should pass through successful getBlob`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } returns expectedData

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
            coVerify(exactly = 1) { mockDelegate.getBlob(blobId, 0, -1) }
        }

        @Test
        fun `should pass through successful putBlob`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val data = "test data".toByteArray()

            coEvery { mockDelegate.putBlob(blobId, data, any()) } returns Unit

            retryingStorage.putBlob(blobId, data)

            coVerify(exactly = 1) { mockDelegate.putBlob(blobId, data, any()) }
        }

        @Test
        fun `should pass through successful deleteBlob`(): Unit = runTest {
            val blobId = BlobId("test-blob")

            coEvery { mockDelegate.deleteBlob(blobId) } returns Unit

            retryingStorage.deleteBlob(blobId)

            coVerify(exactly = 1) { mockDelegate.deleteBlob(blobId) }
        }

        @Test
        fun `should pass through getBlobMetadata`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val metadata = BlobMetadata(blobId, 100, Instant.now())

            coEvery { mockDelegate.getBlobMetadata(blobId) } returns metadata

            val result = retryingStorage.getBlobMetadata(blobId)

            assertEquals(metadata, result)
        }
    }

    @Nested
    @DisplayName("Retry behavior")
    inner class RetryTests {

        @Test
        fun `should retry on IOException`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 3) {
                    throw IOException("Connection reset")
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
            assertEquals(3, callCount.get())
        }

        @Test
        fun `should retry on SocketTimeoutException`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 2) {
                    throw SocketTimeoutException("Read timed out")
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
            assertEquals(2, callCount.get())
        }

        @Test
        fun `should retry on S3 503 Service Unavailable`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 2) {
                    throw S3Exception.builder()
                        .statusCode(503)
                        .message("Service Unavailable")
                        .build()
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
            assertEquals(2, callCount.get())
        }

        @Test
        fun `should retry on S3 500 Internal Server Error`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 2) {
                    throw S3Exception.builder()
                        .statusCode(500)
                        .message("Internal Server Error")
                        .build()
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
        }

        @Test
        fun `should retry on S3 429 Too Many Requests`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 2) {
                    throw S3Exception.builder()
                        .statusCode(429)
                        .message("Too Many Requests")
                        .build()
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
        }

        @Test
        fun `should retry on WebDAV 503 Service Unavailable`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 2) {
                    throw WebDavException("Service Unavailable", 503)
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
            assertEquals(2, callCount.get())
        }

        @Test
        fun `should retry on SFTP connection lost`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 2) {
                    throw SFTPException(Response.StatusCode.CONNECITON_LOST, "Connection lost")
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
            assertEquals(2, callCount.get())
        }

        @Test
        fun `should retry on WebDAV 408 Request Timeout`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val expectedData = "test data".toByteArray()
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                if (callCount.incrementAndGet() < 2) {
                    throw WebDavException("Request Timeout", 408)
                }
                expectedData
            }

            val result = retryingStorage.getBlob(blobId)

            assertArrayEquals(expectedData, result)
            assertEquals(2, callCount.get())
        }

        @Test
        fun `should exhaust retries and throw`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw IOException("Persistent failure")
            }

            assertThrows<IOException> {
                retryingStorage.getBlob(blobId)
            }

            // 1 initial + 3 retries = 4 total
            assertEquals(4, callCount.get())
        }
    }

    @Nested
    @DisplayName("Non-retryable errors")
    inner class NonRetryableTests {

        @Test
        fun `should not retry BlobNotFoundException`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw BlobNotFoundException(blobId)
            }

            assertThrows<BlobNotFoundException> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }

        @Test
        fun `should not retry InvalidCredentialsException`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw InvalidCredentialsException("Invalid credentials")
            }

            assertThrows<InvalidCredentialsException> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }

        @Test
        fun `should not retry S3 400 Bad Request`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw S3Exception.builder()
                    .statusCode(400)
                    .message("Bad Request")
                    .build()
            }

            assertThrows<S3Exception> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }

        @Test
        fun `should not retry S3 403 Forbidden`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw S3Exception.builder()
                    .statusCode(403)
                    .message("Access Denied")
                    .build()
            }

            assertThrows<S3Exception> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }

        @Test
        fun `should not retry S3 404 Not Found`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw S3Exception.builder()
                    .statusCode(404)
                    .message("Not Found")
                    .build()
            }

            assertThrows<S3Exception> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }

        @Test
        fun `should not retry SFTP permission denied`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
            }

            assertThrows<SFTPException> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }

        @Test
        fun `should not retry WebDAV 404 Not Found`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw WebDavException("Not Found", 404)
            }

            assertThrows<WebDavException> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }

        @Test
        fun `should not retry IllegalArgumentException`(): Unit = runTest {
            val blobId = BlobId("test-blob")
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.getBlob(blobId, 0, -1) } answers {
                callCount.incrementAndGet()
                throw IllegalArgumentException("Invalid argument")
            }

            assertThrows<IllegalArgumentException> {
                retryingStorage.getBlob(blobId)
            }

            assertEquals(1, callCount.get())
        }
    }

    @Nested
    @DisplayName("Passthrough methods")
    inner class PassthroughTests {

        @Test
        fun `should passthrough connectionInfo`() {
            val info = ConnectionInfo("s3", mapOf("bucket" to "test"))
            coEvery { mockDelegate.connectionInfo() } returns info

            val result = retryingStorage.connectionInfo()

            assertEquals(info, result)
        }

        @Test
        fun `should passthrough displayName`() {
            coEvery { mockDelegate.displayName() } returns "Test Storage"

            val result = retryingStorage.displayName()

            assertEquals("Test Storage", result)
        }

        @Test
        fun `should passthrough isReadOnly`() {
            coEvery { mockDelegate.isReadOnly() } returns true

            val result = retryingStorage.isReadOnly()

            assertEquals(true, result)
        }

        @Test
        fun `should passthrough close`(): Unit = runTest {
            coEvery { mockDelegate.close() } returns Unit

            retryingStorage.close()

            coVerify(exactly = 1) { mockDelegate.close() }
        }
    }

    @Nested
    @DisplayName("List blobs with retry")
    inner class ListBlobsRetryTests {

        @Test
        fun `should retry flow on error`(): Unit = runTest {
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.listBlobs("test-") } answers {
                flow {
                    if (callCount.incrementAndGet() < 2) {
                        throw IOException("Network error")
                    }
                    emit(BlobMetadata(BlobId("test-1"), 100, Instant.now()))
                }
            }

            val result = retryingStorage.listBlobs("test-").toList()

            assertEquals(1, result.size)
        }

        @Test
        fun `should not double-emit already-listed blobs when retrying`(): Unit = runTest {
            // The upstream emits a,b then fails once; on retry it emits a,b,c. The wrapper must NOT
            // hand the collector a,b,a,b,c — a mid-stream retry cannot re-emit already-seen blobs.
            val callCount = AtomicInteger(0)

            coEvery { mockDelegate.listBlobs("test-") } returns flow {
                val attempt = callCount.incrementAndGet()
                emit(BlobMetadata(BlobId("test-a"), 1, Instant.now()))
                emit(BlobMetadata(BlobId("test-b"), 1, Instant.now()))
                if (attempt < 2) throw IOException("mid-stream failure")
                emit(BlobMetadata(BlobId("test-c"), 1, Instant.now()))
            }

            val ids = retryingStorage.listBlobs("test-").toList().map { it.blobId.value }

            assertEquals(listOf("test-a", "test-b", "test-c"), ids)
        }
    }

    @Nested
    @DisplayName("Wrap helper")
    inner class WrapTests {

        @Test
        fun `should create retrying wrapper with defaults`() {
            val wrapped = RetryingBlobStorage.wrap(mockDelegate)

            assertEquals(mockDelegate.connectionInfo(), wrapped.connectionInfo())
        }

        @Test
        fun `should create retrying wrapper with custom config`() {
            val wrapped = RetryingBlobStorage.wrap(
                mockDelegate,
                maxRetries = 5,
                initialDelayMs = 50,
                maxDelayMs = 5000,
            )

            assertEquals(mockDelegate.connectionInfo(), wrapped.connectionInfo())
        }
    }
}

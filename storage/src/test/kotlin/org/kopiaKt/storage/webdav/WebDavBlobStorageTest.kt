package org.kopiaKt.storage.webdav

import com.github.sardine.DavResource
import com.github.sardine.Sardine
import com.github.sardine.impl.SardineException
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import org.kopiaKt.core.blob.RetentionMode
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.util.Date

private const val HTTP_RANGE_NOT_SATISFIABLE = 416

class WebDavBlobStorageTest {

    private lateinit var mockClient: Sardine
    private lateinit var storage: WebDavBlobStorage
    private val baseUrl = "https://example.com/dav/"
    private val options = WebDavOptions(url = baseUrl)
    private val shardingParams = ShardingParameters(
        default = listOf(1, 3),
        maxNonShardedLength = 20
    )

    @BeforeEach
    fun setup() {
        mockClient = mockk(relaxed = true)
        storage = WebDavBlobStorage.createWithClient(
            client = mockClient,
            options = options,
            shardingParams = shardingParams,
            readOnly = false
        )
    }

    @Nested
    @DisplayName("getBlob")
    inner class GetBlobTests {

        @Test
        fun `returns blob data for simple blob ID`() = runTest {
            val blobId = BlobId("short")
            val expectedData = "test data".toByteArray()

            every { mockClient.get("${baseUrl}short.f") } returns ByteArrayInputStream(expectedData)

            val result = storage.getBlob(blobId)

            assertThat(result).isEqualTo(expectedData)
        }

        @Test
        fun `returns blob data for sharded blob ID`() = runTest {
            // Blob ID longer than maxNonShardedLength (20) triggers sharding
            val blobId = BlobId("pack-abcdef1234567890abcdef")
            val expectedData = "sharded test data".toByteArray()

            // With shards [1, 3], "pack-abcdef1234567890abcdef" becomes:
            // dir: "p/ack" file: "-abcdef1234567890abcdef.f"
            every {
                mockClient.get("${baseUrl}p/ack/-abcdef1234567890abcdef.f")
            } returns ByteArrayInputStream(expectedData)

            val result = storage.getBlob(blobId)

            assertThat(result).isEqualTo(expectedData)
        }

        @Test
        fun `returns partial blob data with offset`() = runTest {
            val blobId = BlobId("short")
            val expectedData = "partial".toByteArray()

            every {
                mockClient.get("${baseUrl}short.f", mapOf("Range" to "bytes=5-"))
            } returns ByteArrayInputStream(expectedData)

            val result = storage.getBlob(blobId, offset = 5)

            assertThat(result).isEqualTo(expectedData)
        }

        @Test
        fun `returns partial blob data with offset and length`() = runTest {
            val blobId = BlobId("short")
            val expectedData = "art".toByteArray()

            every {
                mockClient.get("${baseUrl}short.f", mapOf("Range" to "bytes=5-7"))
            } returns ByteArrayInputStream(expectedData)

            val result = storage.getBlob(blobId, offset = 5, length = 3)

            assertThat(result).isEqualTo(expectedData)
        }

        @Test
        fun `returns empty array for zero-length read`() = runTest {
            val blobId = BlobId("short")

            every {
                mockClient.get("${baseUrl}short.f", mapOf("Range" to "bytes=0-0"))
            } returns ByteArrayInputStream(byteArrayOf(0))

            val result = storage.getBlob(blobId, offset = 0, length = 0)

            assertThat(result).isEmpty()
        }

        @Test
        fun `throws BlobNotFoundException when blob does not exist`() = runTest {
            val blobId = BlobId("missing")

            every { mockClient.get("${baseUrl}missing.f") } throws
                SardineException("Not Found", HttpURLConnection.HTTP_NOT_FOUND, null)

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `throws InvalidBlobRangeException for invalid range`() = runTest {
            val blobId = BlobId("short")

            every {
                mockClient.get("${baseUrl}short.f", mapOf("Range" to "bytes=1000-"))
            } throws SardineException(
                "Range Not Satisfiable",
                HTTP_RANGE_NOT_SATISFIABLE,
                null
            )

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = 1000)
            }
        }

        @Test
        fun `throws InvalidBlobRangeException for negative offset`() = runTest {
            val blobId = BlobId("short")

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = -1)
            }
        }

        @Test
        fun `throws InvalidCredentialsException for auth failure`() = runTest {
            val blobId = BlobId("short")

            every { mockClient.get("${baseUrl}short.f") } throws
                SardineException("Unauthorized", HttpURLConnection.HTTP_UNAUTHORIZED, null)

            assertThrows<InvalidCredentialsException> {
                storage.getBlob(blobId)
            }
        }
    }

    @Nested
    @DisplayName("getBlobMetadata")
    inner class GetBlobMetadataTests {

        @Test
        fun `returns metadata for existing blob`() = runTest {
            val blobId = BlobId("short")
            val modTime = Date.from(Instant.parse("2024-01-15T10:30:00Z"))

            val mockResource = mockk<DavResource> {
                every { contentLength } returns 1234L
                every { modified } returns modTime
            }

            every { mockClient.list("${baseUrl}short.f", 0) } returns listOf(mockResource)

            val result = storage.getBlobMetadata(blobId)

            assertThat(result).isNotNull()
            assertThat(result!!.blobId).isEqualTo(blobId)
            assertThat(result.length).isEqualTo(1234L)
            assertThat(result.timestamp).isEqualTo(modTime.toInstant())
        }

        @Test
        fun `returns null for non-existent blob`() = runTest {
            val blobId = BlobId("missing")

            every { mockClient.list("${baseUrl}missing.f", 0) } throws
                SardineException("Not Found", HttpURLConnection.HTTP_NOT_FOUND, null)

            val result = storage.getBlobMetadata(blobId)

            assertThat(result).isNull()
        }

        @Test
        fun `returns null when list returns empty`() = runTest {
            val blobId = BlobId("empty")

            every { mockClient.list("${baseUrl}empty.f", 0) } returns emptyList()

            val result = storage.getBlobMetadata(blobId)

            assertThat(result).isNull()
        }
    }

    @Nested
    @DisplayName("putBlob")
    inner class PutBlobTests {

        @Test
        fun `writes blob data successfully`() = runTest {
            val blobId = BlobId("new")
            val data = "new content".toByteArray()

            val urlSlot = slot<String>()
            every {
                mockClient.put(capture(urlSlot), any<ByteArray>(), any<String>())
            } just Runs
            every {
                mockClient.move(any(), any(), true)
            } just Runs

            storage.putBlob(blobId, data)

            // Since atomicWrites is false by default, it should write to temp and rename
            assertThat(urlSlot.captured).startsWith("${baseUrl}new.f-")
            verify { mockClient.move(any(), "${baseUrl}new.f", true) }
        }

        @Test
        fun `writes blob atomically when atomicWrites is true`() = runTest {
            val atomicOptions = WebDavOptions(url = baseUrl, atomicWrites = true)
            val atomicStorage = WebDavBlobStorage.createWithClient(
                client = mockClient,
                options = atomicOptions,
                shardingParams = shardingParams,
                readOnly = false
            )

            val blobId = BlobId("atomic")
            val data = "atomic content".toByteArray()

            every {
                mockClient.put("${baseUrl}atomic.f", any<ByteArray>(), any<String>())
            } just Runs

            atomicStorage.putBlob(blobId, data)

            verify(exactly = 0) { mockClient.move(any(), any(), any()) }
        }

        @Test
        fun `creates parent directories when write fails`() = runTest {
            val blobId = BlobId("pack-abcdef1234567890abcdef")
            val data = "data".toByteArray()

            val callCount = mutableListOf<Int>()

            every {
                mockClient.put(any<String>(), any<ByteArray>(), any<String>())
            } answers {
                if (callCount.isEmpty()) {
                    callCount.add(1)
                    throw SardineException("Not Found", HttpURLConnection.HTTP_NOT_FOUND, null)
                }
            }

            every { mockClient.list("${baseUrl}p/", 0) } throws
                SardineException("Not Found", HttpURLConnection.HTTP_NOT_FOUND, null)
            every { mockClient.createDirectory("${baseUrl}p/") } just Runs

            every { mockClient.list("${baseUrl}p/ack/", 0) } throws
                SardineException("Not Found", HttpURLConnection.HTTP_NOT_FOUND, null)
            every { mockClient.createDirectory("${baseUrl}p/ack/") } just Runs

            every { mockClient.move(any(), any(), true) } just Runs

            storage.putBlob(blobId, data)

            verify { mockClient.createDirectory("${baseUrl}p/") }
            verify { mockClient.createDirectory("${baseUrl}p/ack/") }
        }

        @Test
        fun `skips write when blob exists and dontOverwrite is true`() = runTest {
            val blobId = BlobId("existing")
            val data = "data".toByteArray()

            val mockResource = mockk<DavResource>()
            every { mockClient.list("${baseUrl}existing.f", 0) } returns listOf(mockResource)

            storage.putBlob(blobId, data, PutBlobOptions(dontOverwrite = true))

            verify(exactly = 0) { mockClient.put(any<String>(), any<ByteArray>(), any<String>()) }
        }

        @Test
        fun `throws UnsupportedPutOptionException for retention options`() = runTest {
            val blobId = BlobId("blob")
            val data = "data".toByteArray()

            assertThrows<UnsupportedPutOptionException> {
                storage.putBlob(blobId, data, PutBlobOptions(
                    retentionMode = RetentionMode.GOVERNANCE,
                    retentionPeriod = Duration.ofDays(1)
                ))
            }
        }

        @Test
        fun `throws UnsupportedPutOptionException for setModTime`() = runTest {
            val blobId = BlobId("blob")
            val data = "data".toByteArray()

            assertThrows<UnsupportedPutOptionException> {
                storage.putBlob(blobId, data, PutBlobOptions(setModTime = Instant.now()))
            }
        }
    }

    @Nested
    @DisplayName("deleteBlob")
    inner class DeleteBlobTests {

        @Test
        fun `deletes existing blob`() = runTest {
            val blobId = BlobId("todelete")

            every { mockClient.delete("${baseUrl}todelete.f") } just Runs

            storage.deleteBlob(blobId)

            verify { mockClient.delete("${baseUrl}todelete.f") }
        }

        @Test
        fun `ignores delete for non-existent blob`() = runTest {
            val blobId = BlobId("missing")

            every { mockClient.delete("${baseUrl}missing.f") } throws
                SardineException("Not Found", HttpURLConnection.HTTP_NOT_FOUND, null)

            // Should not throw
            storage.deleteBlob(blobId)
        }

        @Test
        fun `deletes sharded blob`() = runTest {
            val blobId = BlobId("pack-abcdef1234567890abcdef")

            every { mockClient.delete("${baseUrl}p/ack/-abcdef1234567890abcdef.f") } just Runs

            storage.deleteBlob(blobId)

            verify { mockClient.delete("${baseUrl}p/ack/-abcdef1234567890abcdef.f") }
        }
    }

    @Nested
    @DisplayName("listBlobs")
    inner class ListBlobsTests {

        @Test
        fun `lists blobs with prefix`() = runTest {
            val modTime = Date.from(Instant.parse("2024-01-15T10:30:00Z"))

            // Root directory listing
            val rootDir = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}")
                every { isDirectory } returns true
            }
            val subDir = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}p/")
                every { name } returns "p"
                every { isDirectory } returns true
            }

            every { mockClient.list(baseUrl, 1) } returns listOf(rootDir, subDir)

            // p/ directory listing
            val pDir = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}p/")
                every { isDirectory } returns true
            }
            val ackDir = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}p/ack/")
                every { name } returns "ack"
                every { isDirectory } returns true
            }

            every { mockClient.list("${baseUrl}p/", 1) } returns listOf(pDir, ackDir)

            // p/ack/ directory listing
            val ackDirSelf = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}p/ack/")
                every { isDirectory } returns true
            }
            val blob1 = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}p/ack/-blob1.f")
                every { name } returns "-blob1.f"
                every { isDirectory } returns false
                every { contentLength } returns 100L
                every { modified } returns modTime
            }
            val blob2 = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}p/ack/-blob2.f")
                every { name } returns "-blob2.f"
                every { isDirectory } returns false
                every { contentLength } returns 200L
                every { modified } returns modTime
            }

            every { mockClient.list("${baseUrl}p/ack/", 1) } returns listOf(ackDirSelf, blob1, blob2)

            val results = storage.listBlobs("pack-").toList()

            assertThat(results).hasSize(2)
            assertThat(results.map { it.blobId.value }).containsExactly("pack-blob1", "pack-blob2")
        }

        @Test
        fun `returns empty list when directory does not exist`() = runTest {
            every { mockClient.list(baseUrl, 1) } throws
                SardineException("Not Found", HttpURLConnection.HTTP_NOT_FOUND, null)

            val results = storage.listBlobs("anything").toList()

            assertThat(results).isEmpty()
        }

        @Test
        fun `ignores files without complete blob suffix`() = runTest {
            val rootDir = mockk<DavResource> {
                every { href } returns java.net.URI(baseUrl)
                every { isDirectory } returns true
            }
            val validFile = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}valid.f")
                every { name } returns "valid.f"
                every { isDirectory } returns false
                every { contentLength } returns 100L
                every { modified } returns Date()
            }
            val invalidFile = mockk<DavResource> {
                every { href } returns java.net.URI("${baseUrl}.shards")
                every { name } returns ".shards"
                every { isDirectory } returns false
            }

            every { mockClient.list(baseUrl, 1) } returns listOf(rootDir, validFile, invalidFile)

            val results = storage.listBlobs("").toList()

            assertThat(results).hasSize(1)
            assertThat(results[0].blobId.value).isEqualTo("valid")
        }
    }

    @Nested
    @DisplayName("connectionInfo and displayName")
    inner class ConnectionInfoTests {

        @Test
        fun `returns correct connection info`() {
            val info = storage.connectionInfo()

            assertThat(info.type).isEqualTo("webdav")
            assertThat(info.config["url"]).isEqualTo(baseUrl)
        }

        @Test
        fun `includes username in connection info when set`() {
            val authOptions = WebDavOptions(url = baseUrl, username = "user")
            val authStorage = WebDavBlobStorage.createWithClient(
                client = mockClient,
                options = authOptions,
                shardingParams = shardingParams,
                readOnly = false
            )

            val info = authStorage.connectionInfo()

            assertThat(info.config["username"]).isEqualTo("user")
        }

        @Test
        fun `returns correct display name`() {
            assertThat(storage.displayName()).isEqualTo("WebDAV: $baseUrl")
        }
    }

    @Nested
    @DisplayName("isReadOnly")
    inner class ReadOnlyTests {

        @Test
        fun `returns false when not read-only`() {
            assertThat(storage.isReadOnly()).isFalse()
        }

        @Test
        fun `returns true when read-only`() {
            val readOnlyStorage = WebDavBlobStorage.createWithClient(
                client = mockClient,
                options = options,
                shardingParams = shardingParams,
                readOnly = true
            )

            assertThat(readOnlyStorage.isReadOnly()).isTrue()
        }
    }

    @Nested
    @DisplayName("sharding")
    inner class ShardingTests {

        @Test
        fun `short blob IDs are not sharded`() = runTest {
            val blobId = BlobId("short") // Length 5 < 20
            val data = "data".toByteArray()

            val urlSlot = slot<String>()
            every {
                mockClient.put(capture(urlSlot), any<ByteArray>(), any<String>())
            } just Runs
            every { mockClient.move(any(), any(), true) } just Runs

            storage.putBlob(blobId, data)

            // Temp file should be at root, not sharded
            assertThat(urlSlot.captured).startsWith("${baseUrl}short.f-")
            verify { mockClient.move(any(), "${baseUrl}short.f", true) }
        }

        @Test
        fun `long blob IDs are sharded`() = runTest {
            // ID length 25 > 20, so will be sharded with [1, 3]
            val blobId = BlobId("pack-abcdef1234567890abc")
            val data = "data".toByteArray()

            val urlSlot = slot<String>()
            every {
                mockClient.put(capture(urlSlot), any<ByteArray>(), any<String>())
            } just Runs
            every { mockClient.move(any(), any(), true) } just Runs

            storage.putBlob(blobId, data)

            // With shards [1, 3]: first 1 char ("p"), then 3 chars ("ack")
            // Remaining: "-abcdef1234567890abc"
            assertThat(urlSlot.captured).startsWith("${baseUrl}p/ack/-abcdef1234567890abc.f-")
        }

        @Test
        fun `uses prefix override shards when matching`() = runTest {
            val customParams = ShardingParameters(
                default = listOf(1, 3),
                maxNonShardedLength = 5,
                overrides = listOf(
                    PrefixShards(prefix = "index", shards = listOf(2, 2))
                )
            )

            val customStorage = WebDavBlobStorage.createWithClient(
                client = mockClient,
                options = options,
                shardingParams = customParams,
                readOnly = false
            )

            val blobId = BlobId("index-12345")
            val data = "data".toByteArray()

            val urlSlot = slot<String>()
            every {
                mockClient.put(capture(urlSlot), any<ByteArray>(), any<String>())
            } just Runs
            every { mockClient.move(any(), any(), true) } just Runs

            customStorage.putBlob(blobId, data)

            // With prefix override shards [2, 2]: first 2 chars ("in"), then 2 chars ("de")
            // Remaining: "x-12345"
            assertThat(urlSlot.captured).startsWith("${baseUrl}in/de/x-12345.f-")
        }
    }

    @Nested
    @DisplayName("close")
    inner class CloseTests {

        @Test
        fun `calls shutdown on client`() = runTest {
            every { mockClient.shutdown() } just Runs

            storage.close()

            verify { mockClient.shutdown() }
        }
    }
}

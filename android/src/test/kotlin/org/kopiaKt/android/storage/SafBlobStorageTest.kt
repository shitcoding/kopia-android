package org.kopiaKt.android.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.RetentionMode
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for SafBlobStorage.
 *
 * Uses MockK to mock Android's DocumentFile and ContentResolver.
 * Uses Robolectric to provide Android framework stubs.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class SafBlobStorageTest {

    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver
    private lateinit var mockRootDocument: DocumentFile
    private lateinit var storage: SafBlobStorage

    private lateinit var testUri: Uri
    private lateinit var options: SafOptions
    private lateinit var shardingParams: SafShardingParameters

    @BeforeEach
    fun setup() {
        testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKopia")

        options = SafOptions(
            treeUri = testUri,
            directoryShards = listOf(1),
            maxNonShardedLength = 20,
        )
        shardingParams = SafShardingParameters(
            default = listOf(1),
            maxNonShardedLength = 20,
        )

        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        mockRootDocument = mockk(relaxed = true)

        every { mockContext.contentResolver } returns mockContentResolver

        // Mock DocumentFile.fromTreeUri
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(mockContext, testUri) } returns mockRootDocument
        every { mockRootDocument.uri } returns testUri

        // Create storage instance for testing (skip permission check)
        storage = SafBlobStorage.createForTesting(
            context = mockContext,
            treeUri = testUri,
            options = options,
            shardingParams = shardingParams,
            skipPermissionCheck = true,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("getBlob")
    inner class GetBlobTests {

        @Test
        fun `returns blob data for simple blob ID`() = runTest {
            val blobId = BlobId("short")
            val expectedData = "test data".toByteArray()

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/short.f")
            }

            every { mockRootDocument.findFile("short.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream(expectedData)

            val result = storage.getBlob(blobId)

            assertThat(result).isEqualTo(expectedData)
        }

        @Test
        fun `returns blob data for sharded blob ID`() = runTest {
            // Blob ID longer than maxNonShardedLength (20) triggers sharding
            val blobId = BlobId("pack-abcdef1234567890abcdef")
            val expectedData = "sharded test data".toByteArray()

            // With shards [1], "pack-abcdef1234567890abcdef" becomes:
            // dir: "p", file: "ack-abcdef1234567890abcdef.f"
            val mockShardDir = mockk<DocumentFile> {
                every { isDirectory } returns true
            }
            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/p/ack-abcdef1234567890abcdef.f")
            }

            every { mockRootDocument.findFile("p") } returns mockShardDir
            every { mockShardDir.findFile("ack-abcdef1234567890abcdef.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream(expectedData)

            val result = storage.getBlob(blobId)

            assertThat(result).isEqualTo(expectedData)
        }

        @Test
        fun `returns partial blob data with offset`() = runTest {
            val blobId = BlobId("short")
            val fullData = "test data for partial read".toByteArray()

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/short.f")
            }

            every { mockRootDocument.findFile("short.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream(fullData)

            val result = storage.getBlob(blobId, offset = 5)

            assertThat(result).isEqualTo("data for partial read".toByteArray())
        }

        @Test
        fun `returns partial blob data with offset and length`() = runTest {
            val blobId = BlobId("short")
            val fullData = "test data for partial read".toByteArray()

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/short.f")
            }

            every { mockRootDocument.findFile("short.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream(fullData)

            val result = storage.getBlob(blobId, offset = 5, length = 4)

            assertThat(result).isEqualTo("data".toByteArray())
        }

        @Test
        fun `returns empty array for zero-length read`() = runTest {
            val blobId = BlobId("short")
            val fullData = "test data".toByteArray()

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/short.f")
            }

            every { mockRootDocument.findFile("short.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream(fullData)

            val result = storage.getBlob(blobId, offset = 0, length = 0)

            assertThat(result).isEmpty()
        }

        @Test
        fun `throws BlobNotFoundException when blob does not exist`() = runTest {
            val blobId = BlobId("missing")

            every { mockRootDocument.findFile("missing.f") } returns null

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
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
        fun `throws InvalidBlobRangeException for offset beyond file size`() = runTest {
            val blobId = BlobId("short")
            val smallData = "abc".toByteArray()

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/short.f")
            }

            every { mockRootDocument.findFile("short.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream(smallData)

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = 1000)
            }
        }

        @Test
        fun `fixed-length read that runs past EOF fails loudly instead of returning a short buffer`() = runTest {
            // A truncated/corrupt pack (or a bad packOffset/packedLength) must not be handed upward as a
            // short buffer — the other backends reject short fixed-length reads (task-15/task-14).
            val blobId = BlobId("short")
            val data = "0123456789".toByteArray() // 10 bytes

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/short.f")
            }
            every { mockRootDocument.findFile("short.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream(data)

            // offset 8, length 5 -> only 2 bytes available -> must throw, not return 2 bytes.
            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = 8, length = 5)
            }
        }

        @Test
        fun `offset past EOF on a seekable over-skipping stream still fails loudly`() = runTest {
            // On a real device the SAF stream is FileInputStream-family: skip() lseeks and reports success
            // PAST EOF, so the skipped<offset guard cannot catch an out-of-range offset — the short-read
            // check must. A bad packOffset on a truncated pack must surface as a range error, not empty data.
            val blobId = BlobId("seek")
            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/seek.f")
            }
            every { mockRootDocument.findFile("seek.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns PositionEncodedStream(size = 10)

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = 15, length = 5)
            }
        }

        @Test
        fun `ranged read at an offset beyond Int MAX returns correct bytes without buffering the whole blob`() = runTest {
            // Regression for task-14: getBlob used to readBytes() the WHOLE blob and narrow offset/length
            // to Int. On a multi-GiB pack that OOMs, and any offset >= 2 GiB wraps to garbage via toInt().
            // A 5 GiB virtual stream (no allocation) proves the ranged read (a) never buffers the whole blob
            // (readBytes() here would OOM) and (b) skips to the TRUE Long offset (byte@p == p % 251).
            val blobId = BlobId("huge")
            val fileSize = 5L * 1024 * 1024 * 1024
            val offset = Int.MAX_VALUE.toLong() + 100 // 2_147_483_747 — offset.toInt() would be negative
            val length = 8L

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/huge.f")
            }
            every { mockRootDocument.findFile("huge.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns PositionEncodedStream(fileSize)

            val result = storage.getBlob(blobId, offset = offset, length = length)

            val expected = ByteArray(length.toInt()) { ((offset + it) % 251).toByte() }
            assertThat(result).isEqualTo(expected)
        }
    }

    /**
     * A virtual input stream of [size] bytes where the byte at position p equals (p % 251) — backed by NO
     * allocation, so it models a multi-GiB blob without OOM and proves a ranged read never buffers it all
     * and skips to the true Long offset.
     *
     * Faithfully models the FileInputStream-family stream that ContentResolver returns on a real device:
     * skip() lseeks and OVER-reports past end-of-file (does not clamp), and read() returns short chunks
     * (at most [maxReadChunk]) so the readUpTo / skipFully read loops are actually exercised.
     */
    private class PositionEncodedStream(
        private val size: Long,
        private val maxReadChunk: Int = 4,
    ) : java.io.InputStream() {
        private var pos = 0L
        override fun read(): Int {
            if (pos >= size) return -1
            return ((pos++) % 251).toInt()
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = minOf(len.toLong(), size - pos, maxReadChunk.toLong()).toInt()
            for (i in 0 until n) b[off + i] = ((pos + i) % 251).toByte()
            pos += n
            return n
        }

        // lseek SEEK_CUR semantics: reports success even past EOF (unlike ByteArrayInputStream, which clamps).
        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            pos += n
            return n
        }
    }

    @Nested
    @DisplayName("getBlobMetadata")
    inner class GetBlobMetadataTests {

        @Test
        fun `returns metadata for existing blob`() = runTest {
            val blobId = BlobId("short")
            val modTime = System.currentTimeMillis()

            val mockFile = mockk<DocumentFile> {
                every { length() } returns 1234L
                every { lastModified() } returns modTime
            }

            every { mockRootDocument.findFile("short.f") } returns mockFile

            val result = storage.getBlobMetadata(blobId)

            assertThat(result).isNotNull()
            assertThat(result!!.blobId).isEqualTo(blobId)
            assertThat(result.length).isEqualTo(1234L)
            assertThat(result.timestamp).isEqualTo(Instant.ofEpochMilli(modTime))
        }

        @Test
        fun `returns null for non-existent blob`() = runTest {
            val blobId = BlobId("missing")

            every { mockRootDocument.findFile("missing.f") } returns null

            val result = storage.getBlobMetadata(blobId)

            assertThat(result).isNull()
        }
    }

    @Nested
    @DisplayName("putBlob")
    inner class PutBlobTests {

        @Test
        fun `writes blob data with atomic writes`() = runTest {
            val blobId = BlobId("new")
            val data = "new content".toByteArray()

            // Setup: No existing file
            every { mockRootDocument.findFile("new.f") } returns null

            // Create temp file
            val mockTempFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/new.f.tmp.123")
                every { renameTo("new.f") } returns true
                every { delete() } returns true
            }

            every { mockRootDocument.createFile("application/octet-stream", any()) } returns mockTempFile

            val outputStream = ByteArrayOutputStream()
            every { mockContentResolver.openOutputStream(mockTempFile.uri) } returns outputStream

            storage.putBlob(blobId, data)

            assertThat(outputStream.toByteArray()).isEqualTo(data)
            verify { mockTempFile.renameTo("new.f") }
        }

        @Test
        fun `skips write when blob exists and dontOverwrite is true`() = runTest {
            val blobId = BlobId("existing")
            val data = "data".toByteArray()

            val mockExisting = mockk<DocumentFile>()
            every { mockRootDocument.findFile("existing.f") } returns mockExisting

            storage.putBlob(blobId, data, PutBlobOptions(dontOverwrite = true))

            verify(exactly = 0) { mockRootDocument.createFile(any(), any()) }
        }

        @Test
        fun `creates shard directory when writing sharded blob`() = runTest {
            val blobId = BlobId("pack-abcdef1234567890abcdef")
            val data = "data".toByteArray()

            // Setup sharding with [1, 3]
            val shardingWithMultiple = SafShardingParameters(
                default = listOf(1, 3),
                maxNonShardedLength = 10,
            )
            val storageWithSharding = SafBlobStorage.createForTesting(
                context = mockContext,
                treeUri = testUri,
                options = options,
                shardingParams = shardingWithMultiple,
                skipPermissionCheck = true,
            )

            // No existing file
            every { mockRootDocument.findFile("p") } returns null

            // Create shard directories
            val mockShardDir1 = mockk<DocumentFile> {
                every { isDirectory } returns true
                every { findFile("ack") } returns null
            }
            val mockShardDir2 = mockk<DocumentFile> {
                every { isDirectory } returns true
                every { findFile("-abcdef1234567890abcdef.f") } returns null
            }
            val mockTempFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/p/ack/temp.f")
                every { renameTo("-abcdef1234567890abcdef.f") } returns true
                every { delete() } returns true
            }

            every { mockRootDocument.createDirectory("p") } returns mockShardDir1
            every { mockShardDir1.createDirectory("ack") } returns mockShardDir2
            every { mockShardDir2.createFile("application/octet-stream", any()) } returns mockTempFile

            val outputStream = ByteArrayOutputStream()
            every { mockContentResolver.openOutputStream(mockTempFile.uri) } returns outputStream

            storageWithSharding.putBlob(blobId, data)

            verify { mockRootDocument.createDirectory("p") }
            verify { mockShardDir1.createDirectory("ack") }
        }

        @Test
        fun `throws UnsupportedPutOptionException for retention options`() = runTest {
            val blobId = BlobId("blob")
            val data = "data".toByteArray()

            assertThrows<UnsupportedPutOptionException> {
                storage.putBlob(
                    blobId,
                    data,
                    PutBlobOptions(
                        retentionMode = RetentionMode.GOVERNANCE,
                        retentionPeriod = Duration.ofDays(1),
                    ),
                )
            }
        }

        @Test
        fun `throws IOException in read-only mode`() = runTest {
            val readOnlyStorage = SafBlobStorage.createForTesting(
                context = mockContext,
                treeUri = testUri,
                options = options.copy(readOnly = true),
                shardingParams = shardingParams,
                skipPermissionCheck = true,
            )

            assertThrows<IOException> {
                readOnlyStorage.putBlob(BlobId("test"), "data".toByteArray())
            }
        }
    }

    @Nested
    @DisplayName("deleteBlob")
    inner class DeleteBlobTests {

        @Test
        fun `deletes existing blob`() = runTest {
            val blobId = BlobId("todelete")

            val mockFile = mockk<DocumentFile> {
                every { delete() } returns true
            }

            every { mockRootDocument.findFile("todelete.f") } returns mockFile

            storage.deleteBlob(blobId)

            verify { mockFile.delete() }
        }

        @Test
        fun `ignores delete for non-existent blob`() = runTest {
            val blobId = BlobId("missing")

            every { mockRootDocument.findFile("missing.f") } returns null

            // Should not throw
            storage.deleteBlob(blobId)
        }

        @Test
        fun `throws IOException in read-only mode`() = runTest {
            val readOnlyStorage = SafBlobStorage.createForTesting(
                context = mockContext,
                treeUri = testUri,
                options = options.copy(readOnly = true),
                shardingParams = shardingParams,
                skipPermissionCheck = true,
            )

            assertThrows<IOException> {
                readOnlyStorage.deleteBlob(BlobId("test"))
            }
        }
    }

    @Nested
    @DisplayName("listBlobs")
    inner class ListBlobsTests {

        @Test
        fun `lists blobs with prefix`() = runTest {
            val modTime = System.currentTimeMillis()

            // Root has a directory "p" and some files
            val mockShardDir = mockk<DocumentFile> {
                every { name } returns "p"
                every { isDirectory } returns true
                every { listFiles() } returns arrayOf(
                    mockk {
                        every { name } returns "ack-blob1.f"
                        every { isDirectory } returns false
                        every { length() } returns 100L
                        every { lastModified() } returns modTime
                    },
                    mockk {
                        every { name } returns "ack-blob2.f"
                        every { isDirectory } returns false
                        every { length() } returns 200L
                        every { lastModified() } returns modTime
                    },
                )
            }

            every { mockRootDocument.listFiles() } returns arrayOf(mockShardDir)

            val results = storage.listBlobs("pack-").toList()

            assertThat(results).hasSize(2)
            assertThat(results.map { it.blobId.value }).containsExactly("pack-blob1", "pack-blob2")
        }

        @Test
        fun `returns empty list when no matching blobs`() = runTest {
            every { mockRootDocument.listFiles() } returns emptyArray()

            val results = storage.listBlobs("anything").toList()

            assertThat(results).isEmpty()
        }

        @Test
        fun `ignores files without complete blob suffix`() = runTest {
            val modTime = System.currentTimeMillis()

            every { mockRootDocument.listFiles() } returns arrayOf(
                mockk {
                    every { name } returns "valid.f"
                    every { isDirectory } returns false
                    every { length() } returns 100L
                    every { lastModified() } returns modTime
                },
                mockk {
                    every { name } returns ".shards"
                    every { isDirectory } returns false
                },
                mockk {
                    every { name } returns "incomplete"
                    every { isDirectory } returns false
                },
            )

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

            assertThat(info.type).isEqualTo("saf")
            assertThat(info.config["uri"]).isEqualTo(testUri.toString())
            assertThat(info.config["shards"]).isEqualTo("1")
        }

        @Test
        fun `returns correct display name`() {
            every { mockRootDocument.uri } returns testUri

            assertThat(storage.displayName()).contains("SAF:")
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
            val readOnlyStorage = SafBlobStorage.createForTesting(
                context = mockContext,
                treeUri = testUri,
                options = options.copy(readOnly = true),
                shardingParams = shardingParams,
                skipPermissionCheck = true,
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

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/short.f")
            }

            every { mockRootDocument.findFile("short.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns ByteArrayInputStream("data".toByteArray())

            storage.getBlob(blobId)

            // Should look directly in root, not in shard directory
            verify { mockRootDocument.findFile("short.f") }
        }

        @Test
        fun `long blob IDs are sharded`() = runTest {
            // ID length 25 > 20, so will be sharded with [1]
            val blobId = BlobId("pack-abcdef1234567890abc")

            val mockShardDir = mockk<DocumentFile> {
                every { isDirectory } returns true
                every { findFile("ack-abcdef1234567890abc.f") } returns null
            }

            every { mockRootDocument.findFile("p") } returns mockShardDir

            // Will throw BlobNotFoundException since file doesn't exist
            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }

            // Verify it looked in shard directory
            verify { mockRootDocument.findFile("p") }
            verify { mockShardDir.findFile("ack-abcdef1234567890abc.f") }
        }

        @Test
        fun `uses prefix override shards when matching`() = runTest {
            val customParams = SafShardingParameters(
                default = listOf(1),
                maxNonShardedLength = 5,
                overrides = listOf(
                    SafPrefixShards(prefix = "index", shards = listOf(2, 2)),
                ),
            )

            val customStorage = SafBlobStorage.createForTesting(
                context = mockContext,
                treeUri = testUri,
                options = options,
                shardingParams = customParams,
                skipPermissionCheck = true,
            )

            val blobId = BlobId("index-12345")

            // With prefix override shards [2, 2]: first 2 chars ("in"), then 2 chars ("de")
            val mockShardDir1 = mockk<DocumentFile> {
                every { isDirectory } returns true
            }
            val mockShardDir2 = mockk<DocumentFile> {
                every { isDirectory } returns true
                every { findFile("x-12345.f") } returns null
            }

            every { mockRootDocument.findFile("in") } returns mockShardDir1
            every { mockShardDir1.findFile("de") } returns mockShardDir2

            assertThrows<BlobNotFoundException> {
                customStorage.getBlob(blobId)
            }

            verify { mockRootDocument.findFile("in") }
            verify { mockShardDir1.findFile("de") }
            verify { mockShardDir2.findFile("x-12345.f") }
        }
    }

    @Nested
    @DisplayName("cache management")
    inner class CacheTests {

        @Test
        fun `clearCache clears shard directory cache`() {
            // First access should cache
            val mockShardDir = mockk<DocumentFile> {
                every { isDirectory } returns true
                every { findFile(any()) } returns null
            }

            every { mockRootDocument.findFile("p") } returns mockShardDir

            // Access to populate cache (will fail to find blob, but that's ok)
            runTest {
                try {
                    storage.getBlob(BlobId("pack-abcdef1234567890abcdef"))
                } catch (_: BlobNotFoundException) {
                }
            }

            // Clear and verify second access queries again
            storage.clearCache()

            runTest {
                try {
                    storage.getBlob(BlobId("pack-abcdef1234567890abcdef"))
                } catch (_: BlobNotFoundException) {
                }
            }

            // Should have queried twice (once before clear, once after)
            verify(exactly = 2) { mockRootDocument.findFile("p") }
        }
    }
}

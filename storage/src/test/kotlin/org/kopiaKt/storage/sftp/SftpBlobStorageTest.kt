package org.kopiaKt.storage.sftp

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.HostKeyNotTrustedException
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.RetentionMode
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import java.time.Duration
import java.time.Instant
import java.util.EnumSet

class SftpBlobStorageTest {

    private lateinit var mockSshClient: SSHClient
    private lateinit var mockSftpClient: SFTPClient
    private lateinit var storage: SftpBlobStorage

    private val basePath = "/repo"
    private val options = SftpOptions(
        path = basePath,
        host = "example.com",
        port = 22,
        username = "user",
        password = "password",
    )

    @BeforeEach
    fun setup() {
        mockSshClient = mockk(relaxed = true)
        mockSftpClient = mockk(relaxed = true)

        storage = SftpBlobStorage.createWithConnections(
            options = options,
            sshClient = mockSshClient,
            sftpClient = mockSftpClient,
            readOnly = false,
            directoryShards = listOf(1, 3),
        )
    }

    @Nested
    @DisplayName("host key verification")
    inner class HostKeyVerificationTests {

        @Test
        fun `create fails closed without known_hosts, fingerprint, or insecure opt-in`() {
            // Point known_hosts at a path that cannot exist so the default ~/.ssh/known_hosts can't
            // satisfy it; with no fingerprint and no insecure opt-in, the verifier must reject rather
            // than trust any server key. The rejection happens before any network connection (host is
            // a closed local port so that if the fail-closed path regressed, the RED failure is fast).
            val failClosed = options.copy(host = "127.0.0.1", port = 1, knownHostsFile = "/dev/null/nonexistent")
            assertThrows<HostKeyNotTrustedException> {
                runBlocking { SftpBlobStorage.create(failClosed, isCreate = false) }
            }
        }
    }

    @Nested
    @DisplayName("getBlob")
    inner class GetBlobTests {

        @Test
        fun `returns blob data for simple blob ID`(): Unit = runTest {
            val blobId = BlobId("short")
            val expectedData = "test data".toByteArray()

            val mockFile = mockk<RemoteFile>(relaxed = true) {
                every { length() } returns expectedData.size.toLong()
                every { read(any(), any<ByteArray>(), any(), any()) } answers {
                    val offset = firstArg<Long>()
                    val buffer = secondArg<ByteArray>()
                    val bufferOffset = thirdArg<Int>()
                    val len = lastArg<Int>()
                    expectedData.copyInto(buffer, bufferOffset, offset.toInt(), offset.toInt() + len)
                    len
                }
            }

            every {
                mockSftpClient.open("$basePath/short.f", any<EnumSet<OpenMode>>())
            } returns mockFile

            val result = storage.getBlob(blobId)

            assertThat(result).isEqualTo(expectedData)
            verify { mockFile.close() }
        }

        @Test
        fun `returns blob data for sharded blob ID`(): Unit = runTest {
            // Blob ID longer than 20 chars triggers sharding
            val blobId = BlobId("pack-abcdef1234567890abcdef")
            val expectedData = "sharded test data".toByteArray()

            val mockFile = mockk<RemoteFile>(relaxed = true) {
                every { length() } returns expectedData.size.toLong()
                every { read(any(), any<ByteArray>(), any(), any()) } answers {
                    val offset = firstArg<Long>()
                    val buffer = secondArg<ByteArray>()
                    val bufferOffset = thirdArg<Int>()
                    val len = lastArg<Int>()
                    expectedData.copyInto(buffer, bufferOffset, offset.toInt(), offset.toInt() + len)
                    len
                }
            }

            // With shards [1, 3], "pack-abcdef1234567890abcdef" becomes:
            // dir: "p/ack" file: "-abcdef1234567890abcdef.f"
            every {
                mockSftpClient.open("$basePath/p/ack/-abcdef1234567890abcdef.f", any<EnumSet<OpenMode>>())
            } returns mockFile

            val result = storage.getBlob(blobId)

            assertThat(result).isEqualTo(expectedData)
        }

        @Test
        fun `returns partial blob data with offset`(): Unit = runTest {
            val blobId = BlobId("short")
            val fullData = "0123456789".toByteArray()

            val mockFile = mockk<RemoteFile>(relaxed = true) {
                every { length() } returns fullData.size.toLong()
                every { read(eq(5L), any<ByteArray>(), any(), any()) } answers {
                    val buffer = secondArg<ByteArray>()
                    val bufferOffset = thirdArg<Int>()
                    val len = lastArg<Int>()
                    val data = "56789".toByteArray()
                    val toCopy = minOf(len, data.size)
                    data.copyInto(buffer, bufferOffset, 0, toCopy)
                    toCopy
                }
            }

            every {
                mockSftpClient.open("$basePath/short.f", any<EnumSet<OpenMode>>())
            } returns mockFile

            val result = storage.getBlob(blobId, offset = 5)

            assertThat(result).isEqualTo("56789".toByteArray())
        }

        @Test
        fun `returns partial blob data with offset and length`(): Unit = runTest {
            val blobId = BlobId("short")
            val fullData = "0123456789".toByteArray()

            val mockFile = mockk<RemoteFile>(relaxed = true) {
                every { length() } returns fullData.size.toLong()
                every { read(eq(5L), any<ByteArray>(), any(), any()) } answers {
                    val buffer = secondArg<ByteArray>()
                    val bufferOffset = thirdArg<Int>()
                    val len = lastArg<Int>()
                    val data = "567".toByteArray()
                    data.copyInto(buffer, bufferOffset, 0, data.size)
                    data.size
                }
            }

            every {
                mockSftpClient.open("$basePath/short.f", any<EnumSet<OpenMode>>())
            } returns mockFile

            val result = storage.getBlob(blobId, offset = 5, length = 3)

            assertThat(result).isEqualTo("567".toByteArray())
        }

        @Test
        fun `returns empty array for zero-length read`(): Unit = runTest {
            val blobId = BlobId("short")

            val mockFile = mockk<RemoteFile>(relaxed = true) {
                every { length() } returns 100L
            }

            every {
                mockSftpClient.open("$basePath/short.f", any<EnumSet<OpenMode>>())
            } returns mockFile

            val result = storage.getBlob(blobId, offset = 0, length = 0)

            assertThat(result).isEmpty()
        }

        @Test
        fun `throws BlobNotFoundException when blob does not exist`(): Unit = runTest {
            val blobId = BlobId("missing")

            every {
                mockSftpClient.open("$basePath/missing.f", any<EnumSet<OpenMode>>())
            } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

            assertThrows<BlobNotFoundException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `throws InvalidBlobRangeException for offset beyond file size`(): Unit = runTest {
            val blobId = BlobId("short")

            val mockFile = mockk<RemoteFile>(relaxed = true) {
                every { length() } returns 10L
            }

            every {
                mockSftpClient.open("$basePath/short.f", any<EnumSet<OpenMode>>())
            } returns mockFile

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = 100)
            }
        }

        @Test
        fun `throws InvalidBlobRangeException for negative offset`(): Unit = runTest {
            val blobId = BlobId("short")

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = -1)
            }
        }

        @Test
        fun `throws on a short open-ended read instead of silently truncating`(): Unit = runTest {
            // File claims 10 bytes but the server delivers only 4 then EOF. The open-ended (length
            // = -1) branch used to return the truncated 4 bytes silently — a corrupt restore.
            val blobId = BlobId("short")
            every {
                mockSftpClient.open("$basePath/short.f", any<EnumSet<OpenMode>>())
            } returns truncatedReadFile(claimedLength = 10L, deliver = 4)

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId)
            }
        }

        @Test
        fun `throws on a short fixed-length read`(): Unit = runTest {
            val blobId = BlobId("short")
            every {
                mockSftpClient.open("$basePath/short.f", any<EnumSet<OpenMode>>())
            } returns truncatedReadFile(claimedLength = 10L, deliver = 4)

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId, offset = 0, length = 10)
            }
        }

        @Test
        fun `throws on a read larger than Int MAX instead of overflowing to empty`(): Unit = runTest {
            // A >2 GiB range can't fit one JVM array; narrowing to Int would overflow negative and
            // (pre-fix) silently return empty. It must fail loudly instead.
            val blobId = BlobId("huge")
            val mockFile = mockk<RemoteFile>(relaxed = true) {
                every { length() } returns 3_000_000_000L // > Int.MAX_VALUE
            }
            every {
                mockSftpClient.open("$basePath/huge.f", any<EnumSet<OpenMode>>())
            } returns mockFile

            assertThrows<InvalidBlobRangeException> {
                storage.getBlob(blobId) // open-ended read of the whole 3 GB "file"
            }
        }
    }

    @Nested
    @DisplayName("getBlobMetadata")
    inner class GetBlobMetadataTests {

        @Test
        fun `returns metadata for existing blob`(): Unit = runTest {
            val blobId = BlobId("short")
            val modTime = Instant.parse("2024-01-15T10:30:00Z")

            val mockAttrs = mockk<FileAttributes> {
                every { size } returns 1234L
                every { mtime } returns modTime.epochSecond
            }

            every { mockSftpClient.stat("$basePath/short.f") } returns mockAttrs

            val result = storage.getBlobMetadata(blobId)

            assertThat(result).isNotNull()
            assertThat(result!!.blobId).isEqualTo(blobId)
            assertThat(result.length).isEqualTo(1234L)
            assertThat(result.timestamp).isEqualTo(modTime)
        }

        @Test
        fun `returns null for non-existent blob`(): Unit = runTest {
            val blobId = BlobId("missing")

            every { mockSftpClient.stat("$basePath/missing.f") } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

            val result = storage.getBlobMetadata(blobId)

            assertThat(result).isNull()
        }
    }

    @Nested
    @DisplayName("putBlob")
    inner class PutBlobTests {

        @Test
        fun `writes blob data successfully`(): Unit = runTest {
            val blobId = BlobId("new")
            val data = "new content".toByteArray()

            val mockAttrs = mockk<FileAttributes> {
                every { size } returns 0L
            }

            // stat() throws because it doesn't exist
            every { mockSftpClient.stat("$basePath/new.f") } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

            val tempFileSlot = slot<String>()
            val mockFile = mockk<RemoteFile>(relaxed = true)

            every {
                mockSftpClient.open(capture(tempFileSlot), any<EnumSet<OpenMode>>())
            } returns mockFile

            every { mockSftpClient.rename(any(), any(), any()) } just Runs

            storage.putBlob(blobId, data)

            // Verify temp file was written and renamed
            assertThat(tempFileSlot.captured).startsWith("$basePath/new.f.tmp.")
            verify { mockFile.write(0, data, 0, data.size) }
            verify { mockFile.close() }
            verify { mockSftpClient.rename(any(), "$basePath/new.f", any()) }
        }

        @Test
        fun `creates parent directories when needed`(): Unit = runTest {
            val blobId = BlobId("pack-abcdef1234567890abcdef")
            val data = "data".toByteArray()

            // Parent directory doesn't exist
            every { mockSftpClient.stat("$basePath/p") } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
            every { mockSftpClient.stat("$basePath/p/ack") } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
            every { mockSftpClient.mkdir(any()) } just Runs

            val mockFile = mockk<RemoteFile>(relaxed = true)
            every {
                mockSftpClient.open(any<String>(), any<EnumSet<OpenMode>>())
            } returns mockFile

            every { mockSftpClient.rename(any(), any(), any()) } just Runs

            storage.putBlob(blobId, data)

            verify { mockSftpClient.mkdir("$basePath/p") }
            verify { mockSftpClient.mkdir("$basePath/p/ack") }
        }

        @Test
        fun `skips write when blob exists and dontOverwrite is true`(): Unit = runTest {
            val blobId = BlobId("existing")
            val data = "data".toByteArray()

            val mockAttrs = mockk<FileAttributes> {
                every { size } returns 100L
            }
            every { mockSftpClient.stat("$basePath/existing.f") } returns mockAttrs

            storage.putBlob(blobId, data, PutBlobOptions(dontOverwrite = true))

            verify(exactly = 0) { mockSftpClient.open(any<String>(), any<EnumSet<OpenMode>>()) }
        }

        @Test
        fun `throws UnsupportedPutOptionException for retention options`(): Unit = runTest {
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
        fun `sets modification time when requested`(): Unit = runTest {
            val blobId = BlobId("timestamped")
            val data = "data".toByteArray()
            val modTime = Instant.parse("2024-01-15T10:30:00Z")

            val mockFile = mockk<RemoteFile>(relaxed = true)
            every {
                mockSftpClient.open(any<String>(), any<EnumSet<OpenMode>>())
            } returns mockFile

            every { mockSftpClient.rename(any(), any(), any()) } just Runs
            every { mockSftpClient.setattr(any<String>(), any()) } just Runs

            storage.putBlob(blobId, data, PutBlobOptions(setModTime = modTime))

            verify { mockSftpClient.setattr("$basePath/timestamped.f", any()) }
        }
    }

    @Nested
    @DisplayName("deleteBlob")
    inner class DeleteBlobTests {

        @Test
        fun `deletes existing blob`(): Unit = runTest {
            val blobId = BlobId("todelete")

            every { mockSftpClient.rm("$basePath/todelete.f") } just Runs

            storage.deleteBlob(blobId)

            verify { mockSftpClient.rm("$basePath/todelete.f") }
        }

        @Test
        fun `ignores delete for non-existent blob`(): Unit = runTest {
            val blobId = BlobId("missing")

            every { mockSftpClient.rm("$basePath/missing.f") } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

            // Should not throw
            storage.deleteBlob(blobId)
        }

        @Test
        fun `deletes sharded blob`(): Unit = runTest {
            val blobId = BlobId("pack-abcdef1234567890abcdef")

            every { mockSftpClient.rm("$basePath/p/ack/-abcdef1234567890abcdef.f") } just Runs

            storage.deleteBlob(blobId)

            verify { mockSftpClient.rm("$basePath/p/ack/-abcdef1234567890abcdef.f") }
        }
    }

    @Nested
    @DisplayName("listBlobs")
    inner class ListBlobsTests {

        @Test
        fun `lists blobs with prefix`(): Unit = runTest {
            val modTime = Instant.parse("2024-01-15T10:30:00Z")

            // Root directory listing
            val pDir = mockResourceInfo("p", isDirectory = true)
            every { mockSftpClient.ls(basePath) } returns listOf(pDir)

            // p/ directory listing
            val ackDir = mockResourceInfo("ack", isDirectory = true)
            every { mockSftpClient.ls("$basePath/p") } returns listOf(ackDir)

            // p/ack/ directory listing
            val blob1 = mockResourceInfo("-blob1.f", isDirectory = false, size = 100L, mtime = modTime.epochSecond)
            val blob2 = mockResourceInfo("-blob2.f", isDirectory = false, size = 200L, mtime = modTime.epochSecond)
            every { mockSftpClient.ls("$basePath/p/ack") } returns listOf(blob1, blob2)

            val results = storage.listBlobs("pack-").toList()

            assertThat(results).hasSize(2)
            assertThat(results.map { it.blobId.value }).containsExactly("pack-blob1", "pack-blob2")
        }

        @Test
        fun `returns empty list when directory does not exist`(): Unit = runTest {
            every { mockSftpClient.ls(basePath) } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

            val results = storage.listBlobs("anything").toList()

            assertThat(results).isEmpty()
        }

        @Test
        fun `ignores files without complete blob suffix`(): Unit = runTest {
            val validFile = mockResourceInfo("valid.f", isDirectory = false, size = 100L)
            val invalidFile = mockResourceInfo(".shards", isDirectory = false, size = 50L)

            every { mockSftpClient.ls(basePath) } returns listOf(validFile, invalidFile)

            val results = storage.listBlobs("").toList()

            assertThat(results).hasSize(1)
            assertThat(results[0].blobId.value).isEqualTo("valid")
        }

        @Test
        fun `ignores dot and dotdot entries`(): Unit = runTest {
            val dotEntry = mockResourceInfo(".", isDirectory = true)
            val dotDotEntry = mockResourceInfo("..", isDirectory = true)
            val validFile = mockResourceInfo("valid.f", isDirectory = false, size = 100L)

            every { mockSftpClient.ls(basePath) } returns listOf(dotEntry, dotDotEntry, validFile)

            val results = storage.listBlobs("").toList()

            assertThat(results).hasSize(1)
            assertThat(results[0].blobId.value).isEqualTo("valid")
        }

        @Test
        fun `does not stack overflow on a directory cycle`(): Unit = runTest {
            // Every directory lists a subdirectory of the same name (a server-side symlink cycle).
            // Without the recursion-depth cap this walks forever and StackOverflows.
            val loopDir = mockResourceInfo("loop", isDirectory = true)
            every { mockSftpClient.ls(any<String>()) } returns listOf(loopDir)

            val results = storage.listBlobs("").toList()

            assertThat(results).isEmpty()
        }
    }

    @Nested
    @DisplayName("connectionInfo and displayName")
    inner class ConnectionInfoTests {

        @Test
        fun `returns correct connection info`() {
            val info = storage.connectionInfo()

            assertThat(info.type).isEqualTo("sftp")
            assertThat(info.config["host"]).isEqualTo("example.com")
            assertThat(info.config["port"]).isEqualTo("22")
            assertThat(info.config["username"]).isEqualTo("user")
            assertThat(info.config["path"]).isEqualTo(basePath)
        }

        @Test
        fun `returns correct display name`() {
            assertThat(storage.displayName()).isEqualTo("SFTP user@example.com")
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
            val readOnlyStorage = SftpBlobStorage.createWithConnections(
                options = options,
                sshClient = mockSshClient,
                sftpClient = mockSftpClient,
                readOnly = true,
                directoryShards = listOf(1, 3),
            )

            assertThat(readOnlyStorage.isReadOnly()).isTrue()
        }

        @Test
        fun `putBlob is rejected in read-only mode`(): Unit = runTest {
            val readOnlyStorage = SftpBlobStorage.createWithConnections(
                options = options,
                sshClient = mockSshClient,
                sftpClient = mockSftpClient,
                readOnly = true,
                directoryShards = listOf(1, 3),
            )
            assertThrows<IllegalStateException> {
                readOnlyStorage.putBlob(BlobId("ro"), "data".toByteArray())
            }
        }

        @Test
        fun `deleteBlob is rejected in read-only mode`(): Unit = runTest {
            val readOnlyStorage = SftpBlobStorage.createWithConnections(
                options = options,
                sshClient = mockSshClient,
                sftpClient = mockSftpClient,
                readOnly = true,
                directoryShards = listOf(1, 3),
            )
            assertThrows<IllegalStateException> {
                readOnlyStorage.deleteBlob(BlobId("ro"))
            }
        }
    }

    @Nested
    @DisplayName("sharding")
    inner class ShardingTests {

        @Test
        fun `short blob IDs are not sharded`(): Unit = runTest {
            val blobId = BlobId("short") // Length 5 < 20
            val data = "data".toByteArray()

            val tempFileSlot = slot<String>()
            val mockFile = mockk<RemoteFile>(relaxed = true)

            every {
                mockSftpClient.open(capture(tempFileSlot), any<EnumSet<OpenMode>>())
            } returns mockFile
            every { mockSftpClient.rename(any(), any(), any()) } just Runs

            storage.putBlob(blobId, data)

            // Temp file should be at root, not sharded
            assertThat(tempFileSlot.captured).startsWith("$basePath/short.f.tmp.")
            verify { mockSftpClient.rename(any(), "$basePath/short.f", any()) }
        }

        @Test
        fun `long blob IDs are sharded`(): Unit = runTest {
            // ID length 25 > 20, so will be sharded with [1, 3]
            val blobId = BlobId("pack-abcdef1234567890abc")
            val data = "data".toByteArray()

            // Ensure parent dir exists
            every { mockSftpClient.stat("$basePath/p") } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
            every { mockSftpClient.stat("$basePath/p/ack") } throws
                SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
            every { mockSftpClient.mkdir(any()) } just Runs

            val tempFileSlot = slot<String>()
            val mockFile = mockk<RemoteFile>(relaxed = true)

            every {
                mockSftpClient.open(capture(tempFileSlot), any<EnumSet<OpenMode>>())
            } returns mockFile
            every { mockSftpClient.rename(any(), any(), any()) } just Runs

            storage.putBlob(blobId, data)

            // With shards [1, 3]: first 1 char ("p"), then 3 chars ("ack")
            // Remaining: "-abcdef1234567890abc"
            assertThat(tempFileSlot.captured).startsWith("$basePath/p/ack/-abcdef1234567890abc.f.tmp.")
        }
    }

    @Nested
    @DisplayName("close")
    inner class CloseTests {

        @Test
        fun `closes SFTP and SSH clients`(): Unit = runTest {
            every { mockSftpClient.close() } just Runs
            every { mockSshClient.disconnect() } just Runs

            storage.close()

            verify { mockSftpClient.close() }
            verify { mockSshClient.disconnect() }
        }
    }

    /**
     * A [RemoteFile] that reports [claimedLength] bytes but delivers only [deliver] bytes and then
     * signals EOF (-1), simulating a truncated/short read from a misbehaving or racing server.
     */
    private fun truncatedReadFile(claimedLength: Long, deliver: Int): RemoteFile {
        var served = false
        return mockk(relaxed = true) {
            every { length() } returns claimedLength
            every { read(any(), any<ByteArray>(), any(), any()) } answers {
                if (served) {
                    -1
                } else {
                    served = true
                    val buffer = secondArg<ByteArray>()
                    val bufferOffset = thirdArg<Int>()
                    ByteArray(deliver) { 'a'.code.toByte() }.copyInto(buffer, bufferOffset, 0, deliver)
                    deliver
                }
            }
        }
    }

    private fun mockResourceInfo(
        name: String,
        isDirectory: Boolean,
        size: Long = 0L,
        mtime: Long = Instant.now().epochSecond,
    ): RemoteResourceInfo {
        val mockAttrs = mockk<FileAttributes> {
            every { this@mockk.size } returns size
            every { this@mockk.mtime } returns mtime
            every { type } returns if (isDirectory) FileMode.Type.DIRECTORY else FileMode.Type.REGULAR
        }

        return mockk {
            every { this@mockk.name } returns name
            every { this@mockk.isDirectory } returns isDirectory
            every { attributes } returns mockAttrs
        }
    }

    @Nested
    @DisplayName("connection timeouts")
    inner class ConnectionTimeoutTests {

        @Test
        fun `applyConnectionTimeouts sets connect and socket timeouts from options`() {
            val ssh = SSHClient()
            SftpBlobStorage.applyConnectionTimeouts(
                ssh,
                options.copy(connectTimeoutMillis = 15_000, socketTimeoutMillis = 45_000),
            )
            assertThat(ssh.connectTimeout).isEqualTo(15_000)
            assertThat(ssh.timeout).isEqualTo(45_000)
        }

        @Test
        fun `non-positive timeouts leave the sshj defaults untouched`() {
            val ssh = SSHClient()
            val defaultConnect = ssh.connectTimeout
            val defaultSocket = ssh.timeout
            SftpBlobStorage.applyConnectionTimeouts(
                ssh,
                options.copy(connectTimeoutMillis = 0, socketTimeoutMillis = 0),
            )
            assertThat(ssh.connectTimeout).isEqualTo(defaultConnect)
            assertThat(ssh.timeout).isEqualTo(defaultSocket)
        }
    }

    @Nested
    @DisplayName("connection error classification")
    inner class ConnectionErrorClassificationTests {

        @Test
        fun `connection-loss SFTP statuses are treated as connection errors`() {
            assertThat(
                SftpBlobStorage.isSftpConnectionError(SFTPException(Response.StatusCode.NO_CONNECTION, "x")),
            ).isTrue()
            assertThat(
                SftpBlobStorage.isSftpConnectionError(SFTPException(Response.StatusCode.CONNECITON_LOST, "x")),
            ).isTrue()
        }

        @Test
        fun `a wrapped transport failure (SFTPException with UNKNOWN status) is a connection error`() {
            // sshj wraps a dead transport / EOF / timeout as an SFTPException with a null status →
            // getStatusCode() == UNKNOWN. This is the DOMINANT connection-loss shape (servers never send
            // NO_CONNECTION/CONNECITON_LOST), so it must reconnect+replay. Both real shapes: the
            // read-side EOF (String ctor) and the chained transport IOException (Throwable ctor).
            assertThat(SftpBlobStorage.isSftpConnectionError(SFTPException("EOF while reading packet"))).isTrue()
            assertThat(SftpBlobStorage.isSftpConnectionError(SFTPException(java.io.IOException("reset")))).isTrue()
        }

        @Test
        fun `operation-level SFTP errors are NOT connection errors (no reconnect-replay)`() {
            assertThat(
                SftpBlobStorage.isSftpConnectionError(SFTPException(Response.StatusCode.NO_SUCH_FILE, "x")),
            ).isFalse()
            assertThat(
                SftpBlobStorage.isSftpConnectionError(SFTPException(Response.StatusCode.PERMISSION_DENIED, "x")),
            ).isFalse()
        }

        @Test
        fun `transport and socket IOExceptions are treated as connection errors`() {
            assertThat(SftpBlobStorage.isSftpConnectionError(java.io.IOException("broken pipe"))).isTrue()
            assertThat(SftpBlobStorage.isSftpConnectionError(java.net.SocketException("reset"))).isTrue()
        }
    }
}

package org.kopiaKt.storage.sftp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.PutBlobOptions
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.EnumSet
import java.util.UUID

/**
 * Integration tests for SftpBlobStorage using a Testcontainers-managed SFTP server.
 *
 * These tests automatically start an atmoz/sftp container using Testcontainers.
 * They are skipped if Docker is not available on the host.
 *
 * Run with: ./gradlew :storage:integrationTest --tests "*SftpBlobStorageIntegrationTest*"
 */
@Tag("integration")
@Tag("sftp")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SftpBlobStorageIntegrationTest {

    companion object {
        private const val USERNAME = "kopia"
        private const val PASSWORD = "testpass"

        @Container
        @JvmStatic
        val sftp: GenericContainer<*> = GenericContainer("atmoz/sftp:latest")
            .withExposedPorts(22)
            .withCommand("$USERNAME:$PASSWORD:::upload")
            .waitingFor(
                Wait.forListeningPort(),
            )
    }

    private lateinit var storage: SftpBlobStorage
    private val testPrefix = UUID.randomUUID().toString().take(8)

    private fun sftpOptions(
        path: String = "/home/$USERNAME/upload/$testPrefix",
        password: String = PASSWORD,
        directoryShards: List<Int>? = null,
    ) = SftpOptions(
        path = path,
        host = sftp.host,
        port = sftp.getMappedPort(22),
        username = USERNAME,
        password = password,
        // The ephemeral atmoz/sftp container generates a throwaway host key not in any known_hosts,
        // so opt into the insecure verifier explicitly (test-only; the default now fails closed).
        knownHostsFile = "/dev/null/nonexistent",
        insecureSkipHostKeyVerification = true,
        directoryShards = directoryShards,
    )

    @BeforeEach
    fun setup() = runTest {
        storage = SftpBlobStorage.create(sftpOptions(), isCreate = true)
    }

    @AfterAll
    fun teardownAll() = runTest {
        if (::storage.isInitialized) {
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
        assertThat(info.config["host"]).isEqualTo(sftp.host)
        assertThat(info.config["username"]).isEqualTo(USERNAME)
    }

    @Test
    @Order(11)
    @DisplayName("display name")
    fun displayName() = runTest {
        val name = storage.displayName()

        assertThat(name).contains("SFTP")
        assertThat(name).contains(USERNAME)
        assertThat(name).contains(sftp.host)
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

    @Test
    @Order(13)
    @DisplayName("create with isCreate=true creates remote directory")
    fun create_withIsCreate_createsRemoteDirectory() = runTest {
        val uniquePath = "/home/$USERNAME/upload/test-${UUID.randomUUID().toString().take(8)}"
        val s = SftpBlobStorage.create(
            sftpOptions(path = uniquePath),
            isCreate = true,
        )
        s.putBlob(BlobId("p_test"), "data".toByteArray())
        val result = s.getBlob(BlobId("p_test"))
        assertArrayEquals("data".toByteArray(), result)
        s.close()
    }

    @Test
    @Order(14)
    @DisplayName("wrong password fails authentication")
    fun create_failsWithWrongPassword() = runTest {
        assertThrows<Exception> {
            runBlocking {
                SftpBlobStorage.create(
                    sftpOptions(password = "wrongpassword"),
                    isCreate = false,
                )
            }
        }
    }

    @Test
    @Order(15)
    @DisplayName("honors a flat (unsharded) repo's .shards file when opening")
    fun open_honorsFlatShardsFile() = runTest {
        // A repo created by e.g. `kopia repository create sftp --flat` writes .shards={"default":[]}
        // and stores blobs UNSHARDED at the repository root. create(isCreate=false) MUST read that
        // .shards and honor it; otherwise it assumes [1,3] sharding, computes x/n0_/… and finds
        // nothing — silently opening an EMPTY view of a real repo (BlobNotFoundException per blob).
        val flatPath = "/home/$USERNAME/upload/flat-${UUID.randomUUID().toString().take(8)}"
        // >20 chars, so it WOULD be sharded under the default [1,3]; must be found at the root instead.
        val blobId = "xn0_abcdef0123456789abcdef0123"
        val payload = "flat-layout blob content".toByteArray()

        withRawSftp { raw ->
            raw.mkdirs(flatPath)
            writeRemoteFile(raw, "$flatPath/.shards", """{"default":[],"maxNonShardedLength":20}""".toByteArray())
            writeRemoteFile(raw, "$flatPath/$blobId.f", payload)
        }

        val flatStorage = SftpBlobStorage.create(sftpOptions(path = flatPath), isCreate = false)
        try {
            assertArrayEquals(payload, flatStorage.getBlob(BlobId(blobId)))
        } finally {
            flatStorage.close()
        }
    }

    @Test
    @Order(16)
    @DisplayName("opens a legacy repo with no .shards using Go's [3,3] fallback")
    fun open_legacyRepoWithoutShards_usesThreeThreeFallback() = runTest {
        // A legacy repo has NO .shards file. Go lays such a repo out with [3,3] on open, so a blob
        // id "abc..." lives at abc/def/…. Kotlin must fall back to [3,3] (not [1,3], which would look
        // under a/bcd/… and read the repo empty). Guards the dead-[3,3]-fallback regression.
        val legacyPath = "/home/$USERNAME/upload/legacy-${UUID.randomUUID().toString().take(8)}"
        val blobId = "abcdefghij0123456789klmno" // 25 chars → sharded under [3,3] as abc/def/rest
        val payload = "legacy [3,3] blob".toByteArray()

        withRawSftp { raw ->
            raw.mkdirs("$legacyPath/abc/def")
            writeRemoteFile(raw, "$legacyPath/abc/def/ghij0123456789klmno.f", payload)
        }

        // directoryShards left unset (null) → open resolves the Go legacy [3,3] fallback.
        val legacy = SftpBlobStorage.create(sftpOptions(path = legacyPath), isCreate = false)
        try {
            assertArrayEquals(payload, legacy.getBlob(BlobId(blobId)))
        } finally {
            legacy.close()
        }
    }

    @Test
    @Order(17)
    @DisplayName("throws when opening a repo whose .shards is present but unparseable")
    fun open_corruptShards_throws() = runTest {
        // A present-but-corrupt .shards must fail loud, not silently fall back to a guessed layout.
        val corruptPath = "/home/$USERNAME/upload/corrupt-${UUID.randomUUID().toString().take(8)}"
        withRawSftp { raw ->
            raw.mkdirs(corruptPath)
            writeRemoteFile(raw, "$corruptPath/.shards", "this is not valid json {".toByteArray())
        }

        assertThrows<Exception> {
            runBlocking { SftpBlobStorage.create(sftpOptions(path = corruptPath), isCreate = false) }
        }
    }

    /** Opens a raw sshj SFTP client to the test container to lay down an arbitrary on-disk layout. */
    private fun withRawSftp(block: (SFTPClient) -> Unit) {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(sftp.host, sftp.getMappedPort(22))
        try {
            ssh.authPassword(USERNAME, PASSWORD)
            ssh.newSFTPClient().use(block)
        } finally {
            ssh.disconnect()
        }
    }

    private fun writeRemoteFile(raw: SFTPClient, path: String, content: ByteArray) {
        raw.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            file.write(0, content, 0, content.size)
        }
    }
}

package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.manifest.ManifestNotFoundException
import java.security.SecureRandom
import java.time.Instant
import kotlin.random.Random

/**
 * Tests for DirectRepositoryImpl.
 */
class DirectRepositoryTest {

    private lateinit var storage: InMemoryBlobStorage

    @BeforeEach
    fun setUp() {
        storage = InMemoryBlobStorage()
    }

    private fun createDefaultConfig(): RepositoryConfig {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val masterKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

        return RepositoryConfig(
            hash = "BLAKE2B-256-128",
            encryption = "AES256-GCM-HMAC-SHA256",
            secret = secret,
            masterKey = masterKey,
            splitter = "FIXED-1M",
        )
    }

    @Nested
    @DisplayName("Repository Creation")
    inner class RepositoryCreation {

        @Test
        fun `create initializes new repository`() = runTest {
            val config = createDefaultConfig()
            val repo = DirectRepositoryImpl.create(storage, "test-password", config)

            assertThat(repo).isNotNull()
            repo.close()

            // Verify format blob was created
            assertThat(storage.contains(BlobId("kopia.repository"))).isTrue()
        }

        @Test
        fun `create fails if repository already exists`() = runTest {
            val config = createDefaultConfig()
            val repo = DirectRepositoryImpl.create(storage, "test-password", config)
            repo.close()

            assertThrows<Exception> {
                DirectRepositoryImpl.create(storage, "test-password", config)
            }
        }

        @Test
        fun `open connects to existing repository`() = runTest {
            val config = createDefaultConfig()
            val repo1 = DirectRepositoryImpl.create(storage, "test-password", config)
            repo1.close()

            val repo2 = DirectRepositoryImpl.open(storage, "test-password")
            assertThat(repo2).isNotNull()
            repo2.close()
        }

        @Test
        fun `open fails with wrong password`() = runTest {
            val config = createDefaultConfig()
            val repo = DirectRepositoryImpl.create(storage, "correct-password", config)
            repo.close()

            val exception = assertThrows<Exception> {
                DirectRepositoryImpl.open(storage, "wrong-password")
            }
            // The exception could be InvalidPasswordException directly or wrapped
            assertThat(
                exception is org.kopiaKt.core.format.InvalidPasswordException ||
                    exception.cause is org.kopiaKt.core.format.InvalidPasswordException,
            ).isTrue()
        }

        @Test
        fun `open fails if repository does not exist`() = runTest {
            assertThrows<Exception> {
                DirectRepositoryImpl.open(storage, "test-password")
            }
        }
    }

    @Nested
    @DisplayName("Object Operations")
    inner class ObjectOperations {

        private lateinit var repo: DirectRepositoryImpl

        @BeforeEach
        fun setUp() = runTest {
            val config = createDefaultConfig()
            repo = DirectRepositoryImpl.create(storage, "test-password", config)
        }

        @Test
        fun `write and read small object`() = runTest {
            val data = "Hello, Kopia!".toByteArray()

            val writer = repo.newDirectWriter()
            val objectId = writer.writeObject(data)
            writer.flush()

            repo.refresh()
            val readData = repo.readObject(objectId)
            assertThat(readData).isEqualTo(data)
        }

        @Test
        fun `write and read larger object`() = runTest {
            // Create 100KB random data
            val data = Random.nextBytes(100_000)

            val writer = repo.newDirectWriter()
            val objectId = writer.writeObject(data)
            writer.flush()

            repo.refresh()
            val readData = repo.readObject(objectId)
            assertThat(readData).isEqualTo(data)
        }

        @Test
        fun `write object with streaming writer`() = runTest {
            val writer = repo.newDirectWriter()
            val objectWriter = writer.newObjectWriter()

            objectWriter.write("Hello, ".toByteArray())
            objectWriter.write("World!".toByteArray())
            val objectId = objectWriter.result()
            writer.flush()

            repo.refresh()
            val readData = repo.readObject(objectId)
            assertThat(readData).isEqualTo("Hello, World!".toByteArray())
        }

        @Test
        fun `verify object returns content IDs`() = runTest {
            val data = "Hello, Kopia!".toByteArray()

            val writer = repo.newDirectWriter()
            val objectId = writer.writeObject(data)
            writer.flush()

            repo.refresh()
            val contentIds = repo.verifyObject(objectId)
            assertThat(contentIds).isNotEmpty()
        }

        @Test
        fun `concatenate objects`() = runTest {
            val writer = repo.newDirectWriter()

            val obj1 = writer.writeObject("Hello, ".toByteArray())
            val obj2 = writer.writeObject("World!".toByteArray())

            val combined = writer.concatenateObjects(listOf(obj1, obj2))
            writer.flush()

            repo.refresh()
            val readData = repo.readObject(combined)
            assertThat(readData).isEqualTo("Hello, World!".toByteArray())
        }
    }

    @Serializable
    data class TestManifest(
        val name: String,
        val value: Int,
    )

    @Nested
    @DisplayName("Manifest Operations")
    inner class ManifestOperations {

        private lateinit var repo: DirectRepositoryImpl

        @BeforeEach
        fun setUp() = runTest {
            val config = createDefaultConfig()
            repo = DirectRepositoryImpl.create(storage, "test-password", config)
        }

        @Test
        fun `put and get manifest`() = runTest {
            val labels = mapOf("type" to "test", "key" to "value")
            val payload = TestManifest("test", 42)

            val writer = repo.newDirectWriter()
            val id = writer.putManifest(labels, payload, serializer<TestManifest>())
            writer.flush()

            // Refresh to see committed manifests
            repo.refresh()

            val (retrieved, metadata) = repo.getManifest(id, serializer<TestManifest>())
            assertThat(retrieved).isEqualTo(payload)
            assertThat(metadata.labels).isEqualTo(labels)
        }

        @Test
        fun `find manifests by labels`() = runTest {
            val labels1 = mapOf("type" to "snapshot", "source" to "/home/user")
            val labels2 = mapOf("type" to "snapshot", "source" to "/var/data")
            val labels3 = mapOf("type" to "policy")

            val writer = repo.newDirectWriter()
            writer.putManifest(labels1, TestManifest("snap1", 1), serializer<TestManifest>())
            writer.putManifest(labels2, TestManifest("snap2", 2), serializer<TestManifest>())
            writer.putManifest(labels3, TestManifest("policy", 3), serializer<TestManifest>())
            writer.flush()

            repo.refresh()

            val snapshots = repo.findManifests(mapOf("type" to "snapshot"))
            assertThat(snapshots).hasSize(2)

            val policies = repo.findManifests(mapOf("type" to "policy"))
            assertThat(policies).hasSize(1)
        }

        @Test
        fun `replace manifests deletes existing and creates new`() = runTest {
            val labels = mapOf("type" to "policy", "path" to "/root")
            val payload1 = TestManifest("old", 1)
            val payload2 = TestManifest("new", 2)

            val writer1 = repo.newDirectWriter()
            writer1.putManifest(labels, payload1, serializer<TestManifest>())
            writer1.flush()

            repo.refresh()

            val writer2 = repo.newDirectWriter()
            val newId = writer2.replaceManifests(labels, payload2, serializer<TestManifest>())
            writer2.flush()

            repo.refresh()

            // Should only find one manifest
            val found = repo.findManifests(labels)
            assertThat(found).hasSize(1)
            assertThat(found[0].id).isEqualTo(newId)

            val (retrieved, _) = repo.getManifest(newId, serializer<TestManifest>())
            assertThat(retrieved).isEqualTo(payload2)
        }

        @Test
        fun `delete manifest marks it as deleted`() = runTest {
            val labels = mapOf("type" to "test")
            val payload = TestManifest("to-delete", 1)

            val writer = repo.newDirectWriter()
            val id = writer.putManifest(labels, payload, serializer<TestManifest>())
            writer.flush()

            repo.refresh()

            // Verify it exists
            val found1 = repo.findManifests(labels)
            assertThat(found1).hasSize(1)

            // Delete it
            val writer2 = repo.newDirectWriter()
            writer2.deleteManifest(id)
            writer2.flush()

            repo.refresh()

            // Should no longer be found
            val found2 = repo.findManifests(labels)
            assertThat(found2).isEmpty()
        }

        @Test
        fun `get non-existent manifest throws ManifestNotFoundException`() = runTest {
            val fakeId = org.kopiaKt.core.manifest.ManifestId.generate()

            assertThrows<ManifestNotFoundException> {
                repo.getManifest(fakeId, serializer<TestManifest>())
            }
        }

        @Test
        fun `put manifest requires type label`() = runTest {
            val labels = mapOf("key" to "value") // Missing "type"
            val payload = TestManifest("test", 1)

            val writer = repo.newDirectWriter()

            assertThrows<IllegalArgumentException> {
                writer.putManifest(labels, payload, serializer<TestManifest>())
            }
        }
    }

    @Nested
    @DisplayName("Repository Properties")
    inner class RepositoryProperties {

        private lateinit var repo: DirectRepositoryImpl

        @BeforeEach
        fun setUp() = runTest {
            val config = createDefaultConfig()
            repo = DirectRepositoryImpl.create(storage, "test-password", config)
        }

        @Test
        fun `uniqueId returns 32-byte identifier`() {
            val uniqueId = repo.uniqueId()
            assertThat(uniqueId).hasLength(32)
        }

        @Test
        fun `uniqueId returns a copy`() {
            val id1 = repo.uniqueId()
            val id2 = repo.uniqueId()

            assertThat(id1).isNotSameInstanceAs(id2)
            assertThat(id1).isEqualTo(id2)
        }

        @Test
        fun `deriveKey produces deterministic keys`() {
            val key1 = repo.deriveKey("test-purpose", 32)
            val key2 = repo.deriveKey("test-purpose", 32)

            assertThat(key1).isEqualTo(key2)
        }

        @Test
        fun `deriveKey produces different keys for different purposes`() {
            val key1 = repo.deriveKey("purpose-1", 32)
            val key2 = repo.deriveKey("purpose-2", 32)

            assertThat(key1).isNotEqualTo(key2)
        }

        @Test
        fun `objectFormat returns splitter configuration`() {
            val format = repo.objectFormat()
            assertThat(format.splitter).isEqualTo("FIXED-1M")
        }

        @Test
        fun `supportsPasswordChange returns true for modern repos`() {
            assertThat(repo.supportsPasswordChange()).isTrue()
        }

        @Test
        fun `blobReader returns the storage`() {
            assertThat(repo.blobReader()).isNotNull()
        }

        @Test
        fun `time returns current time`() {
            val before = Instant.now().minusSeconds(1)
            val repoTime = repo.time()
            val after = Instant.now().plusSeconds(1)

            assertThat(repoTime).isAtLeast(before)
            assertThat(repoTime).isAtMost(after)
        }
    }

    @Nested
    @DisplayName("Client Options")
    inner class ClientOptionsTests {

        private lateinit var repo: DirectRepositoryImpl

        @BeforeEach
        fun setUp() = runTest {
            val config = createDefaultConfig()
            repo = DirectRepositoryImpl.create(storage, "test-password", config)
        }

        @Test
        fun `clientOptions returns configured options`() {
            val options = repo.clientOptions()
            assertThat(options).isNotNull()
        }

        @Test
        fun `updateDescription changes the description`() {
            repo.updateDescription("My Test Repo")
            assertThat(repo.clientOptions().description).isEqualTo("My Test Repo")
        }
    }

    @Nested
    @DisplayName("Write Sessions")
    inner class WriteSessions {

        private lateinit var repo: DirectRepositoryImpl

        @BeforeEach
        fun setUp() = runTest {
            val config = createDefaultConfig()
            repo = DirectRepositoryImpl.create(storage, "test-password", config)
        }

        @Test
        fun `writeSession helper flushes on success`() = runTest {
            val data = "test data".toByteArray()

            val objectId = writeSession(repo) { writer ->
                writer.writeObject(data)
            }

            // Should be readable after session (flush happened)
            repo.refresh()
            val readData = repo.readObject(objectId)
            assertThat(readData).isEqualTo(data)
        }

        @Test
        fun `writeSession does not flush on failure by default`() = runTest {
            val data = "test data".toByteArray()

            assertThrows<RuntimeException> {
                writeSession(repo) { writer ->
                    writer.writeObject(data)
                    throw RuntimeException("Simulated failure")
                }
            }
        }

        @Test
        fun `newDirectWriter creates writer session`() = runTest {
            val writer = repo.newDirectWriter(WriteSessionOptions(purpose = "test"))
            assertThat(writer).isNotNull()

            val objectId = writer.writeObject("test".toByteArray())
            writer.flush()

            repo.refresh()
            val readData = repo.readObject(objectId)
            assertThat(readData).isEqualTo("test".toByteArray())
        }

        @Test
        fun `onSuccessfulFlush callback is invoked`() = runTest {
            var callbackInvoked = false

            val writer = repo.newDirectWriter()
            writer.onSuccessfulFlush {
                callbackInvoked = true
            }

            writer.writeObject("test".toByteArray())
            writer.flush()

            assertThat(callbackInvoked).isTrue()
        }
    }

    @Nested
    @DisplayName("Repository Lifecycle")
    inner class RepositoryLifecycle {

        @Test
        fun `close marks repository as closed`() = runTest {
            val config = createDefaultConfig()
            val repo = DirectRepositoryImpl.create(storage, "test-password", config)

            repo.close()

            assertThrows<IllegalStateException> {
                repo.openObject(ObjectId.direct(org.kopiaKt.core.content.ContentId.Empty))
            }
        }

        @Test
        fun `refresh reloads indexes from storage`() = runTest {
            val config = createDefaultConfig()
            val repo = DirectRepositoryImpl.create(storage, "test-password", config)

            // Write data in a write session
            val writer = repo.newDirectWriter()
            val objectId = writer.writeObject("test".toByteArray())
            writer.flush()

            // Refresh should load the new data
            repo.refresh()

            val readData = repo.readObject(objectId)
            assertThat(readData).isEqualTo("test".toByteArray())
        }
    }
}

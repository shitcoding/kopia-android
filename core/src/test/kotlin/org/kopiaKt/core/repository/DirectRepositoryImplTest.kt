package org.kopiaKt.core.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.manifest.ManifestManager
import org.kopiaKt.core.manifest.ManifestNotFoundException
import java.security.SecureRandom

class DirectRepositoryImplTest {

    private lateinit var storage: InMemoryBlobStorage

    @BeforeEach
    fun setUp() {
        storage = InMemoryBlobStorage()
    }

    // === Create Repository Tests ===

    @Test
    fun `create repository succeeds with valid config`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        val repo = DirectRepositoryImpl.create(storage, password, config)

        assertNotNull(repo)
        assertEquals(config.splitter, repo.objectFormat().splitter)
        repo.close()
    }

    @Test
    fun `create repository with custom client options`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val clientOptions = ClientOptions(
            hostname = "test-host",
            username = "test-user",
            description = "Test repository"
        )

        val repo = DirectRepositoryImpl.create(storage, password, config, clientOptions)

        assertEquals("test-host", repo.clientOptions().hostname)
        assertEquals("test-user", repo.clientOptions().username)
        assertEquals("Test repository", repo.clientOptions().description)
        repo.close()
    }

    @Test
    fun `initialize is alias for create`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        val repo = DirectRepositoryImpl.initialize(storage, password, config)

        assertNotNull(repo)
        repo.close()
    }

    // === Open Repository Tests ===

    @Test
    fun `open repository with correct password succeeds`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        // Create repository
        DirectRepositoryImpl.create(storage, password, config).close()

        // Open repository
        val repo = DirectRepositoryImpl.open(storage, password)

        assertNotNull(repo)
        assertEquals(config.splitter, repo.objectFormat().splitter)
        repo.close()
    }

    @Test
    fun `connect is alias for open`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        DirectRepositoryImpl.create(storage, password, config).close()

        val repo = DirectRepositoryImpl.connect(storage, password)

        assertNotNull(repo)
        repo.close()
    }

    // === Object Read/Write Tests ===

    @Test
    fun `write and read object round-trip`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val writer = repo.newDirectWriter()
        val data = "Hello, Kopia!".toByteArray()
        val objectId = writer.writeObject(data)
        writer.flush()

        // Read back from the writer (shares state)
        val readData = writer.readObject(objectId)

        assertArrayEquals(data, readData)
        writer.close()
        repo.close()
    }

    @Test
    fun `write large object creates indirect object`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val writer = repo.newDirectWriter()
        // Create data larger than average chunk size to trigger chunking
        val data = ByteArray(5 * 1024 * 1024) // 5MB
        SecureRandom().nextBytes(data)
        val objectId = writer.writeObject(data)
        writer.flush()

        // Read back from writer
        val readData = writer.readObject(objectId)

        assertArrayEquals(data, readData)
        writer.close()
        repo.close()
    }

    @Test
    fun `verify object returns content IDs`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val writer = repo.newDirectWriter()
        val data = "Test data for verification".toByteArray()
        val objectId = writer.writeObject(data)
        writer.flush()

        val contentIds = writer.verifyObject(objectId)

        assertTrue(contentIds.isNotEmpty())
        writer.close()
        repo.close()
    }

    // === Manifest Tests ===

    @Serializable
    data class TestPayload(
        val name: String,
        val value: Int
    )

    @Test
    fun `put and get manifest round-trip`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val writer = repo.newDirectWriter()
        val labels = mapOf("type" to "test", "key" to "value")
        val payload = TestPayload("test-name", 42)
        val manifestId = writer.putManifest(labels, payload, serializer())
        writer.flush()

        // Read back from writer
        val (readPayload, metadata) = writer.getManifest(manifestId, serializer<TestPayload>())

        assertEquals(payload.name, readPayload.name)
        assertEquals(payload.value, readPayload.value)
        assertEquals(labels, metadata.labels)
        writer.close()
        repo.close()
    }

    @Test
    fun `find manifests by labels`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val writer = repo.newDirectWriter()

        // Put multiple manifests
        writer.putManifest(mapOf("type" to "test", "category" to "A"), TestPayload("a1", 1), serializer())
        writer.putManifest(mapOf("type" to "test", "category" to "A"), TestPayload("a2", 2), serializer())
        writer.putManifest(mapOf("type" to "test", "category" to "B"), TestPayload("b1", 3), serializer())
        writer.flush()

        // Find by category A from writer
        val resultsA = writer.findManifests(mapOf("type" to "test", "category" to "A"))
        assertEquals(2, resultsA.size)

        // Find by category B from writer
        val resultsB = writer.findManifests(mapOf("type" to "test", "category" to "B"))
        assertEquals(1, resultsB.size)

        writer.close()
        repo.close()
    }

    @Test
    fun `delete manifest removes it from queries`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val writer = repo.newDirectWriter()
        val labels = mapOf("type" to "test")
        val manifestId = writer.putManifest(labels, TestPayload("test", 1), serializer())
        writer.flush()

        // Verify it exists
        var results = writer.findManifests(labels)
        assertEquals(1, results.size)

        // Delete it
        writer.deleteManifest(manifestId)
        writer.flush()

        // Verify it's gone (need to refresh)
        writer.refresh()
        results = writer.findManifests(labels)
        assertEquals(0, results.size)

        writer.close()
        repo.close()
    }

    @Test
    fun `get non-existent manifest throws`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val fakeId = org.kopiaKt.core.manifest.ManifestId.generate()

        assertThrows<ManifestNotFoundException> {
            runBlocking { repo.getManifest(fakeId, serializer<TestPayload>()) }
        }

        repo.close()
    }

    // === Repository State Tests ===

    @Test
    fun `refresh reloads indexes`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        // Create repo and write data
        val repo1 = DirectRepositoryImpl.create(storage, password, config)
        val writer = repo1.newDirectWriter()
        val objectId = writer.writeObject("Test data".toByteArray())
        writer.flush()
        writer.close()
        repo1.close()

        // Open new connection
        val repo2 = DirectRepositoryImpl.open(storage, password)
        repo2.refresh()

        // Should be able to read the data
        val data = repo2.readObject(objectId)
        assertEquals("Test data", String(data))

        repo2.close()
    }

    @Test
    fun `close prevents further operations`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        repo.close()

        assertThrows<IllegalStateException> {
            runBlocking { repo.readObject(ObjectId.Empty) }
        }
    }

    @Test
    fun `update description changes client options`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        repo.updateDescription("New description")

        assertEquals("New description", repo.clientOptions().description)
        repo.close()
    }

    // === DirectRepository Interface Tests ===

    @Test
    fun `unique ID is available`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val uniqueId = repo.uniqueId()

        assertEquals(32, uniqueId.size)
        repo.close()
    }

    @Test
    fun `derive key produces consistent results`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val key1 = repo.deriveKey("test-purpose", 32)
        val key2 = repo.deriveKey("test-purpose", 32)

        assertArrayEquals(key1, key2)
        assertEquals(32, key1.size)
        repo.close()
    }

    @Test
    fun `derive key with different purposes produces different keys`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val key1 = repo.deriveKey("purpose-1", 32)
        val key2 = repo.deriveKey("purpose-2", 32)

        assertTrue(!key1.contentEquals(key2))
        repo.close()
    }

    @Test
    fun `blob reader provides access to storage`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val blobReader = repo.blobReader()

        assertNotNull(blobReader)
        repo.close()
    }

    @Test
    fun `supports password change based on config`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig().copy(enablePasswordChange = true)
        val repo = DirectRepositoryImpl.create(storage, password, config)

        assertTrue(repo.supportsPasswordChange())
        repo.close()
    }

    // === Write Session Tests ===

    @Test
    fun `write session flushes on success`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val objectId = writeSession(repo) { writer ->
            writer.writeObject("Test data".toByteArray())
        }

        // Should be readable after refresh
        repo.refresh()
        val data = repo.readObject(objectId)
        assertEquals("Test data", String(data))

        repo.close()
    }

    @Test
    fun `direct write session provides low-level access`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        directWriteSession(repo) { writer ->
            assertNotNull(writer.blobStorage())
        }

        repo.close()
    }

    // === Concatenate Objects Tests ===

    @Test
    fun `concatenate objects combines multiple objects`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()
        val repo = DirectRepositoryImpl.create(storage, password, config)

        val writer = repo.newDirectWriter()
        val obj1 = writer.writeObject("Hello, ".toByteArray())
        val obj2 = writer.writeObject("World!".toByteArray())
        writer.flush()

        val concatenated = writer.concatenateObjects(listOf(obj1, obj2))
        writer.flush()

        val data = writer.readObject(concatenated)
        assertEquals("Hello, World!", String(data))

        writer.close()
        repo.close()
    }

    // === Helper Functions ===

    private fun createTestConfig() = RepositoryConfig(
        hash = "BLAKE2B-256-128",
        encryption = "AES256-GCM-HMAC-SHA256",
        secret = ByteArray(32) { it.toByte() },
        masterKey = ByteArray(32) { (it + 100).toByte() },
        version = 3,
        maxPackSize = 20 * 1024 * 1024,
        indexVersion = 2,
        enablePasswordChange = true,
        splitter = "DYNAMIC-4M-BUZHASH"
    )
}

package org.kopiaKt.core.format

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.InMemoryBlobStorage
import java.io.IOException

class FormatBlobManagerTest {

    private lateinit var storage: InMemoryBlobStorage
    private lateinit var manager: FormatBlobManager

    @BeforeEach
    fun setUp() {
        storage = InMemoryBlobStorage()
        manager = FormatBlobManager(storage)
    }

    // === Create Repository Tests ===

    @Test
    fun `createRepository creates format blob`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        val result = manager.createRepository(password, config)

        assertNotNull(result.formatJson)
        assertNotNull(result.config)
        assertNotNull(result.formatEncryptionKey)
        assertTrue(storage.contains(BlobId("kopia.repository")))
    }

    @Test
    fun `createRepository with custom build version`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        val result = manager.createRepository(
            password = password,
            config = config,
            buildVersion = "kopiaKt-1.0.0",
        )

        assertEquals("kopiaKt-1.0.0", result.formatJson.buildVersion)
    }

    @Test
    fun `createRepository fails if repository exists`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        // Create first repository
        manager.createRepository(password, config)

        // Try to create second repository
        assertThrows<RepositoryAlreadyExistsException> {
            runBlocking { manager.createRepository(password, config) }
        }
        Unit
    }

    // === Open Repository Tests ===

    @Test
    fun `openRepository with correct password succeeds`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig()

        manager.createRepository(password, config)

        val result = manager.openRepository(password)

        assertEquals(config.hash, result.config.hash)
        assertEquals(config.encryption, result.config.encryption)
        assertArrayEquals(config.secret, result.config.secret)
    }

    @Test
    fun `openRepository with wrong password fails`() = runBlocking {
        val password = "correct-password"
        val config = createTestConfig()

        manager.createRepository(password, config)

        assertThrows<InvalidPasswordException> {
            runBlocking { manager.openRepository("wrong-password") }
        }
        Unit
    }

    @Test
    fun `openRepository fails if repository does not exist`() = runBlocking {
        assertThrows<FormatBlobNotFoundException> {
            runBlocking { manager.openRepository("any-password") }
        }
        Unit
    }

    @Test
    fun `openRepository validates format version`() = runBlocking {
        // Create with valid version
        val password = "test-password"
        val config = createTestConfig()

        manager.createRepository(password, config)

        // Should succeed
        val result = manager.openRepository(password)
        assertTrue(result.config.version in 1..3)
    }

    // === Change Password Tests ===

    @Test
    fun `changePassword works with correct current password`() = runBlocking {
        val oldPassword = "old-password"
        val newPassword = "new-password"
        val config = createTestConfig()

        manager.createRepository(oldPassword, config)

        // Change password
        manager.changePassword(oldPassword, newPassword)

        // Old password should fail
        assertThrows<InvalidPasswordException> {
            runBlocking { manager.openRepository(oldPassword) }
        }

        // New password should work
        val result = manager.openRepository(newPassword)
        assertEquals(config.hash, result.config.hash)
    }

    @Test
    fun `changePassword fails with wrong current password`() = runBlocking {
        val password = "correct-password"
        val config = createTestConfig()

        manager.createRepository(password, config)

        assertThrows<InvalidPasswordException> {
            runBlocking { manager.changePassword("wrong-password", "new-password") }
        }
        Unit
    }

    @Test
    fun `changePassword fails for format V1`() = runBlocking {
        val password = "test-password"
        val config = createTestConfig().copy(
            version = 1,
            enablePasswordChange = false,
        )

        manager.createRepository(password, config)

        assertThrows<UnsupportedOperationException> {
            runBlocking { manager.changePassword(password, "new-password") }
        }
        Unit
    }

    @Test
    fun `changePassword can change key derivation algorithm`() = runBlocking {
        val password = "test-password"
        val newPassword = "new-password"
        val config = createTestConfig()

        // Create with scrypt
        manager.createRepository(
            password = password,
            config = config,
            keyDerivationAlgorithm = "scrypt-65536-8-1",
        )

        // Change to pbkdf2
        manager.changePassword(
            currentPassword = password,
            newPassword = newPassword,
            newKeyDerivationAlgorithm = "pbkdf2-sha256-600000",
        )

        // Verify new algorithm is used
        val formatJson = manager.readFormatBlob()
        assertEquals("pbkdf2-sha256-600000", formatJson.keyDerivationAlgorithm)

        // Verify new password works
        val result = manager.openRepository(newPassword)
        assertNotNull(result)
    }

    // === Read/Write Format Blob Tests ===

    @Test
    fun `readFormatBlob and writeFormatBlob round-trip`() = runBlocking {
        val formatJson = KopiaRepositoryJson.create(buildVersion = "test-1.0")

        manager.writeFormatBlob(formatJson)

        val read = manager.readFormatBlob()

        assertEquals(formatJson.tool, read.tool)
        assertEquals(formatJson.buildVersion, read.buildVersion)
        assertArrayEquals(formatJson.uniqueID, read.uniqueID)
    }

    @Test
    fun `readFormatBlob throws if blob does not exist`() = runBlocking {
        assertThrows<FormatBlobNotFoundException> {
            runBlocking { manager.readFormatBlob() }
        }
        Unit
    }

    // === Integration Tests ===

    @Test
    fun `full repository lifecycle`() = runBlocking {
        val password = "initial-password"
        val config = RepositoryConfig(
            hash = "BLAKE2B-256-128",
            encryption = "AES256-GCM-HMAC-SHA256",
            secret = ByteArray(32) { it.toByte() },
            masterKey = ByteArray(32) { (it + 50).toByte() },
            version = 3,
            maxPackSize = 20 * 1024 * 1024,
            indexVersion = 2,
            enablePasswordChange = true,
            splitter = "DYNAMIC-4M-BUZHASH",
        )

        // Create repository
        val createResult = manager.createRepository(password, config, buildVersion = "kopiaKt-test")
        assertEquals("kopia", createResult.formatJson.tool)
        assertEquals("kopiaKt-test", createResult.formatJson.buildVersion)

        // Open repository
        val openResult = manager.openRepository(password)
        assertEquals(config.hash, openResult.config.hash)
        assertEquals(config.encryption, openResult.config.encryption)
        assertEquals(config.splitter, openResult.config.splitter)
        assertArrayEquals(config.secret, openResult.config.secret)
        assertArrayEquals(config.masterKey, openResult.config.masterKey)

        // Change password
        val newPassword = "new-password"
        manager.changePassword(password, newPassword)

        // Open with new password
        val openResult2 = manager.openRepository(newPassword)
        assertEquals(config.hash, openResult2.config.hash)

        // Old password should fail
        assertThrows<InvalidPasswordException> {
            runBlocking { manager.openRepository(password) }
        }
        Unit
    }

    // === Existence-probe classification (a misread here destroys an existing repository) ===

    @Test
    fun `readFormatBlob propagates a read failure instead of reporting the blob missing`() = runBlocking {
        val failing = FailingReadBlobStorage(storage, IOException("connection reset"))

        assertThrows<IOException> {
            runBlocking { FormatBlobManager(failing).readFormatBlob() }
        }
        Unit
    }

    @Test
    fun `createRepository refuses to overwrite when the existence probe fails`() = runBlocking {
        // An existing repository the user is about to point "create" at.
        val original = manager.createRepository("original-password", createTestConfig())
        val originalBytes = storage.getBlob(BlobId("kopia.repository"), 0, -1)

        // The probe read fails transiently (flaky network, throttling, a 500).
        val failing = FailingReadBlobStorage(storage, IOException("connection reset"))

        assertThrows<IOException> {
            runBlocking { FormatBlobManager(failing).createRepository("new-password", createTestConfig()) }
        }

        // The original format blob must be untouched: rewriting it with a fresh uniqueID and
        // master key makes every existing blob in the repository permanently undecryptable.
        assertArrayEquals(originalBytes, storage.getBlob(BlobId("kopia.repository"), 0, -1))
        val reopened = manager.openRepository("original-password")
        assertArrayEquals(original.formatJson.uniqueID, reopened.formatJson.uniqueID)
    }

    @Test
    fun `createRepository still succeeds when the format blob is genuinely absent`() = runBlocking {
        val result = manager.createRepository("test-password", createTestConfig())
        assertTrue(storage.contains(BlobId("kopia.repository")))
        assertNotNull(result.formatJson)
    }

    /** Delegates everything to [delegate] but fails every [getBlob] with [error]. */
    private class FailingReadBlobStorage(
        private val delegate: InMemoryBlobStorage,
        private val error: Exception,
    ) : BlobStorage by delegate {
        override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray = throw error
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
        splitter = "DYNAMIC-4M-BUZHASH",
    )
}

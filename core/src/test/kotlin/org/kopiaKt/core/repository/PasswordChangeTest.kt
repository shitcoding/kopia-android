package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.format.FormatBlobManager
import org.kopiaKt.core.format.InvalidPasswordException
import org.kopiaKt.core.format.RepositoryConfig
import java.security.SecureRandom

/**
 * Tests for repository password change via FormatBlobManager.changePassword().
 *
 * Verifies that:
 * - Password can be changed and the repository opened with the new password
 * - Old password is rejected after a change
 * - Object data survives a password change
 * - Manifest data survives a password change
 */
@DisplayName("Repository Password Change")
class PasswordChangeTest {

    private fun createConfig(enablePasswordChange: Boolean = true): RepositoryConfig {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val masterKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return RepositoryConfig(
            hash = "BLAKE2B-256-128",
            encryption = "AES256-GCM-HMAC-SHA256",
            secret = secret,
            masterKey = masterKey,
            splitter = "FIXED-1M",
            enablePasswordChange = enablePasswordChange,
        )
    }

    @Test
    fun `should change password and open with new password`() = runTest {
        val storage = InMemoryBlobStorage()
        val config = createConfig()
        val fbm = FormatBlobManager(storage)

        val createResult = fbm.createRepository("original", config)

        fbm.changePassword("original", "newpass")

        val openResult = fbm.openRepository("newpass")

        assertThat(openResult.config.secret).isEqualTo(createResult.config.secret)
        assertThat(openResult.config.masterKey).isEqualTo(createResult.config.masterKey)
        assertThat(openResult.config.hash).isEqualTo(createResult.config.hash)
        assertThat(openResult.config.encryption).isEqualTo(createResult.config.encryption)
    }

    @Test
    fun `should fail to open with old password after change`() = runTest {
        val storage = InMemoryBlobStorage()
        val config = createConfig()
        val fbm = FormatBlobManager(storage)

        fbm.createRepository("original", config)
        fbm.changePassword("original", "newpass")

        assertThrows<InvalidPasswordException> {
            fbm.openRepository("original")
        }
    }

    @Serializable
    private data class TestPayload(val name: String, val value: Int)

    @Test
    fun `should preserve all data after password change`() = runTest {
        val storage = InMemoryBlobStorage()
        val config = createConfig()

        // Create repo and write some objects
        val repo = DirectRepositoryImpl.create(storage, "original", config)
        val data1 = "Hello, Kopia!".toByteArray()
        val data2 = ByteArray(8192).also { SecureRandom().nextBytes(it) }

        val writer = repo.newDirectWriter()
        val objectId1 = writer.writeObject(data1)
        val objectId2 = writer.writeObject(data2)
        writer.flush()
        repo.refresh()
        repo.close()

        // Change password via FormatBlobManager
        val fbm = FormatBlobManager(storage)
        fbm.changePassword("original", "newpass")

        // Reopen with new password and verify data
        val repo2 = DirectRepositoryImpl.open(storage, "newpass")
        val readData1 = repo2.readObject(objectId1)
        val readData2 = repo2.readObject(objectId2)

        assertThat(readData1).isEqualTo(data1)
        assertThat(readData2).isEqualTo(data2)
        repo2.close()
    }

    @Test
    fun `should preserve manifests after password change`() = runTest {
        val storage = InMemoryBlobStorage()
        val config = createConfig()

        // Create repo and write manifests
        val repo = DirectRepositoryImpl.create(storage, "original", config)
        val labels = mapOf("type" to "test-snapshot", "source" to "/data")
        val payload = TestPayload("backup-1", 42)

        val writer = repo.newDirectWriter()
        val manifestId = writer.putManifest(labels, payload, serializer<TestPayload>())
        writer.flush()
        repo.refresh()
        repo.close()

        // Change password
        val fbm = FormatBlobManager(storage)
        fbm.changePassword("original", "newpass")

        // Reopen with new password and verify manifests
        val repo2 = DirectRepositoryImpl.open(storage, "newpass")
        val (retrieved, metadata) = repo2.getManifest(manifestId, serializer<TestPayload>())

        assertThat(retrieved).isEqualTo(payload)
        assertThat(metadata.labels).isEqualTo(labels)

        val found = repo2.findManifests(mapOf("type" to "test-snapshot"))
        assertThat(found).hasSize(1)
        assertThat(found[0].id).isEqualTo(manifestId)
        repo2.close()
    }
}

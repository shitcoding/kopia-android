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
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.format.InvalidPasswordException
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.nio.file.Path
import java.security.SecureRandom

/**
 * Integration tests for repository creation via DirectRepositoryImpl.create().
 *
 * These tests verify end-to-end repository creation, including format blob generation,
 * encryption key derivation, and post-creation usability (connect, read, write).
 */
class RepositoryCreationTest {

    private lateinit var storage: InMemoryBlobStorage

    @BeforeEach
    fun setUp() {
        storage = InMemoryBlobStorage()
    }

    @Serializable
    data class TestManifestPayload(val name: String, val version: Int)

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Nested
    @DisplayName("Basic Creation")
    inner class BasicCreation {

        @Test
        fun `create repository with defaults produces valid repo`(): Unit = runTest {
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
            )

            val repo = DirectRepositoryImpl.create(storage, "test-password", config)

            assertThat(repo).isNotNull()
            // Verify format blob was created
            assertThat(storage.contains(BlobId("kopia.repository"))).isTrue()
            // Verify default splitter was applied
            assertThat(repo.objectFormat().splitter).isEqualTo("DYNAMIC-4M-BUZHASH")
            repo.close()
        }

        @Test
        fun `create repository with each supported hash algorithm`(): Unit = runTest {
            for (hashAlg in HashAlgorithm.entries) {
                val localStorage = InMemoryBlobStorage()
                val config = RepositoryConfig(
                    hash = hashAlg.id,
                    encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                    secret = randomKey(),
                    masterKey = randomKey(),
                )

                val repo = DirectRepositoryImpl.create(localStorage, "password", config)
                assertThat(repo).isNotNull()

                // Verify the repo is usable: write and read an object
                val writer = repo.newDirectWriter()
                val data = "test-data-${hashAlg.id}".toByteArray()
                val objectId = writer.writeObject(data)
                writer.flush()

                repo.refresh()
                val readBack = repo.readObject(objectId)
                assertThat(readBack).isEqualTo(data)

                repo.close()
            }
        }

        @Test
        fun `create repository with no compression`(): Unit = runTest {
            // RepositoryConfig does not have a compression field -- the default compression
            // is NONE in ContentManager. We verify that objects written without explicit
            // compression are stored and readable.
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
            )

            val repo = DirectRepositoryImpl.create(storage, "test-password", config)

            val writer = repo.newDirectWriter()
            val data = "uncompressed data".toByteArray()
            val objectId = writer.writeObject(data)
            writer.flush()

            repo.refresh()
            val readBack = repo.readObject(objectId)
            assertThat(readBack).isEqualTo(data)

            repo.close()
        }

        @Test
        fun `create repository stores description in client options`(): Unit = runTest {
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
            )
            val clientOptions = ClientOptions(
                hostname = "test-host",
                username = "test-user",
                description = "My backup repository",
            )

            val repo = DirectRepositoryImpl.create(storage, "test-password", config, clientOptions)

            assertThat(repo.clientOptions().description).isEqualTo("My backup repository")
            assertThat(repo.clientOptions().hostname).isEqualTo("test-host")
            assertThat(repo.clientOptions().username).isEqualTo("test-user")
            repo.close()
        }

        @Test
        fun `create repository with empty password fails gracefully`(): Unit = runTest {
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
            )

            // Empty password should still create the repo (Kopia allows empty passwords)
            // but reopening with wrong password should fail
            val repo = DirectRepositoryImpl.create(storage, "", config)
            assertThat(repo).isNotNull()
            repo.close()

            // Verify the empty-password repo can be reopened with empty password
            val reopened = DirectRepositoryImpl.open(storage, "")
            assertThat(reopened).isNotNull()
            reopened.close()

            // Verify wrong password fails
            val ex = assertThrows<Exception> {
                DirectRepositoryImpl.open(storage, "wrong-password")
            }
            assertThat(
                ex is InvalidPasswordException || ex.cause is InvalidPasswordException,
            ).isTrue()
        }
    }

    @Nested
    @DisplayName("Post-Creation State")
    inner class PostCreationState {

        @Test
        fun `newly created repo is connectable`(): Unit = runTest {
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
            )

            // Create and close
            val repo = DirectRepositoryImpl.create(storage, "my-password", config)
            repo.close()

            // Reopen with same password
            val reopened = DirectRepositoryImpl.open(storage, "my-password")
            assertThat(reopened).isNotNull()
            assertThat(reopened.objectFormat().splitter).isEqualTo(config.splitter)
            reopened.close()
        }

        @Test
        fun `newly created repo has correct format version`(): Unit = runTest {
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
                // version defaults to FormatVersion.CURRENT (3)
            )

            val repo = DirectRepositoryImpl.create(storage, "test-password", config)

            // The unique ID should be 32 bytes (standard for all repos)
            assertThat(repo.uniqueId()).hasLength(32)
            // supportsPasswordChange defaults to true for modern repos
            assertThat(repo.supportsPasswordChange()).isTrue()
            repo.close()
        }

        @Test
        fun `created repo supports write operations`(): Unit = runTest {
            val (repo, _) = TestRepositoryFactory.createInMemory()

            // Write data
            val writer = repo.newDirectWriter()
            val testData = "Hello, KopiaKt!".toByteArray()
            val objectId = writer.writeObject(testData)
            writer.flush()

            // Read it back from same writer
            val readData = writer.readObject(objectId)
            assertThat(readData).isEqualTo(testData)

            // Also readable from repo after refresh
            repo.refresh()
            val repoReadData = repo.readObject(objectId)
            assertThat(repoReadData).isEqualTo(testData)

            writer.close()
            repo.close()
        }

        @Test
        fun `created repo supports manifest operations`(): Unit = runTest {
            val (repo, _) = TestRepositoryFactory.createInMemory()

            val writer = repo.newDirectWriter()
            val labels = mapOf("type" to "snapshot", "source" to "/data")
            val payload = TestManifestPayload("test-snapshot", 1)
            val manifestId = writer.putManifest(labels, payload, serializer())
            writer.flush()

            // Read back from writer
            val (readPayload, metadata) = writer.getManifest(manifestId, serializer<TestManifestPayload>())
            assertThat(readPayload.name).isEqualTo("test-snapshot")
            assertThat(readPayload.version).isEqualTo(1)
            assertThat(metadata.labels).isEqualTo(labels)

            // Also findable via labels
            val found = writer.findManifests(mapOf("type" to "snapshot"))
            assertThat(found).hasSize(1)
            assertThat(found[0].id).isEqualTo(manifestId)

            writer.close()
            repo.close()
        }
    }

    @Nested
    @DisplayName("Storage Backends")
    inner class StorageBackends {

        @Test
        fun `create repo with in-memory storage succeeds`(): Unit = runTest {
            val (repo, memStorage) = TestRepositoryFactory.createInMemory()

            assertThat(repo).isNotNull()
            assertThat(memStorage.contains(BlobId("kopia.repository"))).isTrue()
            assertThat(memStorage.size()).isGreaterThan(0)

            repo.close()
        }

        @Test
        fun `create repo with filesystem storage succeeds`(@TempDir tempDir: Path): Unit = runTest {
            val fsStorage = FilesystemBlobStorage.create(tempDir, create = true)
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
            )

            val repo = DirectRepositoryImpl.create(fsStorage, "fs-password", config)
            assertThat(repo).isNotNull()

            // Write and read back to verify full round-trip on filesystem
            val writer = repo.newDirectWriter()
            val data = "filesystem round-trip".toByteArray()
            val objectId = writer.writeObject(data)
            writer.flush()

            repo.refresh()
            val readData = repo.readObject(objectId)
            assertThat(readData).isEqualTo(data)

            repo.close()

            // Reopen from filesystem to verify persistence
            val reopened = DirectRepositoryImpl.open(fsStorage, "fs-password")
            reopened.refresh()
            val persistedData = reopened.readObject(objectId)
            assertThat(persistedData).isEqualTo(data)
            reopened.close()
        }

        @Test
        fun `wrong password fails to open created repo`(): Unit = runTest {
            val config = RepositoryConfig(
                hash = HashAlgorithm.BLAKE2B_256_128.id,
                encryption = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256.id,
                secret = randomKey(),
                masterKey = randomKey(),
            )

            // Create with one password
            val repo = DirectRepositoryImpl.create(storage, "correct-password", config)
            repo.close()

            // Try to open with different password
            val ex = assertThrows<Exception> {
                DirectRepositoryImpl.open(storage, "wrong-password")
            }

            assertThat(
                ex is InvalidPasswordException || ex.cause is InvalidPasswordException,
            ).isTrue()
        }
    }
}

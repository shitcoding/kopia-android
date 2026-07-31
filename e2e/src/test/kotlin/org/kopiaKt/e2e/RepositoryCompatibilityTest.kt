package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepositoryImpl
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * E2E tests for repository-level cross-compatibility between
 * Kotlin implementation and Go Kopia.
 *
 * These tests verify that:
 * 1. Repositories created by Go can be opened by Kotlin
 * 2. Repositories created by Kotlin can be reopened
 * 3. Basic operations work across implementations
 */
class RepositoryCompatibilityTest : CrossCompatibilityTestBase() {

    @AfterEach
    fun tearDown() = runTest {
        cleanup()
    }

    @Nested
    @DisplayName("Go Creates, Kotlin Opens")
    inner class GoCreatesKotlinOpens {

        @Test
        @DisplayName("Should open repository created by Go Kopia")
        fun openGoRepository(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            // Verify repository files exist (Go Kopia uses .f suffix)
            assertThat(repoDir.resolve("kopia.repository.f").exists()).isTrue()

            // Open with Kotlin
            val repo = openRepositoryWithKotlin()
            repo.use {
                // Basic verification - repository is open and functional
                assertThat(repo).isNotNull()
                assertThat(repo.uniqueId()).isNotEmpty()
            }
        }

        @Test
        @DisplayName("Should read format blob from Go repository")
        fun readFormatBlob(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            // Open with Kotlin and verify format
            val repo = openRepositoryWithKotlin()
            repo.use {
                // The fact that we could open it means format was read correctly
                assertThat(repo).isNotNull()
                assertThat(repo.objectFormat()).isNotNull()
            }
        }

        @Test
        @DisplayName("Should handle different hash algorithms")
        fun differentHashAlgorithms(): Unit = runTest {
            requireGoKopia()

            // Test BLAKE3-256
            createRepositoryWithGo(hashAlgorithm = "BLAKE3-256")

            val repo = openRepositoryWithKotlin()
            repo.use {
                assertThat(repo).isNotNull()
            }
        }

        @Test
        @DisplayName("Should write and read objects in Go repository")
        fun writeAndReadObjects(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            // Open with Kotlin and write/read objects
            val repo = openRepositoryWithKotlin()
            repo.use {
                // Write some test data
                val testData = "Hello from Kotlin!".toByteArray()
                val objectId = repo.writeObject(testData)
                assertThat(objectId).isNotNull()

                // Read it back
                val readData = repo.readObject(objectId)
                assertThat(readData).isEqualTo(testData)

                // Flush to ensure it's persisted
                repo.flush()
            }

            // Verify Go can still access the repository
            val status = cliRunner.repositoryStatus()
            assertThat(status.success).isTrue()
        }
    }

    @Nested
    @DisplayName("Kotlin Creates Repository")
    inner class KotlinCreatesRepository {

        @Test
        @DisplayName("Should create valid repository structure")
        fun createValidRepository(): Unit = runTest {
            // Create with Kotlin
            val repo = createRepositoryWithKotlin()
            repo.use {
                assertThat(repo).isNotNull()
            }

            // Verify repository files (Kotlin also uses .f suffix for Go Kopia compatibility)
            assertThat(repoDir.resolve("kopia.repository.f").exists()).isTrue()

            // Should have some blob files
            val blobs = repoDir.listDirectoryEntries()
            assertThat(blobs).isNotEmpty()
        }

        @Test
        @DisplayName("Should reopen Kotlin-created repository")
        fun reopenKotlinRepository(): Unit = runTest {
            // Create with Kotlin
            val repo1 = createRepositoryWithKotlin()
            repo1.use {
                // Write some content
                val testData = "Test content".toByteArray()
                repo1.writeObject(testData)
                repo1.flush()
            }

            // Reopen
            val repo2 = openRepositoryWithKotlin()
            repo2.use {
                assertThat(repo2).isNotNull()
            }
        }
    }

    @Nested
    @DisplayName("Object Operations")
    inner class ObjectOperations {

        @Test
        @DisplayName("Should write and read objects in Go repository")
        fun writeReadObjects(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            val repo = openRepositoryWithKotlin()
            repo.use {
                // Write object
                val originalData = "Test content for E2E".toByteArray()
                val objectId = repo.writeObject(originalData)

                // Read it back
                val readData = repo.readObject(objectId)

                assertThat(readData).isEqualTo(originalData)
            }
        }

        @Test
        @DisplayName("Should persist objects across repository open/close")
        fun persistObjects(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            val testData = "Persistent test data".toByteArray()
            var objectIdStr: String

            // Write object
            val repo1 = openRepositoryWithKotlin()
            repo1.use {
                val objectId = repo1.writeObject(testData)
                objectIdStr = objectId.toString()
                repo1.flush()
            }

            // Reopen and verify
            val repo2 = openRepositoryWithKotlin()
            repo2.use {
                val objectId = ObjectId.parse(objectIdStr)
                val readData = repo2.readObject(objectId)
                assertThat(readData).isEqualTo(testData)
            }
        }

        @Test
        @DisplayName("Should handle large objects")
        fun largeObjects(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            val repo = openRepositoryWithKotlin()
            repo.use {
                // Write 1MB of data
                val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
                val objectId = repo.writeObject(largeData)

                // Read it back
                val readData = repo.readObject(objectId)

                assertThat(readData).isEqualTo(largeData)
            }
        }
    }

    @Nested
    @DisplayName("Manifest Operations")
    inner class ManifestOperations {

        @Test
        @DisplayName("Should write and read manifests in Go repository")
        fun writeReadManifests(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            val repo = openRepositoryWithKotlin()
            repo.use {
                // Write a test manifest
                val labels = mapOf(
                    "type" to "test",
                    "name" to "e2e-test-manifest",
                )
                val content = "test-payload"

                val manifestId = repo.putManifest(labels, content, String.serializer())
                assertThat(manifestId).isNotNull()

                repo.flush()

                // Read it back by finding it
                val found = repo.findManifests(mapOf("type" to "test"))
                assertThat(found).isNotEmpty()

                // Verify the content - getManifest returns Pair<T, EntryMetadata>
                val result = repo.getManifest(found.first().id, String.serializer())
                val readContent = result.first
                val metadata = result.second
                assertThat(metadata.labels["type"]).isEqualTo("test")
                assertThat(metadata.labels["name"]).isEqualTo("e2e-test-manifest")
                assertThat(readContent).isEqualTo(content)
            }
        }

        @Test
        @DisplayName("Should find manifests by label")
        fun findManifestsByLabel(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            val repo = openRepositoryWithKotlin()
            repo.use {
                // Write multiple manifests
                repeat(3) { i ->
                    val labels = mapOf(
                        "type" to "test",
                        "index" to i.toString(),
                    )
                    repo.putManifest(labels, "content $i", String.serializer())
                }

                repo.flush()

                // Find by type
                val found = repo.findManifests(mapOf("type" to "test"))

                assertThat(found).hasSize(3)
            }
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandling {

        @Test
        @DisplayName("Should fail gracefully with wrong password")
        fun wrongPassword(): Unit = runTest {
            requireGoKopia()

            // Create repository with Go
            createRepositoryWithGo()

            // Try to open with wrong password
            try {
                val storage = createBlobStorage()
                DirectRepositoryImpl.open(storage, "wrong-password")
                throw AssertionError("Should have thrown an exception")
            } catch (e: Exception) {
                // Expected - password mismatch or decryption failure
                assertThat(e).isNotNull()
            }
        }

        @Test
        @DisplayName("Should fail on non-existent repository")
        fun nonExistentRepository(): Unit = runTest {
            // Try to open non-existent repository
            try {
                openRepositoryWithKotlin()
                throw AssertionError("Should have thrown an exception")
            } catch (e: Exception) {
                // Expected - repository doesn't exist
                assertThat(e).isNotNull()
            }
        }
    }
}

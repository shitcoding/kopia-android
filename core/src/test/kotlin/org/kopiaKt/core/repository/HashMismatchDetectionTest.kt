package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.testutil.CorruptionHelpers
import org.kopiaKt.core.testutil.TestRepositoryFactory

/**
 * Tests that bit-rot / silent data corruption in pack blobs is detected
 * during readObject() because AEAD decryption (AES-256-GCM) will reject
 * tampered ciphertext.
 *
 * verifyObject() only checks index existence and does NOT read or verify
 * actual blob data, so it must still succeed on corrupted blobs.
 *
 * Pack blob layout: [PREAMBLE (32B)] [CONTENT BLOCKS (encrypted)] [LOCAL INDEX] [POSTAMBLE]
 * Each content block is individually encrypted with AES-256-GCM (12B nonce + ciphertext + 16B tag).
 * Corruption within a content block causes AEAD decryption to fail.
 * Corruption in the local index or postamble does NOT affect content reads (the index
 * offsets come from separate index blobs, not the pack's local index).
 */
@DisplayName("Hash Mismatch / Bit-Rot Detection")
class HashMismatchDetectionTest {

    private lateinit var repo: DirectRepositoryImpl
    private lateinit var storage: InMemoryBlobStorage
    private lateinit var objectIds: Map<String, ObjectId>

    /**
     * Offset into the pack blob that is guaranteed to be within the encrypted
     * content data area (past the 32-byte preamble and 12-byte GCM nonce).
     */
    private val contentAreaCorruptionOffset = 48

    @AfterEach
    fun tearDown() {
        if (::repo.isInitialized) {
            repo.close()
        }
    }

    /**
     * Helper: corrupt every pack blob ("p" prefix) in the content data area.
     */
    private suspend fun corruptAllPackBlobs() {
        val packBlobs = storage.listBlobs("p").toList()
        assertThat(packBlobs).isNotEmpty()

        for (blobMeta in packBlobs) {
            val blobData = storage.getBlob(blobMeta.blobId)
            // Corrupt within the encrypted content area
            val offset = contentAreaCorruptionOffset.coerceAtMost(blobData.size - 1)
            val corruptedData = CorruptionHelpers.bitFlip(blobData, offset)
            storage.putBlob(blobMeta.blobId, corruptedData, PutBlobOptions())
        }
    }

    @Nested
    @DisplayName("Single Object Corruption")
    inner class SingleObjectCorruption {

        @Test
        fun `should detect corrupted content during readObject`(): Unit = runTest {
            val data = "integrity-test-payload".toByteArray()
            val result = TestRepositoryFactory.createWithObjects(
                objects = mapOf("obj" to data),
            )
            repo = result.first
            storage = result.second
            objectIds = result.third

            // Verify the object is readable before corruption
            val readBefore = repo.readObject(objectIds.getValue("obj"))
            assertThat(readBefore).isEqualTo(data)

            // Corrupt within the encrypted content data area of the pack blob
            corruptAllPackBlobs()

            // Re-open the repo so it reads fresh from storage
            repo.close()
            repo = DirectRepositoryImpl.open(storage, "test-password")

            // readObject must fail because AEAD decryption rejects tampered data
            assertThrows<Exception> {
                repo.readObject(objectIds.getValue("obj"))
            }
        }

        @Test
        fun `should still succeed verifyObject on corrupted blob`(): Unit = runTest {
            val data = "verify-vs-read-test".toByteArray()
            val result = TestRepositoryFactory.createWithObjects(
                objects = mapOf("obj" to data),
            )
            repo = result.first
            storage = result.second
            objectIds = result.third

            // Corrupt the pack blob in the content data area
            corruptAllPackBlobs()

            // verifyObject only checks index existence -- it must succeed
            // even though the underlying blob data is corrupted
            val contentIds = repo.verifyObject(objectIds.getValue("obj"))
            assertThat(contentIds).isNotEmpty()
        }
    }

    @Nested
    @DisplayName("Targeted Encrypted Data Corruption")
    inner class TargetedEncryptedDataCorruption {

        @Test
        fun `should detect when encrypted blob is bit-flipped in storage`(): Unit = runTest {
            // Use a larger payload to ensure the encrypted data area is substantial
            val data = ByteArray(4096) { (it % 251).toByte() }
            val result = TestRepositoryFactory.createWithObjects(
                objects = mapOf("large" to data),
            )
            repo = result.first
            storage = result.second
            objectIds = result.third

            // Verify readable before corruption
            val readBefore = repo.readObject(objectIds.getValue("large"))
            assertThat(readBefore).isEqualTo(data)

            // Corrupt a byte within the encrypted content area
            val packBlobs = storage.listBlobs("p").toList()
            assertThat(packBlobs).isNotEmpty()

            val blobId = packBlobs.first().blobId
            val blobData = storage.getBlob(blobId)

            // Flip a bit past the preamble and nonce, squarely in the ciphertext
            val corruptedData = CorruptionHelpers.bitFlip(blobData, contentAreaCorruptionOffset, 3)
            storage.putBlob(blobId, corruptedData, PutBlobOptions())

            // Re-open to force fresh reads from storage
            repo.close()
            repo = DirectRepositoryImpl.open(storage, "test-password")

            // AEAD must reject the tampered ciphertext
            assertThrows<Exception> {
                repo.readObject(objectIds.getValue("large"))
            }
        }
    }

    @Nested
    @DisplayName("Pack-Wide Corruption Impact")
    inner class PackWideCorruptionImpact {

        @Test
        fun `should fail reading at least one object when their shared pack blob is corrupted`(): Unit = runTest {
            // Write multiple small objects that will land in the same pack blob
            val objects = mapOf(
                "alpha" to "first-object-data-alpha".toByteArray(),
                "beta" to "second-object-data-beta".toByteArray(),
                "gamma" to "third-object-data-gamma".toByteArray(),
            )
            val result = TestRepositoryFactory.createWithObjects(objects)
            repo = result.first
            storage = result.second
            objectIds = result.third

            // Verify all objects are readable before corruption
            for ((key, expectedData) in objects) {
                val readData = repo.readObject(objectIds.getValue(key))
                assertThat(readData).isEqualTo(expectedData)
            }

            // Corrupt every pack blob in the content data area
            corruptAllPackBlobs()

            // Re-open to force fresh reads
            repo.close()
            repo = DirectRepositoryImpl.open(storage, "test-password")

            // Count how many objects fail to read
            var failureCount = 0
            for (key in objects.keys) {
                try {
                    repo.readObject(objectIds.getValue(key))
                } catch (_: Exception) {
                    failureCount++
                }
            }

            // At least one object must fail. In practice, if all three objects
            // share a pack blob, corrupting that blob's content area will cause
            // AEAD failure for whichever content block contains the corrupted byte.
            // Multiple objects may share the same pack, but only the content block
            // that was actually bit-flipped will fail decryption -- others in the
            // same pack at different offsets may still decrypt fine.
            assertThat(failureCount).isAtLeast(1)
        }
    }
}

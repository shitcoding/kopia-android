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
import org.kopiaKt.core.blob.RepositoryUnavailableException
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Edge case tests for SAF (Storage Access Framework) blob storage provider.
 *
 * Verifies correct behavior when the underlying ContentResolver or DocumentFile
 * returns null, throws exceptions, or otherwise signals failure conditions that
 * can occur with revoked permissions, deleted URIs, or misbehaving providers.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("SAF Provider Edge Cases")
class SafProviderEdgeCasesTest {

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

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(mockContext, testUri) } returns mockRootDocument
        every { mockRootDocument.uri } returns testUri
        // A reachable tree, which is what every test here means to model. `relaxed = true` answers
        // false for exists(), i.e. "this destination is gone" -- fine while nothing asked, but
        // task-69's guard does ask, and a fixture that silently means the opposite of what it says
        // is worse than a broken one.
        every { mockRootDocument.exists() } returns true

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
    @DisplayName("A destination that is no longer available")
    inner class MissingTreeTests {

        /**
         * task-69: `DocumentFile.listFiles()` answers with an EMPTY ARRAY for a tree it cannot
         * reach — an ejected card, a revoked grant — so a listing reported "no blobs" as a fact.
         * Retention runs from a `finally` and opens with `repository.refresh()`, so the FAILED path
         * reads the repository too, and that empty answer replaced the user's committed view: every
         * source showed zero snapshots. On SAF this is the likeliest case of all, because removable
         * storage is the destination that actually gets unplugged.
         */
        @Test
        @DisplayName("listBlobs fails instead of reporting an unreachable tree as empty")
        fun listBlobsFailsWhenTheTreeIsGone(): Unit = runTest {
            every { mockRootDocument.exists() } returns false

            assertThrows<RepositoryUnavailableException> { storage.listBlobs("").toList() }
        }

        /** The ordinary case still lists: present tree, nothing in it. */
        @Test
        @DisplayName("an empty but reachable tree still lists as empty")
        fun listBlobsIsEmptyForAReachableEmptyTree(): Unit = runTest {
            every { mockRootDocument.exists() } returns true
            every { mockRootDocument.listFiles() } returns emptyArray()

            assertThat(storage.listBlobs("").toList()).isEmpty()
        }
    }

    @Nested
    @DisplayName("Null stream handling")
    inner class NullStreamTests {

        @Test
        fun `should handle null from ContentResolver openInputStream`(): Unit = runTest {
            val blobId = BlobId("testblob")

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/testblob.f")
            }

            every { mockRootDocument.findFile("testblob.f") } returns mockFile
            every { mockContentResolver.openInputStream(mockFile.uri) } returns null

            val exception = assertThrows<IOException> {
                storage.getBlob(blobId)
            }

            assertThat(exception.message).contains("Could not open blob")
        }

        @Test
        fun `should handle null from ContentResolver openOutputStream`(): Unit = runTest {
            val blobId = BlobId("writeblob")
            val data = "some data".toByteArray()

            // No existing file
            every { mockRootDocument.findFile("writeblob.f") } returns null

            // Create temp file for atomic write
            val mockTempFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/writeblob.f.tmp.123")
                every { delete() } returns true
            }

            every { mockRootDocument.createFile("application/octet-stream", any()) } returns mockTempFile
            every { mockContentResolver.openOutputStream(mockTempFile.uri) } returns null

            val exception = assertThrows<IOException> {
                storage.putBlob(blobId, data)
            }

            assertThat(exception.message).contains("Could not write")

            // Temp file should be cleaned up on failure
            verify { mockTempFile.delete() }
        }
    }

    @Nested
    @DisplayName("Null listFiles handling")
    inner class NullListFilesTests {

        @Test
        fun `should handle provider returning null from listFiles`(): Unit = runTest {
            // DocumentFile.listFiles() can return an empty array but the underlying
            // provider could return null in degenerate cases. With relaxed mocking,
            // listFiles() on the root document returns a default value.
            // We explicitly mock it to return an empty array (the safe equivalent).
            every { mockRootDocument.listFiles() } returns emptyArray()

            val results = storage.listBlobs("").toList()

            assertThat(results).isEmpty()
        }
    }

    @Nested
    @DisplayName("Rename failure during atomic write")
    inner class RenameFailureTests {

        @Test
        fun `should handle rename failure during atomic write`(): Unit = runTest {
            val blobId = BlobId("renamefail")
            val data = "data to write".toByteArray()

            // No existing file
            every { mockRootDocument.findFile("renamefail.f") } returns null

            val mockTempFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/renamefail.f.tmp.999")
                every { renameTo("renamefail.f") } returns false
                every { delete() } returns true
            }

            every { mockRootDocument.createFile("application/octet-stream", any()) } returns mockTempFile

            val outputStream = ByteArrayOutputStream()
            every { mockContentResolver.openOutputStream(mockTempFile.uri) } returns outputStream

            val exception = assertThrows<IOException> {
                storage.putBlob(blobId, data)
            }

            assertThat(exception.message).contains("Could not rename temp file")

            // Verify the data was written to the stream before rename failed
            assertThat(outputStream.toByteArray()).isEqualTo(data)

            // Verify temp file cleanup
            verify { mockTempFile.delete() }
        }
    }

    @Nested
    @DisplayName("Permission revocation")
    inner class PermissionRevocationTests {

        @Test
        fun `should handle SecurityException from revoked permissions`(): Unit = runTest {
            val blobId = BlobId("secured")

            val mockFile = mockk<DocumentFile> {
                every { uri } returns Uri.parse("$testUri/secured.f")
            }

            every { mockRootDocument.findFile("secured.f") } returns mockFile
            every {
                mockContentResolver.openInputStream(mockFile.uri)
            } throws SecurityException("Permission denied: URI access revoked")

            val exception = assertThrows<SecurityException> {
                storage.getBlob(blobId)
            }

            assertThat(exception.message).contains("Permission denied")
        }
    }

    @Nested
    @DisplayName("Deleted tree URI")
    inner class DeletedTreeUriTests {

        @Test
        fun `should handle FileNotFoundException for deleted tree URI`(): Unit = runTest {
            // When the tree URI has been deleted or is no longer valid,
            // DocumentFile.fromTreeUri returns null. The rootDocument lazy
            // property will throw IllegalArgumentException in that case.
            val deletedUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADeleted")

            val deletedOptions = SafOptions(
                treeUri = deletedUri,
                directoryShards = listOf(1),
                maxNonShardedLength = 20,
            )

            every { DocumentFile.fromTreeUri(mockContext, deletedUri) } returns null

            val deletedStorage = SafBlobStorage.createForTesting(
                context = mockContext,
                treeUri = deletedUri,
                options = deletedOptions,
                shardingParams = shardingParams,
                skipPermissionCheck = true,
            )

            // Any operation that accesses the rootDocument will trigger the lazy init
            // which throws IllegalArgumentException when fromTreeUri returns null
            val exception = assertThrows<IllegalArgumentException> {
                deletedStorage.getBlob(BlobId("anything"))
            }

            assertThat(exception.message).contains("Invalid root URI")
        }
    }
}

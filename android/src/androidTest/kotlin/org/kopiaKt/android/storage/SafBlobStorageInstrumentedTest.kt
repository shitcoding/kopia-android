package org.kopiaKt.android.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.PutBlobOptions
import java.time.Instant
import java.util.UUID

/**
 * Instrumented tests for SafBlobStorage.
 *
 * These tests require a real Android device or emulator with SAF support.
 * They use the app's internal cache directory which doesn't require external permissions.
 *
 * For testing with external storage (SD card), you'll need to:
 * 1. Run the test app manually
 * 2. Grant SAF permission via the picker
 * 3. Store the URI for testing
 *
 * To run these tests:
 * ./gradlew :android:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SafBlobStorageInstrumentedTest {

    private lateinit var context: Context
    private lateinit var testDir: DocumentFile
    private lateinit var storage: SafBlobStorage

    private val testId = UUID.randomUUID().toString().substring(0, 8)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Use app's cache directory for testing (doesn't require SAF permissions)
        val cacheDir = context.cacheDir
        val safTestDir = java.io.File(cacheDir, "saf_test_$testId")
        safTestDir.mkdirs()

        testDir = DocumentFile.fromFile(safTestDir)

        // Create storage using internal directory
        // Note: This is a simplified test setup. Real SAF testing requires
        // a tree URI from ACTION_OPEN_DOCUMENT_TREE
        val options = SafOptions(
            treeUri = testDir.uri,
            directoryShards = listOf(1),
            maxNonShardedLength = 20,
            atomicWrites = true,
            readOnly = false
        )

        val shardingParams = SafShardingParameters(
            default = listOf(1),
            maxNonShardedLength = 20
        )

        // For internal storage, we can create the storage directly
        storage = createStorageForInternalDir(context, testDir.uri, options, shardingParams)
    }

    @After
    fun tearDown() {
        // Clean up test directory
        try {
            testDir.delete()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }

    /**
     * Creates SafBlobStorage for internal directory testing.
     * This bypasses the SAF permission check since we're using internal storage.
     */
    private fun createStorageForInternalDir(
        context: Context,
        uri: Uri,
        options: SafOptions,
        shardingParams: SafShardingParameters
    ): SafBlobStorage {
        return SafBlobStorage.createForTesting(
            context = context,
            treeUri = uri,
            options = options,
            shardingParams = shardingParams,
            skipPermissionCheck = true
        )
    }

    @Test
    fun putAndGetBlob_roundtrip() = runTest {
        val blobId = BlobId("test-blob-1")
        val data = "Hello, SAF!".toByteArray()

        storage.putBlob(blobId, data)
        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun putAndGetBlob_binaryData() = runTest {
        val blobId = BlobId("binary-blob")
        val data = ByteArray(256) { it.toByte() }

        storage.putBlob(blobId, data)
        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun putAndGetBlob_emptyData() = runTest {
        val blobId = BlobId("empty-blob")
        val data = byteArrayOf()

        storage.putBlob(blobId, data)
        val result = storage.getBlob(blobId)

        assertThat(result).isEmpty()
    }

    @Test
    fun putAndGetBlob_largeData() = runTest {
        val blobId = BlobId("large-blob")
        val data = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB

        storage.putBlob(blobId, data)
        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun getBlob_partialRead() = runTest {
        val blobId = BlobId("partial-blob")
        val data = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()

        storage.putBlob(blobId, data)

        // Read with offset
        val result1 = storage.getBlob(blobId, offset = 5)
        assertThat(String(result1)).isEqualTo("FGHIJKLMNOPQRSTUVWXYZ")

        // Read with offset and length
        val result2 = storage.getBlob(blobId, offset = 5, length = 5)
        assertThat(String(result2)).isEqualTo("FGHIJ")

        // Read zero length
        val result3 = storage.getBlob(blobId, offset = 0, length = 0)
        assertThat(result3).isEmpty()
    }

    @Test(expected = BlobNotFoundException::class)
    fun getBlob_notFound() = runTest {
        storage.getBlob(BlobId("nonexistent"))
    }

    @Test(expected = InvalidBlobRangeException::class)
    fun getBlob_invalidOffset() = runTest {
        val blobId = BlobId("range-test")
        storage.putBlob(blobId, "small".toByteArray())

        storage.getBlob(blobId, offset = 1000)
    }

    @Test
    fun getBlobMetadata_existing() = runTest {
        val blobId = BlobId("metadata-blob")
        val data = "test data".toByteArray()
        val beforePut = Instant.now()

        storage.putBlob(blobId, data)

        val metadata = storage.getBlobMetadata(blobId)

        assertThat(metadata).isNotNull()
        assertThat(metadata!!.blobId).isEqualTo(blobId)
        assertThat(metadata.length).isEqualTo(data.size.toLong())
        assertThat(metadata.timestamp).isAtLeast(beforePut.minusSeconds(1))
    }

    @Test
    fun getBlobMetadata_notFound() = runTest {
        val metadata = storage.getBlobMetadata(BlobId("nonexistent"))
        assertThat(metadata).isNull()
    }

    @Test
    fun deleteBlob_existing() = runTest {
        val blobId = BlobId("to-delete")
        storage.putBlob(blobId, "data".toByteArray())

        storage.deleteBlob(blobId)

        val metadata = storage.getBlobMetadata(blobId)
        assertThat(metadata).isNull()
    }

    @Test
    fun deleteBlob_nonexistent() = runTest {
        // Should not throw
        storage.deleteBlob(BlobId("never-existed"))
    }

    @Test
    fun putBlob_dontOverwrite() = runTest {
        val blobId = BlobId("overwrite-test")
        val data1 = "original".toByteArray()
        val data2 = "modified".toByteArray()

        storage.putBlob(blobId, data1)
        storage.putBlob(blobId, data2, PutBlobOptions(dontOverwrite = true))

        // Should still be original
        val result = storage.getBlob(blobId)
        assertThat(result).isEqualTo(data1)
    }

    @Test
    fun putBlob_overwrite() = runTest {
        val blobId = BlobId("overwrite-test2")
        val data1 = "original".toByteArray()
        val data2 = "modified".toByteArray()

        storage.putBlob(blobId, data1)
        storage.putBlob(blobId, data2) // dontOverwrite defaults to false

        // Should be modified
        val result = storage.getBlob(blobId)
        assertThat(result).isEqualTo(data2)
    }

    @Test
    fun listBlobs_all() = runTest {
        // Create some blobs
        storage.putBlob(BlobId("list-a"), "a".toByteArray())
        storage.putBlob(BlobId("list-b"), "b".toByteArray())
        storage.putBlob(BlobId("list-c"), "c".toByteArray())

        val blobs = storage.listBlobs("list-").toList()

        assertThat(blobs).hasSize(3)
        assertThat(blobs.map { it.blobId.value }).containsExactly("list-a", "list-b", "list-c")
    }

    @Test
    fun listBlobs_withPrefix() = runTest {
        storage.putBlob(BlobId("pack-1"), "p1".toByteArray())
        storage.putBlob(BlobId("pack-2"), "p2".toByteArray())
        storage.putBlob(BlobId("index-1"), "i1".toByteArray())

        val packBlobs = storage.listBlobs("pack-").toList()

        assertThat(packBlobs).hasSize(2)
        assertThat(packBlobs.map { it.blobId.value }).containsExactly("pack-1", "pack-2")
    }

    @Test
    fun listBlobs_empty() = runTest {
        val blobs = storage.listBlobs("nonexistent-").toList()
        assertThat(blobs).isEmpty()
    }

    @Test
    fun sharding_longBlobIds() = runTest {
        // Blob ID longer than maxNonShardedLength (20) should be sharded
        val blobId = BlobId("pack-abcdef1234567890abcdef")
        val data = "sharded data".toByteArray()

        storage.putBlob(blobId, data)
        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun sharding_shortBlobIds() = runTest {
        // Blob ID shorter than maxNonShardedLength should not be sharded
        val blobId = BlobId("short")
        val data = "not sharded".toByteArray()

        storage.putBlob(blobId, data)
        val result = storage.getBlob(blobId)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun connectionInfo() {
        val info = storage.connectionInfo()

        assertThat(info.type).isEqualTo("saf")
        assertThat(info.config).containsKey("uri")
        assertThat(info.config).containsKey("shards")
    }

    @Test
    fun displayName() {
        val name = storage.displayName()
        assertThat(name).startsWith("SAF:")
    }

    @Test
    fun isReadOnly_default() {
        assertThat(storage.isReadOnly()).isFalse()
    }
}

/**
 * Integration tests that require a real SAF tree URI.
 *
 * These tests are skipped unless you configure a test URI.
 * To run these tests:
 * 1. Set the SAF_TEST_URI environment variable to a granted tree URI
 * 2. Run: ./gradlew :android:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.saf_uri="your_uri"
 */
@RunWith(AndroidJUnit4::class)
class SafBlobStorageRealSafTest {

    private lateinit var context: Context
    private var storage: SafBlobStorage? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Try to get a real SAF URI from test arguments
        val safUri = getTestSafUri()
        if (safUri != null) {
            try {
                storage = SafBlobStorage.create(
                    context = context,
                    treeUri = safUri,
                    options = SafOptions(treeUri = safUri)
                )
            } catch (_: SecurityException) {
                // Permission not granted
            }
        }
    }

    private fun getTestSafUri(): Uri? {
        // This would typically come from test instrumentation arguments
        // or a shared preference set by a test setup activity
        return null
    }

    @Test
    fun realSafStorage_putAndGet() = runTest {
        assumeTrue("SAF storage not configured", storage != null)

        val blobId = BlobId("real-saf-test-${UUID.randomUUID()}")
        val data = "Real SAF test data".toByteArray()

        try {
            storage!!.putBlob(blobId, data)
            val result = storage!!.getBlob(blobId)
            assertThat(result).isEqualTo(data)
        } finally {
            // Cleanup
            try {
                storage!!.deleteBlob(blobId)
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun realSafStorage_getCapacity() = runTest {
        assumeTrue("SAF storage not configured", storage != null)

        val capacity = storage!!.getCapacity()

        // Capacity might be -1 if not determinable, but shouldn't throw
        assertThat(capacity.sizeBytes).isAtLeast(-1)
        assertThat(capacity.freeBytes).isAtLeast(-1)
    }
}

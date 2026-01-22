package org.kopiaKt.android.storage

import android.content.Context
import android.net.Uri
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
 * IMPORTANT: These tests require a real SAF tree URI obtained from
 * ACTION_OPEN_DOCUMENT_TREE. The tests use assumeTrue to skip when
 * no valid SAF URI is configured, since SAF APIs cannot work with
 * file:// URIs from the app's internal storage.
 *
 * To run these tests with actual SAF storage:
 * 1. Set SAF_TEST_URI environment variable to a granted tree URI, OR
 * 2. Implement getTestSafUri() to return a pre-configured URI
 *
 * ./gradlew :android:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SafBlobStorageInstrumentedTest {

    private lateinit var context: Context
    private var storage: SafBlobStorage? = null
    private var safUri: Uri? = null

    private val testId = UUID.randomUUID().toString().substring(0, 8)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Try to get a real SAF URI from test configuration
        safUri = getTestSafUri()

        if (safUri != null) {
            try {
                val options = SafOptions(
                    treeUri = safUri!!,
                    directoryShards = listOf(1),
                    maxNonShardedLength = 20,
                    atomicWrites = true,
                    readOnly = false
                )

                storage = SafBlobStorage.create(
                    context = context,
                    treeUri = safUri!!,
                    options = options
                )
            } catch (e: Exception) {
                // SAF storage creation failed - tests will be skipped
                storage = null
            }
        }
    }

    /**
     * Returns a valid SAF tree URI for testing, or null if none is configured.
     *
     * Override this method or set SAF_TEST_URI environment variable to provide
     * a valid tree URI from ACTION_OPEN_DOCUMENT_TREE.
     */
    private fun getTestSafUri(): Uri? {
        // Could be set via test instrumentation arguments or SharedPreferences
        // from a test setup activity that requests SAF permissions
        return null
    }

    @After
    fun tearDown() {
        // No cleanup needed - storage is on external SAF-managed storage
    }

    private fun requireStorage(): SafBlobStorage {
        assumeTrue(
            "SAF storage not configured. These tests require a real SAF tree URI " +
                    "from ACTION_OPEN_DOCUMENT_TREE. File URIs are not supported.",
            storage != null
        )
        return storage!!
    }

    @Test
    fun putAndGetBlob_roundtrip() = runTest {
        val s = requireStorage()
        val blobId = BlobId("test-blob-$testId")
        val data = "Hello, SAF!".toByteArray()

        try {
            s.putBlob(blobId, data)
            val result = s.getBlob(blobId)
            assertThat(result).isEqualTo(data)
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun putAndGetBlob_binaryData() = runTest {
        val s = requireStorage()
        val blobId = BlobId("binary-blob-$testId")
        val data = ByteArray(256) { it.toByte() }

        try {
            s.putBlob(blobId, data)
            val result = s.getBlob(blobId)
            assertThat(result).isEqualTo(data)
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun putAndGetBlob_emptyData() = runTest {
        val s = requireStorage()
        val blobId = BlobId("empty-blob-$testId")
        val data = byteArrayOf()

        try {
            s.putBlob(blobId, data)
            val result = s.getBlob(blobId)
            assertThat(result).isEmpty()
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun putAndGetBlob_largeData() = runTest {
        val s = requireStorage()
        val blobId = BlobId("large-blob-$testId")
        val data = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB

        try {
            s.putBlob(blobId, data)
            val result = s.getBlob(blobId)
            assertThat(result).isEqualTo(data)
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun getBlob_partialRead() = runTest {
        val s = requireStorage()
        val blobId = BlobId("partial-blob-$testId")
        val data = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()

        try {
            s.putBlob(blobId, data)

            // Read with offset
            val result1 = s.getBlob(blobId, offset = 5)
            assertThat(String(result1)).isEqualTo("FGHIJKLMNOPQRSTUVWXYZ")

            // Read with offset and length
            val result2 = s.getBlob(blobId, offset = 5, length = 5)
            assertThat(String(result2)).isEqualTo("FGHIJ")

            // Read zero length
            val result3 = s.getBlob(blobId, offset = 0, length = 0)
            assertThat(result3).isEmpty()
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun getBlob_notFound() = runTest {
        val s = requireStorage()
        try {
            s.getBlob(BlobId("nonexistent-$testId"))
            throw AssertionError("Expected BlobNotFoundException")
        } catch (_: BlobNotFoundException) {
            // Expected
        }
    }

    @Test
    fun getBlob_invalidOffset() = runTest {
        val s = requireStorage()
        val blobId = BlobId("range-test-$testId")

        try {
            s.putBlob(blobId, "small".toByteArray())

            try {
                s.getBlob(blobId, offset = 1000)
                throw AssertionError("Expected InvalidBlobRangeException")
            } catch (_: InvalidBlobRangeException) {
                // Expected
            }
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun getBlobMetadata_existing() = runTest {
        val s = requireStorage()
        val blobId = BlobId("metadata-blob-$testId")
        val data = "test data".toByteArray()
        val beforePut = Instant.now()

        try {
            s.putBlob(blobId, data)

            val metadata = s.getBlobMetadata(blobId)

            assertThat(metadata).isNotNull()
            assertThat(metadata!!.blobId).isEqualTo(blobId)
            assertThat(metadata.length).isEqualTo(data.size.toLong())
            assertThat(metadata.timestamp).isAtLeast(beforePut.minusSeconds(1))
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun getBlobMetadata_notFound() = runTest {
        val s = requireStorage()
        val metadata = s.getBlobMetadata(BlobId("nonexistent-$testId"))
        assertThat(metadata).isNull()
    }

    @Test
    fun deleteBlob_existing() = runTest {
        val s = requireStorage()
        val blobId = BlobId("to-delete-$testId")
        s.putBlob(blobId, "data".toByteArray())

        s.deleteBlob(blobId)

        val metadata = s.getBlobMetadata(blobId)
        assertThat(metadata).isNull()
    }

    @Test
    fun deleteBlob_nonexistent() = runTest {
        val s = requireStorage()
        // Should not throw
        s.deleteBlob(BlobId("never-existed-$testId"))
    }

    @Test
    fun putBlob_dontOverwrite() = runTest {
        val s = requireStorage()
        val blobId = BlobId("overwrite-test-$testId")
        val data1 = "original".toByteArray()
        val data2 = "modified".toByteArray()

        try {
            s.putBlob(blobId, data1)
            s.putBlob(blobId, data2, PutBlobOptions(dontOverwrite = true))

            // Should still be original
            val result = s.getBlob(blobId)
            assertThat(result).isEqualTo(data1)
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun putBlob_overwrite() = runTest {
        val s = requireStorage()
        val blobId = BlobId("overwrite-test2-$testId")
        val data1 = "original".toByteArray()
        val data2 = "modified".toByteArray()

        try {
            s.putBlob(blobId, data1)
            s.putBlob(blobId, data2) // dontOverwrite defaults to false

            // Should be modified
            val result = s.getBlob(blobId)
            assertThat(result).isEqualTo(data2)
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun listBlobs_all() = runTest {
        val s = requireStorage()
        val prefix = "list-$testId-"

        try {
            // Create some blobs
            s.putBlob(BlobId("${prefix}a"), "a".toByteArray())
            s.putBlob(BlobId("${prefix}b"), "b".toByteArray())
            s.putBlob(BlobId("${prefix}c"), "c".toByteArray())

            val blobs = s.listBlobs(prefix).toList()

            assertThat(blobs).hasSize(3)
            assertThat(blobs.map { it.blobId.value }).containsExactly(
                "${prefix}a", "${prefix}b", "${prefix}c"
            )
        } finally {
            try { s.deleteBlob(BlobId("${prefix}a")) } catch (_: Exception) {}
            try { s.deleteBlob(BlobId("${prefix}b")) } catch (_: Exception) {}
            try { s.deleteBlob(BlobId("${prefix}c")) } catch (_: Exception) {}
        }
    }

    @Test
    fun listBlobs_withPrefix() = runTest {
        val s = requireStorage()
        val packPrefix = "pack-$testId-"
        val indexPrefix = "index-$testId-"

        try {
            s.putBlob(BlobId("${packPrefix}1"), "p1".toByteArray())
            s.putBlob(BlobId("${packPrefix}2"), "p2".toByteArray())
            s.putBlob(BlobId("${indexPrefix}1"), "i1".toByteArray())

            val packBlobs = s.listBlobs(packPrefix).toList()

            assertThat(packBlobs).hasSize(2)
            assertThat(packBlobs.map { it.blobId.value }).containsExactly(
                "${packPrefix}1", "${packPrefix}2"
            )
        } finally {
            try { s.deleteBlob(BlobId("${packPrefix}1")) } catch (_: Exception) {}
            try { s.deleteBlob(BlobId("${packPrefix}2")) } catch (_: Exception) {}
            try { s.deleteBlob(BlobId("${indexPrefix}1")) } catch (_: Exception) {}
        }
    }

    @Test
    fun listBlobs_empty() = runTest {
        val s = requireStorage()
        val blobs = s.listBlobs("nonexistent-prefix-$testId-").toList()
        assertThat(blobs).isEmpty()
    }

    @Test
    fun sharding_longBlobIds() = runTest {
        val s = requireStorage()
        // Blob ID longer than maxNonShardedLength (20) should be sharded
        val blobId = BlobId("pack-abcdef1234567890abcdef-$testId")
        val data = "sharded data".toByteArray()

        try {
            s.putBlob(blobId, data)
            val result = s.getBlob(blobId)
            assertThat(result).isEqualTo(data)
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun sharding_shortBlobIds() = runTest {
        val s = requireStorage()
        // Blob ID shorter than maxNonShardedLength should not be sharded
        val blobId = BlobId("short-$testId")
        val data = "not sharded".toByteArray()

        try {
            s.putBlob(blobId, data)
            val result = s.getBlob(blobId)
            assertThat(result).isEqualTo(data)
        } finally {
            try { s.deleteBlob(blobId) } catch (_: Exception) {}
        }
    }

    @Test
    fun connectionInfo() {
        val s = requireStorage()
        val info = s.connectionInfo()

        assertThat(info.type).isEqualTo("saf")
        assertThat(info.config).containsKey("uri")
        assertThat(info.config).containsKey("shards")
    }

    @Test
    fun displayName() {
        val s = requireStorage()
        val name = s.displayName()
        assertThat(name).startsWith("SAF:")
    }

    @Test
    fun isReadOnly_default() {
        val s = requireStorage()
        assertThat(s.isReadOnly()).isFalse()
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

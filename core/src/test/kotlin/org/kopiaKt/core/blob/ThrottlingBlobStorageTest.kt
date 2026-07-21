package org.kopiaKt.core.blob

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.system.measureTimeMillis

class ThrottlingBlobStorageTest {

    @Test
    fun `ThrottlingConfig defaults are unlimited`() {
        val config = ThrottlingConfig()

        assertThat(config.downloadLimitBytesPerSecond).isEqualTo(0)
        assertThat(config.uploadLimitBytesPerSecond).isEqualTo(0)
        assertThat(config.maxConcurrentReads).isEqualTo(0)
        assertThat(config.maxConcurrentWrites).isEqualTo(0)
        assertThat(config.isThrottlingEnabled).isFalse()
    }

    @Test
    fun `ThrottlingConfig isThrottlingEnabled detects any limit`() {
        assertThat(ThrottlingConfig(downloadLimitBytesPerSecond = 1000).isThrottlingEnabled).isTrue()
        assertThat(ThrottlingConfig(uploadLimitBytesPerSecond = 1000).isThrottlingEnabled).isTrue()
        assertThat(ThrottlingConfig(maxConcurrentReads = 5).isThrottlingEnabled).isTrue()
        assertThat(ThrottlingConfig(maxConcurrentWrites = 5).isThrottlingEnabled).isTrue()
    }

    @Test
    fun `wrapIfNeeded returns original storage when no throttling`() {
        val delegate = mockk<BlobStorage>()
        val config = ThrottlingConfig.Unlimited

        val result = ThrottlingBlobStorage.wrapIfNeeded(delegate, config)

        assertThat(result).isSameInstanceAs(delegate)
    }

    @Test
    fun `wrapIfNeeded wraps storage when throttling enabled`() {
        val delegate = mockk<BlobStorage>()
        val config = ThrottlingConfig(maxConcurrentReads = 5)

        val result = ThrottlingBlobStorage.wrapIfNeeded(delegate, config)

        assertThat(result).isInstanceOf(ThrottlingBlobStorage::class.java)
    }

    @Test
    fun `getBlob delegates to underlying storage`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val blobId = BlobId("test-blob")
        val expectedData = byteArrayOf(1, 2, 3, 4)

        coEvery { delegate.getBlob(blobId, 0, -1) } returns expectedData

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        val result = throttled.getBlob(blobId, 0, -1)

        assertThat(result).isEqualTo(expectedData)
        coVerify { delegate.getBlob(blobId, 0, -1) }
    }

    @Test
    fun `putBlob delegates to underlying storage`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val blobId = BlobId("test-blob")
        val data = byteArrayOf(1, 2, 3, 4)

        coEvery { delegate.putBlob(blobId, data, any()) } returns Unit

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        throttled.putBlob(blobId, data)

        coVerify { delegate.putBlob(blobId, data, any()) }
    }

    @Test
    fun `getBlobMetadata delegates to underlying storage`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val blobId = BlobId("test-blob")
        val expectedMetadata = BlobMetadata(blobId, 100, Instant.now())

        coEvery { delegate.getBlobMetadata(blobId) } returns expectedMetadata

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        val result = throttled.getBlobMetadata(blobId)

        assertThat(result).isEqualTo(expectedMetadata)
    }

    @Test
    fun `listBlobs delegates to underlying storage`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val metadata1 = BlobMetadata(BlobId("blob1"), 100, Instant.now())
        val metadata2 = BlobMetadata(BlobId("blob2"), 200, Instant.now())

        coEvery { delegate.listBlobs("prefix") } returns flowOf(metadata1, metadata2)

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        val result = throttled.listBlobs("prefix").toList()

        assertThat(result).containsExactly(metadata1, metadata2)
    }

    @Test
    fun `deleteBlob delegates to underlying storage`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val blobId = BlobId("test-blob")

        coEvery { delegate.deleteBlob(blobId) } returns Unit

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        throttled.deleteBlob(blobId)

        coVerify { delegate.deleteBlob(blobId) }
    }

    @Test
    fun `connectionInfo delegates to underlying storage`() {
        val delegate = mockk<BlobStorage>()
        val expectedInfo = ConnectionInfo("test", mapOf("key" to "value"))

        every { delegate.connectionInfo() } returns expectedInfo

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        val result = throttled.connectionInfo()

        assertThat(result).isEqualTo(expectedInfo)
    }

    @Test
    fun `displayName delegates to underlying storage`() {
        val delegate = mockk<BlobStorage>()

        every { delegate.displayName() } returns "Test Storage"

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)

        assertThat(throttled.displayName()).isEqualTo("Test Storage")
    }

    @Test
    fun `isReadOnly delegates to underlying storage`() {
        val delegate = mockk<BlobStorage>()

        every { delegate.isReadOnly() } returns true

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)

        assertThat(throttled.isReadOnly()).isTrue()
    }

    @Test
    fun `close delegates to underlying storage`() = runBlocking {
        val delegate = mockk<BlobStorage>()

        coEvery { delegate.close() } returns Unit

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        throttled.close()

        coVerify { delegate.close() }
    }

    @Test
    fun `flushCaches delegates to underlying storage`() = runBlocking {
        val delegate = mockk<BlobStorage>()

        coEvery { delegate.flushCaches() } returns Unit

        val throttled = ThrottlingBlobStorage(delegate, ThrottlingConfig.Unlimited)
        throttled.flushCaches()

        coVerify { delegate.flushCaches() }
    }

    @Test
    fun `concurrent reads are limited by semaphore`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val blobId = BlobId("test-blob")
        var concurrentCount = 0
        var maxConcurrent = 0

        coEvery { delegate.getBlob(blobId, any(), any()) } coAnswers {
            concurrentCount++
            maxConcurrent = maxOf(maxConcurrent, concurrentCount)
            delay(50)
            concurrentCount--
            byteArrayOf(1)
        }

        val config = ThrottlingConfig(maxConcurrentReads = 2)
        val throttled = ThrottlingBlobStorage(delegate, config)

        // Launch 5 concurrent reads
        val jobs = (1..5).map {
            async { throttled.getBlob(blobId, 0, -1) }
        }
        jobs.awaitAll()

        // Should never exceed 2 concurrent reads
        assertThat(maxConcurrent).isAtMost(2)
    }

    @Test
    fun `concurrent writes are limited by semaphore`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val data = byteArrayOf(1, 2, 3)
        var concurrentCount = 0
        var maxConcurrent = 0

        coEvery { delegate.putBlob(any(), any(), any()) } coAnswers {
            concurrentCount++
            maxConcurrent = maxOf(maxConcurrent, concurrentCount)
            delay(50)
            concurrentCount--
        }

        val config = ThrottlingConfig(maxConcurrentWrites = 2)
        val throttled = ThrottlingBlobStorage(delegate, config)

        // Launch 5 concurrent writes
        val jobs = (1..5).map { i ->
            async { throttled.putBlob(BlobId("blob$i"), data) }
        }
        jobs.awaitAll()

        // Should never exceed 2 concurrent writes
        assertThat(maxConcurrent).isAtMost(2)
    }

    @Test
    fun `statistics track bytes consumed`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val blobId = BlobId("test-blob")
        val data = ByteArray(1000)

        coEvery { delegate.getBlob(blobId, any(), any()) } returns data
        coEvery { delegate.putBlob(any(), any(), any()) } returns Unit

        val config = ThrottlingConfig.Unlimited
        val throttled = ThrottlingBlobStorage(delegate, config)

        // Perform operations
        throttled.getBlob(blobId, 0, -1)
        throttled.putBlob(blobId, data)

        // Statistics should be available (but won't track without throttling buckets)
        val stats = throttled.getStatistics()
        assertThat(stats.totalBytesDownloaded).isEqualTo(0) // No throttling = no tracking
        assertThat(stats.totalBytesUploaded).isEqualTo(0)
    }

    @Test
    fun `statistics track bytes with throttling enabled`() = runBlocking {
        val delegate = mockk<BlobStorage>()
        val blobId = BlobId("test-blob")
        val data = ByteArray(100)

        coEvery { delegate.getBlob(blobId, any(), any()) } returns data
        coEvery { delegate.putBlob(any(), any(), any()) } returns Unit

        // High limits to avoid delays but enable tracking
        val config = ThrottlingConfig(
            downloadLimitBytesPerSecond = 1_000_000_000,
            uploadLimitBytesPerSecond = 1_000_000_000,
        )
        val throttled = ThrottlingBlobStorage(delegate, config)

        // Perform operations
        throttled.getBlob(blobId, 0, -1)
        throttled.putBlob(blobId, data)

        val stats = throttled.getStatistics()
        assertThat(stats.totalBytesDownloaded).isEqualTo(100)
        assertThat(stats.totalBytesUploaded).isEqualTo(100)
    }

    @Test
    fun `ThrottlingStatistics data class works`() {
        val stats = ThrottlingStatistics(
            totalBytesDownloaded = 1000,
            totalBytesUploaded = 2000,
            downloadDelayMillis = 100,
            uploadDelayMillis = 200,
        )

        assertThat(stats.totalBytesDownloaded).isEqualTo(1000)
        assertThat(stats.totalBytesUploaded).isEqualTo(2000)
        assertThat(stats.downloadDelayMillis).isEqualTo(100)
        assertThat(stats.uploadDelayMillis).isEqualTo(200)
    }
}

class TokenBucketTest {

    @Test
    fun `consume zero bytes returns immediately`() = runBlocking {
        val bucket = TokenBucket(1000)
        val time = measureTimeMillis {
            bucket.consume(0)
        }
        assertThat(time).isLessThan(50)
    }

    @Test
    fun `consume within bucket capacity returns immediately`() = runBlocking {
        val bucket = TokenBucket(10000) // 10KB/s, starts with 10KB
        val time = measureTimeMillis {
            bucket.consume(5000) // 5KB, within capacity
        }
        assertThat(time).isLessThan(50)
    }

    @Test
    fun `totalBytesConsumed tracks usage`() = runBlocking {
        val bucket = TokenBucket(100000)

        bucket.consume(100)
        bucket.consume(200)
        bucket.consume(300)

        assertThat(bucket.totalBytesConsumed).isEqualTo(600)
    }

    @Test
    fun `multiple fast consumes are allowed when bucket has tokens`() = runBlocking {
        val bucket = TokenBucket(100000) // 100KB/s

        val time = measureTimeMillis {
            repeat(10) {
                bucket.consume(1000)
            }
        }

        // Should complete quickly with such high rate
        assertThat(time).isLessThan(200)
    }
}

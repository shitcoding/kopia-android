package org.kopiaKt.core.blob

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * Configuration for bandwidth throttling.
 */
data class ThrottlingConfig(
    /**
     * Maximum download speed in bytes per second.
     * 0 means unlimited.
     */
    val downloadLimitBytesPerSecond: Long = 0,

    /**
     * Maximum upload speed in bytes per second.
     * 0 means unlimited.
     */
    val uploadLimitBytesPerSecond: Long = 0,

    /**
     * Maximum concurrent read operations.
     * 0 means unlimited.
     */
    val maxConcurrentReads: Int = 0,

    /**
     * Maximum concurrent write operations.
     * 0 means unlimited.
     */
    val maxConcurrentWrites: Int = 0
) {
    /**
     * Whether any throttling is configured.
     */
    val isThrottlingEnabled: Boolean
        get() = downloadLimitBytesPerSecond > 0 ||
            uploadLimitBytesPerSecond > 0 ||
            maxConcurrentReads > 0 ||
            maxConcurrentWrites > 0

    companion object {
        /** No throttling configuration */
        val Unlimited = ThrottlingConfig()
    }
}

/**
 * BlobStorage wrapper that implements bandwidth throttling and concurrency limits.
 *
 * This wrapper uses a token bucket algorithm for bandwidth limiting and
 * semaphores for concurrency control.
 *
 * @param delegate The underlying BlobStorage to wrap
 * @param config Throttling configuration
 */
class ThrottlingBlobStorage(
    private val delegate: BlobStorage,
    private val config: ThrottlingConfig
) : BlobStorage {

    private val downloadBucket = if (config.downloadLimitBytesPerSecond > 0) {
        TokenBucket(config.downloadLimitBytesPerSecond)
    } else null

    private val uploadBucket = if (config.uploadLimitBytesPerSecond > 0) {
        TokenBucket(config.uploadLimitBytesPerSecond)
    } else null

    private val readSemaphore = if (config.maxConcurrentReads > 0) {
        Semaphore(config.maxConcurrentReads)
    } else null

    private val writeSemaphore = if (config.maxConcurrentWrites > 0) {
        Semaphore(config.maxConcurrentWrites)
    } else null

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray {
        return withReadLimit {
            val data = delegate.getBlob(blobId, offset, length)
            downloadBucket?.consume(data.size.toLong())
            data
        }
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? {
        return withReadLimit {
            delegate.getBlobMetadata(blobId)
        }
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> {
        // List operations don't count toward bandwidth limits
        return delegate.listBlobs(prefix)
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
        withWriteLimit {
            uploadBucket?.consume(data.size.toLong())
            delegate.putBlob(blobId, data, options)
        }
    }

    override suspend fun deleteBlob(blobId: BlobId) {
        withWriteLimit {
            delegate.deleteBlob(blobId)
        }
    }

    override suspend fun extendBlobRetention(blobId: BlobId, options: ExtendBlobRetentionOptions) {
        withWriteLimit {
            delegate.extendBlobRetention(blobId, options)
        }
    }

    override fun connectionInfo(): ConnectionInfo = delegate.connectionInfo()

    override fun displayName(): String = delegate.displayName()

    override fun isReadOnly(): Boolean = delegate.isReadOnly()

    override suspend fun close() {
        delegate.close()
    }

    override suspend fun flushCaches() {
        delegate.flushCaches()
    }

    private suspend inline fun <T> withReadLimit(block: () -> T): T {
        return if (readSemaphore != null) {
            readSemaphore.acquire()
            try {
                block()
            } finally {
                readSemaphore.release()
            }
        } else {
            block()
        }
    }

    private suspend inline fun <T> withWriteLimit(block: () -> T): T {
        return if (writeSemaphore != null) {
            writeSemaphore.acquire()
            try {
                block()
            } finally {
                writeSemaphore.release()
            }
        } else {
            block()
        }
    }

    /**
     * Gets current throttling statistics.
     */
    fun getStatistics(): ThrottlingStatistics {
        return ThrottlingStatistics(
            totalBytesDownloaded = downloadBucket?.totalBytesConsumed ?: 0,
            totalBytesUploaded = uploadBucket?.totalBytesConsumed ?: 0,
            downloadDelayMillis = downloadBucket?.totalDelayMillis ?: 0,
            uploadDelayMillis = uploadBucket?.totalDelayMillis ?: 0
        )
    }

    companion object {
        /**
         * Wraps a BlobStorage with throttling if the config has any limits.
         * Returns the original storage if no throttling is configured.
         */
        fun wrapIfNeeded(storage: BlobStorage, config: ThrottlingConfig): BlobStorage {
            return if (config.isThrottlingEnabled) {
                ThrottlingBlobStorage(storage, config)
            } else {
                storage
            }
        }
    }
}

/**
 * Statistics about throttling behavior.
 */
data class ThrottlingStatistics(
    /** Total bytes that have been downloaded through the throttled storage */
    val totalBytesDownloaded: Long,
    /** Total bytes that have been uploaded through the throttled storage */
    val totalBytesUploaded: Long,
    /** Total time spent waiting due to download throttling (milliseconds) */
    val downloadDelayMillis: Long,
    /** Total time spent waiting due to upload throttling (milliseconds) */
    val uploadDelayMillis: Long
)

/**
 * Token bucket rate limiter for bandwidth control.
 *
 * Implements a simple token bucket algorithm where:
 * - Tokens are added at a rate of `bytesPerSecond` per second
 * - Maximum bucket size is 1 second worth of tokens (allows bursts)
 * - Consuming more than available tokens results in waiting
 */
internal class TokenBucket(
    private val bytesPerSecond: Long
) {
    private val tokens = AtomicLong(bytesPerSecond) // Start with 1 second of burst
    private var lastRefillTime = System.nanoTime()
    private val lock = Any()

    private val _totalBytesConsumed = AtomicLong(0)
    private val _totalDelayMillis = AtomicLong(0)

    val totalBytesConsumed: Long get() = _totalBytesConsumed.get()
    val totalDelayMillis: Long get() = _totalDelayMillis.get()

    /**
     * Consumes the specified number of bytes, waiting if necessary.
     *
     * @param bytes Number of bytes to consume
     */
    suspend fun consume(bytes: Long) {
        if (bytes <= 0) return

        _totalBytesConsumed.addAndGet(bytes)

        while (true) {
            refill()

            val available = tokens.get()
            val toConsume = bytes.coerceAtMost(available)

            if (tokens.compareAndSet(available, available - toConsume)) {
                val remaining = bytes - toConsume
                if (remaining <= 0) {
                    return
                }

                // Need to wait for more tokens
                val waitTimeMs = (remaining * 1000) / bytesPerSecond
                if (waitTimeMs > 0) {
                    _totalDelayMillis.addAndGet(waitTimeMs)
                    delay(waitTimeMs)
                }

                // Continue to consume remaining bytes
                consume(remaining)
                return
            }
            // CAS failed, retry
        }
    }

    private fun refill() {
        synchronized(lock) {
            val now = System.nanoTime()
            val elapsedNanos = now - lastRefillTime

            if (elapsedNanos > 0) {
                val tokensToAdd = (elapsedNanos * bytesPerSecond) / 1_000_000_000L
                if (tokensToAdd > 0) {
                    val currentTokens = tokens.get()
                    val newTokens = (currentTokens + tokensToAdd).coerceAtMost(bytesPerSecond)
                    tokens.set(newTokens)
                    lastRefillTime = now
                }
            }
        }
    }
}

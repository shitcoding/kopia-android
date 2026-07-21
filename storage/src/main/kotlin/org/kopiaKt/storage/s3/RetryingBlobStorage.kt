package org.kopiaKt.storage.s3

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retry
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.ExtendBlobRetentionOptions
import org.kopiaKt.core.blob.HostKeyNotTrustedException
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.PutBlobOptions
import software.amazon.awssdk.services.s3.model.S3Exception
import kotlin.math.min
import kotlin.random.Random

/**
 * A wrapper around BlobStorage that adds retry logic with exponential backoff.
 *
 * This is compatible with Go Kopia's retrying.Wrapper functionality.
 *
 * @param delegate The underlying blob storage to wrap
 * @param maxRetries Maximum number of retry attempts (default: 10)
 * @param initialDelayMs Initial delay between retries in milliseconds (default: 100)
 * @param maxDelayMs Maximum delay between retries in milliseconds (default: 30000)
 * @param jitterFactor Random jitter factor (0.0 to 1.0) to add to delay (default: 0.2)
 */
class RetryingBlobStorage(
    private val delegate: BlobStorage,
    private val maxRetries: Int = 10,
    private val initialDelayMs: Long = 100,
    private val maxDelayMs: Long = 30_000,
    private val jitterFactor: Double = 0.2,
) : BlobStorage {

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray = retryOperation("getBlob", blobId) {
        delegate.getBlob(blobId, offset, length)
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? = retryOperation("getBlobMetadata", blobId) {
        delegate.getBlobMetadata(blobId)
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> {
        // For flows, we retry the entire operation if it fails during iteration
        return delegate.listBlobs(prefix).retry(maxRetries.toLong()) { cause ->
            if (isRetryable(cause)) {
                val delayMs = calculateDelay(0) // Use initial delay for flow retries
                delay(delayMs)
                true
            } else {
                false
            }
        }
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
        retryOperation("putBlob", blobId) {
            delegate.putBlob(blobId, data, options)
        }
    }

    override suspend fun deleteBlob(blobId: BlobId) {
        retryOperation("deleteBlob", blobId) {
            delegate.deleteBlob(blobId)
        }
    }

    override suspend fun extendBlobRetention(blobId: BlobId, options: ExtendBlobRetentionOptions) {
        retryOperation("extendBlobRetention", blobId) {
            delegate.extendBlobRetention(blobId, options)
        }
    }

    override fun connectionInfo(): ConnectionInfo = delegate.connectionInfo()

    override fun displayName(): String = delegate.displayName()

    override fun isReadOnly(): Boolean = delegate.isReadOnly()

    override suspend fun flushCaches() {
        retryOperation("flushCaches", null) {
            delegate.flushCaches()
        }
    }

    override suspend fun close() {
        delegate.close()
    }

    /**
     * Executes an operation with retry logic.
     */
    private suspend fun <T> retryOperation(
        operationName: String,
        blobId: BlobId?,
        operation: suspend () -> T,
    ): T {
        var lastException: Throwable? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            try {
                return operation()
            } catch (e: Throwable) {
                lastException = e

                // Don't retry non-retryable errors
                if (!isRetryable(e)) {
                    throw e
                }

                // Don't retry if we've exhausted retries
                if (attempt >= maxRetries) {
                    throw e
                }

                // Calculate delay with exponential backoff and jitter
                val delayMs = calculateDelay(attempt)
                delay(delayMs)

                attempt++
            }
        }

        // Should never reach here, but just in case
        throw lastException ?: IllegalStateException("Retry loop completed without result")
    }

    /**
     * Calculates the delay for the given attempt number using exponential backoff.
     */
    private fun calculateDelay(attempt: Int): Long {
        // Exponential backoff: initialDelay * 2^attempt
        val baseDelay = initialDelayMs * (1L shl attempt)

        // Cap at max delay
        val cappedDelay = min(baseDelay, maxDelayMs)

        // Add random jitter
        val jitter = (cappedDelay * jitterFactor * Random.nextDouble()).toLong()

        return cappedDelay + jitter
    }

    /**
     * Determines if an exception is retryable.
     */
    private fun isRetryable(e: Throwable): Boolean {
        // Never retry these errors
        if (e is BlobNotFoundException) return false
        if (e is InvalidCredentialsException) return false
        if (e is HostKeyNotTrustedException) return false
        if (e is IllegalArgumentException) return false
        if (e is UnsupportedOperationException) return false

        // Check S3-specific errors
        if (e is S3Exception) {
            return when (e.statusCode()) {
                // Client errors that shouldn't be retried
                400, // Bad Request
                401, // Unauthorized
                403, // Forbidden
                404, // Not Found
                405, // Method Not Allowed
                409,
                -> false // Conflict

                // Server errors that should be retried
                500, // Internal Server Error
                502, // Bad Gateway
                503, // Service Unavailable
                504,
                -> true // Gateway Timeout

                // Retry other errors (429 Too Many Requests, etc.)
                else -> e.statusCode() >= 500 || e.statusCode() == 429
            }
        }

        // Retry network-related exceptions
        if (e is java.io.IOException) return true
        if (e is java.net.SocketException) return true
        if (e is java.net.SocketTimeoutException) return true
        if (e is java.net.ConnectException) return true
        if (e is java.net.UnknownHostException) return true

        // Check for timeout-related errors in the message
        val message = e.message?.lowercase() ?: ""
        if (message.contains("timeout") || message.contains("timed out")) return true
        if (message.contains("connection reset")) return true
        if (message.contains("connection refused")) return false // Server not running

        // Don't retry unknown errors by default
        return false
    }

    companion object {
        /**
         * Wraps a BlobStorage with retry logic.
         */
        fun wrap(
            storage: BlobStorage,
            maxRetries: Int = 10,
            initialDelayMs: Long = 100,
            maxDelayMs: Long = 30_000,
        ): RetryingBlobStorage = RetryingBlobStorage(storage, maxRetries, initialDelayMs, maxDelayMs)
    }
}

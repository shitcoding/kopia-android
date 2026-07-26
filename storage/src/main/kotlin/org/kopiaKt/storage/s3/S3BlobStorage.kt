package org.kopiaKt.storage.s3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.ExtendBlobRetentionOptions
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.RetentionMode
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import org.kopiaKt.storage.tls.TlsTrust
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.ObjectLockRetention
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * S3-based blob storage implementation.
 *
 * This implementation is compatible with Go Kopia's S3 storage backend,
 * allowing cross-compatibility between Go and Kotlin implementations.
 *
 * Features:
 * - Support for AWS S3 and S3-compatible services (MinIO, etc.)
 * - Synchronous operations via UrlConnectionHttpClient for Android compatibility
 * - Object retention/locking support
 * - Storage class configuration per blob prefix
 * - Retries with exponential backoff (when wrapped in [RetryingBlobStorage])
 */
class S3BlobStorage private constructor(
    private val client: S3Client,
    private val options: S3Options,
    private val storageConfig: S3StorageConfig,
    private val readOnly: Boolean,
) : BlobStorage {

    companion object {
        private const val S3_STORAGE_TYPE = "s3"
        private const val CONTENT_TYPE = "application/x-kopia"
        private const val CONFIG_NAME = ".storageconfig"
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** S3 error codes that mean a non-retryable auth failure (bad creds or denied permission). */
        private val CREDENTIAL_ERROR_CODES = setOf(
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "AccessDenied",
            "ExpiredToken",
            "InvalidToken",
            "TokenRefreshRequired",
        )

        /**
         * Creates a new S3 blob storage instance.
         *
         * @param options S3 connection options
         * @param readOnly If true, the storage will be in read-only mode
         * @return A new S3BlobStorage instance
         */
        suspend fun create(options: S3Options, readOnly: Boolean = false): S3BlobStorage {
            requireSupportedOptions(options)
            val client = createClient(options)
            val storageConfig = loadStorageConfig(client, options)
            return S3BlobStorage(client, options, storageConfig, readOnly)
        }

        /**
         * Creates a new S3 blob storage with a custom client (for testing).
         */
        internal fun createWithClient(
            client: S3Client,
            options: S3Options,
            storageConfig: S3StorageConfig = S3StorageConfig(),
            readOnly: Boolean = false,
        ): S3BlobStorage {
            requireSupportedOptions(options)
            return S3BlobStorage(client, options, storageConfig, readOnly)
        }

        /**
         * Rejects options this backend would otherwise silently ignore. Failing closed is safer than
         * pretending they took effect: a caller that sets one of these would believe the connection is
         * hardened/configured when it is not.
         *
         * `doNotVerifyTls` stays rejected **by design**, not as a gap: `rootCa` (below) and the WebDAV
         * certificate pin cover every legitimate self-signed/private-CA setup, whereas a reachable
         * trust-anything switch is an unbounded MITM downgrade. Being stricter than Go here is
         * deliberate. `rootCa` IS now supported.
         */
        private fun requireSupportedOptions(options: S3Options) {
            require(!options.doNotVerifyTls) {
                "doNotVerifyTls is not supported by the S3 backend (TLS verification cannot be disabled); " +
                    "use rootCa to trust a private CA instead"
            }
            require(options.roleArn.isEmpty()) {
                "AssumeRole (roleArn) is not supported by the S3 backend"
            }
            // Parse eagerly so a malformed CA fails at connect with a clear message instead of as an
            // opaque TLS error on the first request (mirrors the WebDAV fingerprint check).
            options.rootCa?.let {
                TlsTrust.trustManagerForRootCa(it)
                // Fail closed instead of silently ignoring the CA: over http there is no TLS
                // handshake to validate, so the user would believe the connection is protected.
                require(!usesCleartextEndpoint(options)) {
                    "rootCa requires an https endpoint (a custom CA has no effect over cleartext http)"
                }
            }
        }

        /**
         * True when this configuration will contact the endpoint over plaintext http — either an
         * explicit `http://` endpoint or the `doNotUseTls` flag, which selects http for a scheme-less
         * endpoint. A scheme-less endpoint without that flag resolves to https.
         */
        private fun usesCleartextEndpoint(options: S3Options): Boolean {
            if (options.doNotUseTls) return true
            // Match the "http:" scheme prefix, not just "http://", so this agrees with the app's
            // connect-layer gate and the UI helper rather than having two definitions of cleartext.
            return options.endpoint.trim().startsWith("http:", ignoreCase = true)
        }

        private fun createClient(options: S3Options): S3Client {
            val builder = S3Client.builder()

            // Configure credentials
            val credentialsProvider = createCredentialsProvider(options)
            builder.credentialsProvider(credentialsProvider)

            // Configure region
            if (options.region.isNotEmpty()) {
                builder.region(Region.of(options.region))
            } else {
                // Default to us-east-1 if no region specified
                builder.region(Region.US_EAST_1)
            }

            // Configure endpoint for S3-compatible services
            if (options.endpoint.isNotEmpty()) {
                val scheme = if (options.doNotUseTls) "http" else "https"
                // Case-insensitive, and matching the bare "http:"/"https:" scheme prefix, so this agrees
                // with the cleartext predicates elsewhere. A case-sensitive "//"-strict check would
                // prefix an already-schemed endpoint ("HTTP://host") into "https://HTTP://host" and fail
                // with a baffling URI error instead of connecting or reporting the real problem.
                val endpoint = if (options.endpoint.startsWith("http:", ignoreCase = true) ||
                    options.endpoint.startsWith("https:", ignoreCase = true)
                ) {
                    options.endpoint
                } else {
                    "$scheme://${options.endpoint}"
                }
                builder.endpointOverride(URI.create(endpoint))

                // Enable path-style access for S3-compatible services
                builder.serviceConfiguration(
                    S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build(),
                )
            }

            // Configure HTTP client (UrlConnection for Android compatibility)
            val httpClientBuilder = UrlConnectionHttpClient.builder()
                .socketTimeout(Duration.ofMinutes(5))
                .connectionTimeout(Duration.ofSeconds(30))

            // A custom root CA lets a self-hosted S3 (MinIO behind a private CA) be reached over https
            // instead of forcing the user onto cleartext. Validation stays ON — only the trust anchor
            // changes, and it replaces the system store rather than extending it.
            options.rootCa?.let { pem ->
                val trustManager = TlsTrust.trustManagerForRootCa(pem)
                httpClientBuilder.tlsTrustManagersProvider { arrayOf(trustManager) }
            }

            builder.httpClient(httpClientBuilder.build())

            return builder.build()
        }

        private fun createCredentialsProvider(options: S3Options): AwsCredentialsProvider = when {
            options.accessKeyId.isNotEmpty() && options.secretAccessKey.isNotEmpty() -> {
                if (options.sessionToken.isNotEmpty()) {
                    StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(
                            options.accessKeyId,
                            options.secretAccessKey,
                            options.sessionToken,
                        ),
                    )
                } else {
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            options.accessKeyId,
                            options.secretAccessKey,
                        ),
                    )
                }
            }
            else -> {
                // Use default credential chain (env vars, instance profile, etc.)
                DefaultCredentialsProvider.create()
            }
        }

        internal suspend fun loadStorageConfig(
            client: S3Client,
            options: S3Options,
        ): S3StorageConfig = withContext(Dispatchers.IO) {
            try {
                val request = GetObjectRequest.builder()
                    .bucket(options.bucketName)
                    .key(getObjectKey(options.prefix, CONFIG_NAME))
                    .build()

                val bytes = client.getObject(request, ResponseTransformer.toBytes())
                val json = bytes.asUtf8String()
                Json.decodeFromString<S3StorageConfig>(json)
            } catch (e: NoSuchKeyException) {
                // No storage config yet (e.g. a fresh bucket) — use defaults.
                S3StorageConfig()
            } catch (e: SerializationException) {
                // The config blob exists but isn't parseable (corrupt or a newer format). It only
                // holds non-critical sharding/storage-class hints, so fall back to defaults rather
                // than failing the whole connect.
                S3StorageConfig()
            }
            // Any other failure (bad credentials, access denied, missing bucket, connectivity) is
            // deliberately NOT caught: swallowing it here used to return defaults and make create()
            // falsely "succeed" with unusable credentials. Let it propagate so the connect fails loudly.
        }

        private fun getObjectKey(prefix: String, blobId: String): String = prefix + blobId
    }

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = GetObjectRequest.builder()
                .bucket(options.bucketName)
                .key(getObjectKey(blobId))

            // Set range header for partial reads
            if (offset > 0 || length >= 0) {
                val rangeHeader = when {
                    length == 0L -> {
                        // Zero-length read - return empty array immediately
                        return@withContext ByteArray(0)
                    }
                    length > 0 -> "bytes=$offset-${offset + length - 1}"
                    else -> "bytes=$offset-"
                }
                requestBuilder.range(rangeHeader)
            }

            val response = client.getObject(
                requestBuilder.build(),
                ResponseTransformer.toBytes(),
            )

            response.asByteArray()
        } catch (e: NoSuchKeyException) {
            throw BlobNotFoundException(blobId)
        } catch (e: S3Exception) {
            handleS3Exception(e, blobId)
        }
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? = withContext(Dispatchers.IO) {
        try {
            val request = HeadObjectRequest.builder()
                .bucket(options.bucketName)
                .key(getObjectKey(blobId))
                .build()

            val response = client.headObject(request)

            BlobMetadata(
                blobId = blobId,
                length = response.contentLength(),
                timestamp = response.lastModified(),
            )
        } catch (e: NoSuchKeyException) {
            null
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                null
            } else {
                handleS3Exception(e, blobId)
            }
        }
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        var continuationToken: String? = null

        do {
            val requestBuilder = ListObjectsV2Request.builder()
                .bucket(options.bucketName)
                // Build the S3 key prefix directly (options.prefix + the blob-id prefix). Must NOT wrap
                // in BlobId(prefix): a list-all uses an empty prefix, and BlobId rejects an empty value.
                .prefix(options.prefix + prefix)

            if (continuationToken != null) {
                requestBuilder.continuationToken(continuationToken)
            }

            val response = client.listObjectsV2(requestBuilder.build())

            for (obj in response.contents()) {
                val key = obj.key()
                val blobIdStr = key.removePrefix(options.prefix)
                // Skip the storage config file (its stripped id is exactly CONFIG_NAME — an
                // endsWith() check would also drop a legitimate blob whose id happens to end in
                // ".storageconfig") and any directory-marker / prefix-equal key whose stripped id
                // would be empty (BlobId rejects an empty value).
                if (blobIdStr == CONFIG_NAME || blobIdStr.isEmpty()) {
                    continue
                }
                emit(
                    BlobMetadata(
                        blobId = BlobId(blobIdStr),
                        length = obj.size(),
                        timestamp = obj.lastModified(),
                    ),
                )
            }

            continuationToken = if (response.isTruncated) {
                response.nextContinuationToken()
            } else {
                null
            }
        } while (continuationToken != null)
    }.flowOn(Dispatchers.IO)

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) = withContext(Dispatchers.IO) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
        // S3 doesn't support setModTime
        if (options.setModTime != null) {
            throw UnsupportedPutOptionException("setModTime")
        }

        // Honor dontOverwrite with a HEAD-then-PUT existence check. This is technically racy, but
        // deliberately not upgraded to a conditional If-None-Match:* PUT: a same-blob-id write is the
        // same writer retrying (identical bytes, so a lost race is harmless), no production path sets
        // dontOverwrite, and If-None-Match:* is a recent S3 feature that older S3-compatible servers
        // reject. (Go's S3 backend does not support DoNotRecreate at all.)
        if (options.dontOverwrite) {
            val exists = getBlobMetadata(blobId) != null
            if (exists) {
                return@withContext
            }
        }

        val storageClass = getStorageClassForBlobId(blobId)

        val requestBuilder = PutObjectRequest.builder()
            .bucket(this@S3BlobStorage.options.bucketName)
            .key(getObjectKey(blobId))
            .contentType(CONTENT_TYPE)
            .contentLength(data.size.toLong())

        if (storageClass.isNotEmpty()) {
            requestBuilder.storageClass(storageClass)
        }

        // Configure retention if specified
        if (options.retentionPeriod != Duration.ZERO) {
            val retentionMode = when (options.retentionMode) {
                RetentionMode.GOVERNANCE -> ObjectLockMode.GOVERNANCE
                RetentionMode.COMPLIANCE -> ObjectLockMode.COMPLIANCE
                else -> null
            }

            if (retentionMode != null) {
                requestBuilder.objectLockMode(retentionMode)
                requestBuilder.objectLockRetainUntilDate(
                    Instant.now().plus(options.retentionPeriod),
                )
            }
        }

        try {
            client.putObject(
                requestBuilder.build(),
                RequestBody.fromBytes(data),
            )
        } catch (e: S3Exception) {
            handleS3Exception(e, blobId)
        }
    }

    override suspend fun deleteBlob(blobId: BlobId) = withContext(Dispatchers.IO) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
        try {
            val request = DeleteObjectRequest.builder()
                .bucket(options.bucketName)
                .key(getObjectKey(blobId))
                .build()

            client.deleteObject(request)
        } catch (e: NoSuchKeyException) {
            // Idempotent delete: a missing blob is not an error. (S3 DeleteObject does not actually
            // throw this, but keep the guard so the contract holds if a backend/mocked client does.)
        } catch (e: S3Exception) {
            if (e.statusCode() != 404) {
                handleS3Exception(e, blobId)
            }
        }
        Unit
    }

    override suspend fun extendBlobRetention(blobId: BlobId, options: ExtendBlobRetentionOptions) {
        withContext(Dispatchers.IO) {
            if (readOnly) {
                throw IllegalStateException("Storage is read-only")
            }
            val retentionMode = when (options.retentionMode) {
                RetentionMode.GOVERNANCE -> ObjectLockRetentionMode.GOVERNANCE
                RetentionMode.COMPLIANCE -> ObjectLockRetentionMode.COMPLIANCE
                else -> throw IllegalArgumentException("Invalid retention mode: ${options.retentionMode}")
            }

            val retainUntilDate = Instant.now().plus(options.retentionPeriod)

            val request = PutObjectRetentionRequest.builder()
                .bucket(this@S3BlobStorage.options.bucketName)
                .key(getObjectKey(blobId))
                .retention(
                    ObjectLockRetention.builder()
                        .mode(retentionMode)
                        .retainUntilDate(retainUntilDate)
                        .build(),
                )
                .build()

            try {
                client.putObjectRetention(request)
            } catch (e: S3Exception) {
                handleS3Exception(e, blobId)
            }
        }
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = S3_STORAGE_TYPE,
        config = buildMap {
            put("bucket", options.bucketName)
            if (options.prefix.isNotEmpty()) put("prefix", options.prefix)
            if (options.endpoint.isNotEmpty()) put("endpoint", options.endpoint)
            if (options.region.isNotEmpty()) put("region", options.region)
        },
    )

    override fun displayName(): String {
        val endpoint = options.endpoint.ifEmpty { "aws" }
        return "S3: $endpoint ${options.bucketName}"
    }

    override fun isReadOnly(): Boolean = readOnly

    override suspend fun close() {
        client.close()
    }

    override suspend fun flushCaches() {
        // S3 operations are already persisted immediately
    }

    private fun getObjectKey(blobId: BlobId): String = options.prefix + blobId.value

    private fun getStorageClassForBlobId(blobId: BlobId): String {
        val id = blobId.value
        for (option in storageConfig.blobOptions) {
            if (id.startsWith(option.prefix)) {
                return option.storageClass
            }
        }
        return ""
    }

    private fun handleS3Exception(e: S3Exception, blobId: BlobId): Nothing {
        // Classify by the S3 error CODE, not fragile message-substring matching. NB: AccessDenied may be
        // a bucket-policy/permission error rather than bad credentials, but both are non-retryable auth
        // failures, so mapping to InvalidCredentialsException (carrying the original message) is correct
        // for control flow; the message conveys the real cause.
        val errorCode = e.awsErrorDetails()?.errorCode().orEmpty()

        if (errorCode in CREDENTIAL_ERROR_CODES) {
            throw InvalidCredentialsException(e.message ?: errorCode)
        }

        // Check for not found
        if (e.statusCode() == 404 || errorCode == "NoSuchKey" || errorCode == "NoSuchBucket") {
            throw BlobNotFoundException(blobId)
        }

        // Requested range not satisfiable — map to the same typed error the SFTP/WebDAV backends use
        // so an out-of-range read surfaces consistently instead of leaking a raw S3Exception.
        if (e.statusCode() == HTTP_RANGE_NOT_SATISFIABLE) {
            throw InvalidBlobRangeException("Requested range not satisfiable for blob ${blobId.value}")
        }

        throw e
    }
}

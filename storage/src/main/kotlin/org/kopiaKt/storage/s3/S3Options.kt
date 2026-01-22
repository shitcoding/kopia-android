package org.kopiaKt.storage.s3

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Options for S3-based blob storage.
 *
 * Compatible with Go Kopia S3 storage options for cross-compatibility.
 */
@Serializable
data class S3Options(
    /**
     * Name of the S3 bucket where data is stored.
     */
    @SerialName("bucket")
    val bucketName: String,

    /**
     * Prefix to prepend to all object keys.
     */
    @SerialName("prefix")
    val prefix: String = "",

    /**
     * S3-compatible endpoint URL.
     * For AWS, leave empty to use the default region endpoint.
     * For MinIO or other S3-compatible services, specify the full URL.
     */
    @SerialName("endpoint")
    val endpoint: String = "",

    /**
     * AWS region (e.g., "us-east-1", "eu-west-1").
     */
    @SerialName("region")
    val region: String = "",

    /**
     * AWS access key ID for authentication.
     */
    @SerialName("accessKeyID")
    val accessKeyId: String = "",

    /**
     * AWS secret access key for authentication.
     */
    @SerialName("secretAccessKey")
    val secretAccessKey: String = "",

    /**
     * AWS session token for temporary credentials.
     */
    @SerialName("sessionToken")
    val sessionToken: String = "",

    /**
     * If true, use HTTP instead of HTTPS.
     * Only for testing with local S3-compatible services.
     */
    @SerialName("doNotUseTLS")
    val doNotUseTls: Boolean = false,

    /**
     * If true, skip TLS certificate verification.
     * Use with caution, only for testing.
     */
    @SerialName("doNotVerifyTLS")
    val doNotVerifyTls: Boolean = false,

    /**
     * Custom root CA certificate in PEM format for TLS verification.
     */
    @SerialName("rootCA")
    val rootCa: ByteArray? = null,

    // AssumeRole options
    /**
     * ARN of the IAM role to assume.
     */
    @SerialName("roleARN")
    val roleArn: String = "",

    /**
     * Session name for the assumed role.
     */
    @SerialName("sessionName")
    val sessionName: String = "",

    /**
     * Duration of the assumed role session in seconds.
     */
    @SerialName("duration")
    val roleDurationSeconds: Long = 0,

    /**
     * Custom STS endpoint for assume role.
     */
    @SerialName("roleEndpoint")
    val roleEndpoint: String = "",

    /**
     * Region for the STS assume role request.
     */
    @SerialName("roleRegion")
    val roleRegion: String = "",

    // Throttling limits
    /**
     * Maximum download speed in bytes per second (0 = unlimited).
     */
    val downloadLimitBytesPerSecond: Long = 0,

    /**
     * Maximum upload speed in bytes per second (0 = unlimited).
     */
    val uploadLimitBytesPerSecond: Long = 0,

    /**
     * Maximum concurrent reads (0 = unlimited).
     */
    val maxConcurrentReads: Int = 0,

    /**
     * Maximum concurrent writes (0 = unlimited).
     */
    val maxConcurrentWrites: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as S3Options

        if (bucketName != other.bucketName) return false
        if (prefix != other.prefix) return false
        if (endpoint != other.endpoint) return false
        if (region != other.region) return false
        if (accessKeyId != other.accessKeyId) return false
        if (secretAccessKey != other.secretAccessKey) return false
        if (sessionToken != other.sessionToken) return false
        if (doNotUseTls != other.doNotUseTls) return false
        if (doNotVerifyTls != other.doNotVerifyTls) return false
        if (rootCa != null) {
            if (other.rootCa == null) return false
            if (!rootCa.contentEquals(other.rootCa)) return false
        } else if (other.rootCa != null) return false
        if (roleArn != other.roleArn) return false
        if (sessionName != other.sessionName) return false
        if (roleDurationSeconds != other.roleDurationSeconds) return false
        if (roleEndpoint != other.roleEndpoint) return false
        if (roleRegion != other.roleRegion) return false
        if (downloadLimitBytesPerSecond != other.downloadLimitBytesPerSecond) return false
        if (uploadLimitBytesPerSecond != other.uploadLimitBytesPerSecond) return false
        if (maxConcurrentReads != other.maxConcurrentReads) return false
        if (maxConcurrentWrites != other.maxConcurrentWrites) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bucketName.hashCode()
        result = 31 * result + prefix.hashCode()
        result = 31 * result + endpoint.hashCode()
        result = 31 * result + region.hashCode()
        result = 31 * result + accessKeyId.hashCode()
        result = 31 * result + secretAccessKey.hashCode()
        result = 31 * result + sessionToken.hashCode()
        result = 31 * result + doNotUseTls.hashCode()
        result = 31 * result + doNotVerifyTls.hashCode()
        result = 31 * result + (rootCa?.contentHashCode() ?: 0)
        result = 31 * result + roleArn.hashCode()
        result = 31 * result + sessionName.hashCode()
        result = 31 * result + roleDurationSeconds.hashCode()
        result = 31 * result + roleEndpoint.hashCode()
        result = 31 * result + roleRegion.hashCode()
        result = 31 * result + downloadLimitBytesPerSecond.hashCode()
        result = 31 * result + uploadLimitBytesPerSecond.hashCode()
        result = 31 * result + maxConcurrentReads.hashCode()
        result = 31 * result + maxConcurrentWrites.hashCode()
        return result
    }
}

/**
 * Storage configuration that can be persisted in the S3 bucket itself.
 * This is compatible with Go Kopia's .storageconfig file.
 */
@Serializable
data class S3StorageConfig(
    @SerialName("blobOptions")
    val blobOptions: List<PrefixAndStorageClass> = emptyList()
)

/**
 * Maps a blob prefix to a storage class.
 */
@Serializable
data class PrefixAndStorageClass(
    @SerialName("prefix")
    val prefix: String,

    @SerialName("storageClass")
    val storageClass: String
)

/**
 * Point-in-time view configuration for versioned buckets.
 */
data class PointInTimeOptions(
    val pointInTime: Instant? = null
)

package org.kopiaKt.storage.webdav

import kotlinx.serialization.Serializable

/**
 * Options for WebDAV-based storage.
 *
 * Compatible with Go Kopia's WebDAV options structure.
 */
@Serializable
data class WebDavOptions(
    /**
     * WebDAV server URL (e.g., "https://example.com/dav/")
     */
    val url: String,

    /**
     * Username for HTTP Basic authentication.
     */
    val username: String = "",

    /**
     * Password for HTTP Basic authentication.
     */
    val password: String = "",

    /**
     * SHA-256 fingerprint of the trusted server certificate.
     * If set, only this certificate will be accepted (useful for self-signed certs).
     */
    val trustedServerCertificateFingerprint: String = "",

    /**
     * Whether the server supports atomic write operations.
     * If false, writes will use temp file + rename pattern for safety.
     */
    val atomicWrites: Boolean = false,

    /**
     * Directory sharding configuration.
     * Defaults to [1, 3] for new repos, [3, 3] for existing.
     * For example, [1, 3] means first shard uses 1 char, second uses 3 chars.
     */
    val directoryShards: List<Int> = listOf(1, 3),

    /**
     * Maximum length of blob ID that won't be sharded.
     */
    val maxNonShardedLength: Int = 20,

    /**
     * Parallelism for listing operations.
     */
    val listParallelism: Int = 1,

    /**
     * Download speed limit in bytes per second (0 = unlimited).
     */
    val maxDownloadSpeedBytesPerSecond: Long = 0,

    /**
     * Upload speed limit in bytes per second (0 = unlimited).
     */
    val maxUploadSpeedBytesPerSecond: Long = 0
)

/**
 * Sharding parameters persisted in the storage.
 * Stored in .shards file at the root of the repository.
 */
@Serializable
data class ShardingParameters(
    /**
     * Default shards to use for blob IDs.
     */
    val default: List<Int> = listOf(1, 3),

    /**
     * Maximum length of blob ID that won't be sharded.
     */
    val maxNonShardedLength: Int = 20,

    /**
     * Per-prefix overrides for sharding.
     */
    val overrides: List<PrefixShards> = emptyList()
)

/**
 * Per-prefix sharding override.
 */
@Serializable
data class PrefixShards(
    /**
     * Blob ID prefix this override applies to.
     */
    val prefix: String,

    /**
     * Shards to use for blobs with this prefix.
     */
    val shards: List<Int>
)

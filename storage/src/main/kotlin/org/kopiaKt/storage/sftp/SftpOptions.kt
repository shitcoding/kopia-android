package org.kopiaKt.storage.sftp

import kotlinx.serialization.Serializable

/**
 * Options for SFTP-based storage.
 *
 * Compatible with Go Kopia's SFTP options structure.
 * Supports both password and SSH key authentication.
 */
@Serializable
data class SftpOptions(
    /**
     * Remote path on the SFTP server where blobs will be stored.
     */
    val path: String,

    /**
     * SFTP server hostname or IP address.
     */
    val host: String,

    /**
     * SFTP server port (default: 22).
     */
    val port: Int = 22,

    /**
     * Username for SSH authentication.
     */
    val username: String,

    /**
     * Password for SSH authentication.
     * If set, keyfile and keyData are ignored.
     */
    val password: String = "",

    /**
     * Path to SSH private key file.
     * Used if password is not set.
     */
    val keyfile: String = "",

    /**
     * SSH private key data (PEM format).
     * Used if password and keyfile are not set.
     */
    val keyData: String = "",

    /**
     * Path to known_hosts file.
     * Defaults to ~/.ssh/known_hosts if not specified.
     */
    val knownHostsFile: String = "",

    /**
     * Known hosts data (OpenSSH format).
     * Takes precedence over knownHostsFile if set.
     */
    val knownHostsData: String = "",

    /**
     * SSH host-key fingerprint to pin, in sshj format ("SHA256:<base64>" or MD5 hex "aa:bb:..").
     * When set (and no known_hosts is used), the server's key must match this fingerprint — a secure
     * way to trust a host without a known_hosts file.
     */
    val hostKeyFingerprint: String = "",

    /**
     * Explicitly disable SSH host-key verification (trust ANY server key). INSECURE — it exposes the
     * connection to MITM and must never be enabled in release builds. For local/testing use only;
     * production connections must supply known_hosts data/file or a pinned [hostKeyFingerprint].
     */
    val insecureSkipHostKeyVerification: Boolean = false,

    /**
     * Whether to use external SSH command instead of built-in SSH client.
     * Not supported in Kotlin implementation.
     */
    val externalSSH: Boolean = false,

    /**
     * External SSH command to use (default: "ssh").
     * Only used if externalSSH is true.
     */
    val sshCommand: String = "ssh",

    /**
     * Additional arguments for external SSH command.
     */
    val sshArguments: String = "",

    /**
     * Directory sharding to force when the repo has NO `.shards` file. Mirrors Go's nullable
     * `DirectoryShards`: `null` (unset, the default) means "infer" — `[1,3]` when creating, Go's legacy
     * `[3,3]` when opening — while an explicit list (including an empty list = flat) is used verbatim.
     * When the repo DOES have a `.shards` file, that file wins and this is ignored.
     */
    val directoryShards: List<Int>? = null,

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
    val maxUploadSpeedBytesPerSecond: Long = 0,

    /**
     * TCP connect timeout in milliseconds. Guards against an unreachable host hanging the connect
     * indefinitely. A coroutine `withTimeout` cannot preempt a blocking socket connect, so this must
     * be enforced at the socket layer. 0 = library default (no explicit timeout).
     */
    val connectTimeoutMillis: Int = 30_000,

    /**
     * Socket read timeout (SO_TIMEOUT) in milliseconds on the transport socket. Its main value is
     * bounding the pre-key-exchange banner read during connect (sshj already caps SFTP data requests
     * at its own ~30s timeout, so data ops were mostly covered already). It is a per-read idle
     * timeout, not a total-transfer cap — an active download keeps resetting it and sshj's transport
     * reader loops on it — so it can be generous. 0 = library default (no explicit timeout).
     */
    val socketTimeoutMillis: Int = 120_000,
) {
    /**
     * Gets the effective known_hosts file path.
     * Falls back to ~/.ssh/known_hosts if not specified.
     */
    fun effectiveKnownHostsFile(): String = if (knownHostsFile.isEmpty()) {
        val home = System.getProperty("user.home")
        "$home/.ssh/known_hosts"
    } else {
        knownHostsFile
    }
}

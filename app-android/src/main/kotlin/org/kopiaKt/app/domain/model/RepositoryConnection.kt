package org.kopiaKt.app.domain.model

import java.time.Instant

data class RepositoryConnection(
    val id: String,
    val displayName: String,
    val storageType: StorageType,
    val connectionConfig: ConnectionConfig,
    val lastConnected: Instant? = null,
    val isConnected: Boolean = false,
)

enum class StorageType {
    LOCAL_FILESYSTEM,
    S3,
    WEBDAV,
    SFTP,
    SAF,
}

sealed interface ConnectionConfig {
    data class LocalFilesystem(val path: String) : ConnectionConfig

    data class S3(
        val bucket: String,
        val endpoint: String,
        val region: String,
        val accessKeyId: String,
        val secretAccessKey: String = "",
        /** PEM-encoded root CA to trust instead of the system store (private/self-signed servers). */
        val rootCaPem: String = "",
        /** Explicit acknowledgment that credentials may travel over plaintext http. */
        val allowCleartextHttp: Boolean = false,
    ) : ConnectionConfig

    data class WebDAV(
        val url: String,
        val username: String,
        val password: String = "",
        /** SHA-256 fingerprint of the one server certificate to trust (self-signed servers). */
        val trustedServerCertificateFingerprint: String = "",
        /** Explicit acknowledgment that credentials may travel over plaintext http. */
        val allowCleartextHttp: Boolean = false,
    ) : ConnectionConfig

    data class SFTP(
        val host: String,
        val port: Int,
        val username: String,
        val path: String,
        val password: String = "",
        /** OpenSSH known_hosts content pinning the server key (preferred trust material). */
        val knownHostsData: String = "",
        /** sshj host-key fingerprint to pin ("SHA256:<base64>" or MD5 hex), if no known_hosts. */
        val hostKeyFingerprint: String = "",
        /** Trust ANY server key — INSECURE, dev/testing only; rejected in release builds. */
        val insecureSkipHostKeyVerification: Boolean = false,
    ) : ConnectionConfig

    data class SAF(
        val treeUri: String,
        val displayPath: String,
    ) : ConnectionConfig
}

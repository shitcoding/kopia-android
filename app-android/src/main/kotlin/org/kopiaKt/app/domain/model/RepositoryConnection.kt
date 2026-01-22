package org.kopiaKt.app.domain.model

import java.time.Instant

data class RepositoryConnection(
    val id: String,
    val displayName: String,
    val storageType: StorageType,
    val connectionConfig: ConnectionConfig,
    val lastConnected: Instant? = null,
    val isConnected: Boolean = false
)

enum class StorageType {
    LOCAL_FILESYSTEM,
    S3,
    WEBDAV,
    SFTP,
    SAF
}

sealed interface ConnectionConfig {
    data class LocalFilesystem(val path: String) : ConnectionConfig

    data class S3(
        val bucket: String,
        val endpoint: String,
        val region: String,
        val accessKeyId: String
    ) : ConnectionConfig

    data class WebDAV(
        val url: String,
        val username: String
    ) : ConnectionConfig

    data class SFTP(
        val host: String,
        val port: Int,
        val username: String,
        val path: String
    ) : ConnectionConfig

    data class SAF(
        val treeUri: String,
        val displayPath: String
    ) : ConnectionConfig
}

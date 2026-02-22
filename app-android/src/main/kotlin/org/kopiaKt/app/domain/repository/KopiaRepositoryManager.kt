package org.kopiaKt.app.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.RepositoryConnection
import org.kopiaKt.core.repository.DirectRepository

interface KopiaRepositoryManager {
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(config: ConnectionConfig, repositoryPassword: String): Result<RepositoryConnection>

    suspend fun create(
        config: ConnectionConfig,
        repositoryPassword: String,
        options: RepositoryCreateOptions = RepositoryCreateOptions()
    ): Result<RepositoryConnection>

    suspend fun disconnect()

    suspend fun getStoredConnections(): List<RepositoryConnection>

    suspend fun deleteStoredConnection(id: String)

    /** Returns the currently connected repository, or null if disconnected. */
    fun getRepository(): DirectRepository?
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val connection: RepositoryConnection) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

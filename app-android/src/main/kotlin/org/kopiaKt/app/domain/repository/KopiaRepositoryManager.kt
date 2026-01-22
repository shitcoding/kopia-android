package org.kopiaKt.app.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.RepositoryConnection

interface KopiaRepositoryManager {
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(config: ConnectionConfig, password: String): Result<RepositoryConnection>

    suspend fun disconnect()

    suspend fun getStoredConnections(): List<RepositoryConnection>

    suspend fun deleteStoredConnection(id: String)
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val connection: RepositoryConnection) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

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
        options: RepositoryCreateOptions = RepositoryCreateOptions(),
    ): Result<RepositoryConnection>

    /**
     * Contacts the storage backend and proves it can be listed with the given credentials and trust
     * material, then closes it again. No repository is opened, so no password is involved, and the
     * current connection -- if any -- is left untouched.
     *
     * This is what the wizard's "Test Connection" means. Validating the config alone is not enough:
     * a dead host or a wrong secret key passes every syntactic check.
     *
     * What it deliberately does NOT do is write. Credentials with read-only access therefore pass
     * here and fail later at repository creation, which is the lesser of two wrongs: a "test" that
     * leaves objects behind on a server the user may not end up using is worse than one that
     * answers a narrower question. Backends still create the target directory while connecting if
     * it is missing -- that is the storage layer's behaviour, not this probe's.
     */
    suspend fun testConnection(config: ConnectionConfig): Result<Unit>

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

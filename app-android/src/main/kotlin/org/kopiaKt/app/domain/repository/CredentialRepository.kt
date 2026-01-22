package org.kopiaKt.app.domain.repository

interface CredentialRepository {
    suspend fun storePassword(connectionId: String, password: String)

    suspend fun getPassword(connectionId: String): String?

    suspend fun deletePassword(connectionId: String)

    suspend fun hasPassword(connectionId: String): Boolean
}

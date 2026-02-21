package org.kopiaKt.app.domain.usecase

import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.RepositoryConnection
import org.kopiaKt.app.domain.repository.CredentialRepository
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import javax.inject.Inject

class ConnectRepositoryUseCase @Inject constructor(
    private val repositoryManager: KopiaRepositoryManager,
    private val credentialRepository: CredentialRepository
) {
    suspend operator fun invoke(
        config: ConnectionConfig,
        repositoryPassword: String,
        savePassword: Boolean
    ): Result<RepositoryConnection> {
        val result = repositoryManager.connect(config, repositoryPassword)

        if (result.isSuccess && savePassword) {
            val connection = result.getOrThrow()
            credentialRepository.storePassword(connection.id, repositoryPassword)
        }

        return result
    }
}

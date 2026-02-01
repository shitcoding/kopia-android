package org.kopiaKt.app.ui.screens.repositoryconnect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.StorageType
import org.kopiaKt.app.domain.usecase.ConnectRepositoryUseCase
import javax.inject.Inject

@HiltViewModel
class RepositoryConnectViewModel @Inject constructor(
    private val connectRepositoryUseCase: ConnectRepositoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RepositoryConnectUiState())
    val uiState: StateFlow<RepositoryConnectUiState> = _uiState.asStateFlow()

    fun updateStorageType(type: StorageType) {
        _uiState.update { it.copy(selectedStorageType = type, error = null) }
    }

    fun updateS3Config(
        bucket: String = _uiState.value.s3Config.bucket,
        endpoint: String = _uiState.value.s3Config.endpoint,
        region: String = _uiState.value.s3Config.region,
        accessKeyId: String = _uiState.value.s3Config.accessKeyId
    ) {
        _uiState.update {
            it.copy(s3Config = it.s3Config.copy(
                bucket = bucket,
                endpoint = endpoint,
                region = region,
                accessKeyId = accessKeyId
            ))
        }
    }

    fun updateWebDavConfig(
        url: String = _uiState.value.webDavConfig.url,
        username: String = _uiState.value.webDavConfig.username
    ) {
        _uiState.update {
            it.copy(webDavConfig = it.webDavConfig.copy(url = url, username = username))
        }
    }

    fun updateSftpConfig(
        host: String = _uiState.value.sftpConfig.host,
        port: Int = _uiState.value.sftpConfig.port,
        username: String = _uiState.value.sftpConfig.username,
        path: String = _uiState.value.sftpConfig.path
    ) {
        _uiState.update {
            it.copy(sftpConfig = it.sftpConfig.copy(
                host = host,
                port = port,
                username = username,
                path = path
            ))
        }
    }

    fun updateLocalConfig(path: String) {
        _uiState.update {
            it.copy(localConfig = it.localConfig.copy(path = path))
        }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password) }
    }

    fun updateSavePassword(save: Boolean) {
        _uiState.update { it.copy(savePassword = save) }
    }

    fun connect(onSuccess: () -> Unit) {
        val state = _uiState.value

        val config: ConnectionConfig = when (state.selectedStorageType) {
            StorageType.LOCAL_FILESYSTEM -> ConnectionConfig.LocalFilesystem(
                path = state.localConfig.path
            )
            StorageType.S3 -> ConnectionConfig.S3(
                bucket = state.s3Config.bucket,
                endpoint = state.s3Config.endpoint,
                region = state.s3Config.region,
                accessKeyId = state.s3Config.accessKeyId
            )
            StorageType.WEBDAV -> ConnectionConfig.WebDAV(
                url = state.webDavConfig.url,
                username = state.webDavConfig.username
            )
            StorageType.SFTP -> ConnectionConfig.SFTP(
                host = state.sftpConfig.host,
                port = state.sftpConfig.port,
                username = state.sftpConfig.username,
                path = state.sftpConfig.path
            )
            else -> {
                _uiState.update { it.copy(error = "Unsupported storage type") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, error = null) }

            val result = connectRepositoryUseCase(config, state.password, state.savePassword)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isConnecting = false) }
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            error = e.message ?: "Connection failed"
                        )
                    }
                }
            )
        }
    }
}

data class RepositoryConnectUiState(
    val selectedStorageType: StorageType = StorageType.LOCAL_FILESYSTEM,
    val localConfig: LocalFormState = LocalFormState(),
    val s3Config: S3FormState = S3FormState(),
    val webDavConfig: WebDavFormState = WebDavFormState(),
    val sftpConfig: SftpFormState = SftpFormState(),
    val password: String = "",
    val savePassword: Boolean = true,
    val isConnecting: Boolean = false,
    val error: String? = null
)

data class S3FormState(
    val bucket: String = "",
    val endpoint: String = "s3.amazonaws.com",
    val region: String = "us-east-1",
    val accessKeyId: String = ""
)

data class WebDavFormState(
    val url: String = "",
    val username: String = ""
)

data class SftpFormState(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val path: String = ""
)

data class LocalFormState(
    val path: String = ""  // Empty by default for easier testing
)

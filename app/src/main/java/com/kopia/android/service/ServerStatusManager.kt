package com.kopia.android.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Kopia server status across the application.
 * Provides a single source of truth for server connection state.
 */
class ServerStatusManager private constructor(private val context: Context) {

    enum class ServerStatus {
        DISCONNECTED,  // Server not running
        CONNECTING,    // Server starting up
        CONNECTED,     // Server running and responsive
        ERROR          // Server error
    }

    private val _serverStatus = MutableStateFlow(ServerStatus.DISCONNECTED)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _serverPort = MutableStateFlow(51515)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Check if server is responsive by pinging the API endpoint
     */
    suspend fun checkServerStatus(): ServerStatus {
        return try {
            val url = URL("http://127.0.0.1:${_serverPort.value}/api/v1/repo/status")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1000 // 1 second timeout
            connection.readTimeout = 1000

            val responseCode = connection.responseCode
            connection.disconnect()

            val newStatus = if (responseCode in 200..299 || responseCode == 401) {
                // 200-299: OK, 401: Unauthorized (server is up, just needs auth)
                ServerStatus.CONNECTED
            } else {
                ServerStatus.ERROR
            }

            _serverStatus.value = newStatus
            newStatus
        } catch (e: Exception) {
            val newStatus = ServerStatus.DISCONNECTED
            _serverStatus.value = newStatus
            newStatus
        }
    }

    fun setStatus(status: ServerStatus) {
        _serverStatus.value = status
    }

    fun setPort(port: Int) {
        _serverPort.value = port
    }

    fun setError(message: String?) {
        _errorMessage.value = message
        if (message != null) {
            _serverStatus.value = ServerStatus.ERROR
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    companion object {
        @Volatile
        private var instance: ServerStatusManager? = null

        fun getInstance(context: Context): ServerStatusManager {
            return instance ?: synchronized(this) {
                instance ?: ServerStatusManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

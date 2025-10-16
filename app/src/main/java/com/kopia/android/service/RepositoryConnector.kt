package com.kopia.android.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles repository connection via Kopia server API.
 * Validates passwords and connects to repositories through the running server.
 */
class RepositoryConnector(private val serverPort: Int = 51515) {

    companion object {
        private const val TAG = "RepositoryConnector"

        init {
            // Set up cookie manager for session handling
            val cookieManager = CookieManager()
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            CookieHandler.setDefault(cookieManager)
        }
    }

    private var authCookie: String? = null
    private var csrfToken: String? = null

    /**
     * Authenticate with server and get auth token + CSRF token
     * CookieManager handles Kopia-Auth cookie automatically, we just need CSRF token
     */
    private suspend fun authenticate(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Call any API endpoint with Basic Auth to trigger cookie/CSRF generation
                val statusUrl = URL("http://127.0.0.1:$serverPort/api/v1/repo/status")
                val statusConn = statusUrl.openConnection() as HttpURLConnection

                statusConn.requestMethod = "GET"
                statusConn.setRequestProperty("Authorization", "Basic " +
                    android.util.Base64.encodeToString("admin:admin".toByteArray(), android.util.Base64.NO_WRAP))
                statusConn.connectTimeout = 5000
                statusConn.readTimeout = 5000

                val responseCode = statusConn.responseCode
                Log.d(TAG, "Auth response code: $responseCode")

                // Check all response headers for CSRF token
                Log.d(TAG, "All response headers:")
                statusConn.headerFields.forEach { (key, values) ->
                    Log.d(TAG, "  $key: $values")
                }

                // Extract CSRF token from headers
                csrfToken = statusConn.getHeaderField("X-Kopia-Csrf-Token")
                    ?: statusConn.getHeaderField("X-CSRF-Token")
                    ?: statusConn.getHeaderField("Csrf-Token")

                if (csrfToken != null) {
                    Log.d(TAG, "Got CSRF token: ${csrfToken?.take(20)}...")
                } else {
                    Log.w(TAG, "No CSRF token found in response headers")
                }

                // Extract Kopia-Auth JWT cookie (CookieManager should handle this automatically)
                val cookies = statusConn.headerFields["Set-Cookie"]
                Log.d(TAG, "Auth cookies: $cookies")
                cookies?.forEach { cookie ->
                    if (cookie.startsWith("Kopia-Auth=")) {
                        authCookie = cookie.split(";")[0]
                        Log.d(TAG, "Got Kopia-Auth cookie: ${authCookie?.take(40)}...")
                    }
                }

                val response = try {
                    statusConn.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    statusConn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                Log.d(TAG, "Auth response: ${response.take(200)}")

                statusConn.disconnect()

                // Need either CSRF token or auth cookie
                val success = authCookie != null || csrfToken != null
                if (!success) {
                    Log.e(TAG, "Authentication failed - no cookie or CSRF token received")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to authenticate", e)
                false
            }
        }
    }

    /**
     * Connect to a repository via the Kopia server API
     * @param repoPath Path to the repository
     * @param password Repository password
     * @return Pair of (success: Boolean, errorMessage: String?)
     */
    suspend fun connectToRepository(repoPath: String, password: String): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                // Authenticate first to get session cookie and CSRF token
                if (!authenticate()) {
                    return@withContext false to "Failed to authenticate with server"
                }

                val url = URL("http://127.0.0.1:$serverPort/api/v1/repo/connect")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Basic " +
                    android.util.Base64.encodeToString("admin:admin".toByteArray(), android.util.Base64.NO_WRAP))
                // Don't manually set Cookie - CookieManager handles this automatically
                // Only set CSRF token if we have one
                if (csrfToken != null) {
                    connection.setRequestProperty("X-Kopia-Csrf-Token", csrfToken)
                }
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 30000  // Repository connection can take time

                // Build request JSON
                val requestJson = JSONObject().apply {
                    put("storage", JSONObject().apply {
                        put("type", "filesystem")
                        put("config", JSONObject().apply {
                            put("path", repoPath)
                        })
                    })
                    put("password", password)
                }

                Log.d(TAG, "Connecting to repository at $repoPath")

                // Send request
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestJson.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Connection response code: $responseCode")

                if (responseCode in 200..299) {
                    val response = connection.inputStream.bufferedReader().readText()
                    Log.d(TAG, "Connection successful: $response")
                    connection.disconnect()
                    true to null
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    Log.e(TAG, "Connection failed: $errorResponse")
                    connection.disconnect()

                    // Parse error message
                    val errorMsg = try {
                        val errorJson = JSONObject(errorResponse)
                        errorJson.optString("error", errorResponse)
                    } catch (e: Exception) {
                        errorResponse
                    }

                    false to errorMsg
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception connecting to repository", e)
                false to "Connection error: ${e.message}"
            }
        }
    }

    /**
     * Create a new repository via the Kopia server API
     * @param repoPath Path for the new repository
     * @param password Repository password
     * @return Pair of (success: Boolean, errorMessage: String?)
     */
    suspend fun createRepository(repoPath: String, password: String): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                // Authenticate first to get session cookie and CSRF token
                if (!authenticate()) {
                    return@withContext false to "Failed to authenticate with server"
                }

                val url = URL("http://127.0.0.1:$serverPort/api/v1/repo/create")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Basic " +
                    android.util.Base64.encodeToString("admin:admin".toByteArray(), android.util.Base64.NO_WRAP))
                // Don't manually set Cookie - CookieManager handles this automatically
                // Only set CSRF token if we have one
                if (csrfToken != null) {
                    connection.setRequestProperty("X-Kopia-Csrf-Token", csrfToken)
                }
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 30000 // Repository creation may take longer

                // Build request JSON
                val requestJson = JSONObject().apply {
                    put("storage", JSONObject().apply {
                        put("type", "filesystem")
                        put("config", JSONObject().apply {
                            put("path", repoPath)
                        })
                    })
                    put("password", password)
                    put("options", JSONObject().apply {
                        put("blockFormat", JSONObject().apply {
                            put("compression", "zstd")
                        })
                    })
                }

                Log.d(TAG, "Creating repository at $repoPath")

                // Send request
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestJson.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Create response code: $responseCode")

                if (responseCode in 200..299) {
                    val response = connection.inputStream.bufferedReader().readText()
                    Log.d(TAG, "Repository created successfully: $response")
                    connection.disconnect()
                    true to null
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    Log.e(TAG, "Repository creation failed: $errorResponse")
                    connection.disconnect()

                    // Parse error message
                    val errorMsg = try {
                        val errorJson = JSONObject(errorResponse)
                        errorJson.optString("error", errorResponse)
                    } catch (e: Exception) {
                        errorResponse
                    }

                    false to errorMsg
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception creating repository", e)
                false to "Creation error: ${e.message}"
            }
        }
    }
}

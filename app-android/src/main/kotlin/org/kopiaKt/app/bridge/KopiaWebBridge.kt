package org.kopiaKt.app.bridge

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.app.domain.repository.SnapshotRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hilt EntryPoint for accessing DI-managed services from the WebView bridge.
 * This allows non-Hilt classes (like KopiaWebBridge) to access Hilt-injected dependencies.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WebBridgeEntryPoint {
    fun repositoryManager(): KopiaRepositoryManager
    fun snapshotRepository(): SnapshotRepository
    fun credentialRepository(): org.kopiaKt.app.domain.repository.CredentialRepository
}

/**
 * JavaScript bridge for WebView communication with the Kotlin domain layer.
 * All methods annotated with @JavascriptInterface are callable from JavaScript
 * via window.KopiaBridge.methodName().
 *
 * Methods that need to return data use JSON serialization.
 * Async operations (like restore) push events back to JavaScript via evaluateJavascript.
 */
class KopiaWebBridge(
    private val context: Context,
    private val activity: ComponentActivity,
    private val containerView: android.view.View,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private companion object {
        const val TAG = "KopiaWebBridge"
    }

    private val entryPoint = EntryPointAccessors.fromApplication(
        context,
        WebBridgeEntryPoint::class.java
    )

    private val repositoryManager get() = entryPoint.repositoryManager()
    private val snapshotRepository get() = entryPoint.snapshotRepository()
    private val credentialRepository get() = entryPoint.credentialRepository()

    private val webViewRef = AtomicReference<WebView?>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val bridgeCallCounter = AtomicInteger(0)
    private var restoreJob: Job? = null

    /**
     * Attaches the WebView reference for pushing events to JavaScript.
     */
    fun attachWebView(webView: WebView) {
        webViewRef.set(webView)
    }

    /**
     * Simple ping to verify bridge communication is working.
     */
    @JavascriptInterface
    fun ping(): String {
        return json.encodeToString(WebResult.success("pong"))
    }

    /**
     * Update the status bar and navigation bar appearance based on the app's theme.
     * @param isDarkMode true for dark mode (light icons), false for light mode (dark icons)
     */
    @JavascriptInterface
    fun setStatusBarAppearance(isDarkMode: Boolean) {
        activity.runOnUiThread {
            val window = activity.window

            // Background colors matching React CSS exactly:
            // Light mode: --background: 220 20% 97% = #F7F8FA
            // Dark mode: --background: 220 25% 10% = #131720
            val bgColor = if (isDarkMode) {
                android.graphics.Color.parseColor("#131720")
            } else {
                android.graphics.Color.parseColor("#F7F8FA")
            }

            // Set status bar and navigation bar colors
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor

            // Set the decorView background (this affects the area behind system bars)
            window.decorView.setBackgroundColor(bgColor)

            // Update status bar and navigation bar icon colors
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDarkMode  // Light mode = dark icons, Dark mode = light icons
                isAppearanceLightNavigationBars = !isDarkMode
            }

            // Also update the container view background (the WebView's parent)
            containerView.setBackgroundColor(bgColor)
        }
    }

    /**
     * Get the current Android system theme mode.
     * @return JSON-encoded WebResult<String> - "light" or "dark"
     */
    @JavascriptInterface
    fun getSystemTheme(): String {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val theme = when (uiMode) {
            Configuration.UI_MODE_NIGHT_YES -> "dark"
            Configuration.UI_MODE_NIGHT_NO -> "light"
            else -> "light" // Default to light if undefined
        }
        return json.encodeToString(WebResult.success(theme))
    }

    /**
     * Check if the app has storage permission for accessing local filesystem.
     * On Android 11+, this requires MANAGE_EXTERNAL_STORAGE permission.
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun hasStoragePermission(): String {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Legacy storage is handled by manifest permissions
        }
        return json.encodeToString(WebResult.success(hasPermission))
    }

    /**
     * Open the system settings to grant storage permission.
     * On Android 11+, opens the "All files access" settings for this app.
     */
    @JavascriptInterface
    fun openStoragePermissionSettings() {
        activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                activity.startActivity(intent)
            }
        }
    }

    /**
     * Connect to a Kopia repository.
     * @param requestJson JSON-encoded WebConnectRequest
     * @return JSON-encoded WebResult<WebRepositoryConnection>
     */
    @JavascriptInterface
    fun connect(requestJson: String): String {
        return runBlocking {
            try {
                val request = json.decodeFromString<WebConnectRequest>(requestJson)

                // Check storage permission for local filesystem
                if (request.config.storageType == "LOCAL_FILESYSTEM") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        return@runBlocking json.encodeToString(
                            WebResult.error<WebRepositoryConnection>(
                                "Storage permission required. Please grant \"All files access\" permission to access local repositories.",
                                WebErrorCodes.STORAGE_PERMISSION_REQUIRED
                            )
                        )
                    }
                }

                val result = repositoryManager.connect(request.config.toDomain(), request.password)
                result.fold(
                    onSuccess = { json.encodeToString(WebResult.success(it.toWeb())) },
                    onFailure = { json.encodeToString(WebResult.error<WebRepositoryConnection>(it.message ?: "Unknown error")) }
                )
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<WebRepositoryConnection>(e.message ?: "Parse error"))
            }
        }
    }

    /**
     * Disconnect from the current repository.
     */
    @JavascriptInterface
    fun disconnect() {
        runBlocking {
            repositoryManager.disconnect()
        }
    }

    /**
     * List all snapshot sources in the connected repository.
     * @return JSON-encoded WebResult<List<WebSourceInfo>>
     */
    @JavascriptInterface
    fun listSources(): String {
        return runBlocking {
            try {
                val sources = snapshotRepository.listSources()
                json.encodeToString(WebResult.success(sources.map { it.toWeb() }))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<List<WebSourceInfo>>(e.message ?: "Error listing sources"))
            }
        }
    }

    /**
     * List all snapshot sources with aggregated statistics.
     * @return JSON-encoded WebResult<List<WebSourceWithStats>>
     */
    @JavascriptInterface
    fun listSourcesWithStats(): String {
        val callId = bridgeCallCounter.incrementAndGet()
        android.util.Log.e(TAG, "listSourcesWithStats ENTER callId=$callId thread=${Thread.currentThread().name}")
        return runBlocking {
            try {
                android.util.Log.e(TAG, "listSourcesWithStats RUN callId=$callId")
                val sources = snapshotRepository.listSourcesWithStats()
                android.util.Log.e(TAG, "listSourcesWithStats SUCCESS callId=$callId sourceCount=${sources.size}")
                val webSources = sources.map { src ->
                    WebSourceWithStats(
                        source = src.source.toWeb(),
                        snapshotCount = src.snapshotCount,
                        latestSnapshotTime = src.latestSnapshotTime.toEpochMilli(),
                        totalFileCount = src.totalFileCount.toLong(),
                        totalFileSize = src.totalFileSize
                    )
                }
                json.encodeToString(WebResult.success(webSources))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "listSourcesWithStats ERROR callId=$callId", e)
                json.encodeToString(
                    WebResult.error<List<WebSourceWithStats>>(
                        "listSourcesWithStats failed (${e::class.java.simpleName}): ${e.message ?: "Unknown error"}"
                    )
                )
            } finally {
                android.util.Log.e(TAG, "listSourcesWithStats EXIT callId=$callId")
            }
        }
    }

    /**
     * List snapshots for a source with computed retention reasons.
     * @param requestJson JSON-encoded WebSnapshotListRequest
     * @return JSON-encoded WebResult<List<WebSnapshotWithRetention>>
     */
    @JavascriptInterface
    fun listSnapshotsWithRetention(requestJson: String): String {
        return runBlocking {
            try {
                val request = json.decodeFromString<WebSnapshotListRequest>(requestJson)
                val source = request.source?.toDomain()
                    ?: throw IllegalArgumentException("Source is required")
                val results = snapshotRepository.listSnapshotsWithRetention(source)
                val webResults = results.map { result ->
                    WebSnapshotWithRetention(
                        id = result.snapshot.id,
                        source = result.snapshot.source.toWeb(),
                        startTimeEpochMs = result.snapshot.startTime.toEpochMilli(),
                        endTimeEpochMs = result.snapshot.endTime?.toEpochMilli(),
                        description = result.snapshot.description,
                        stats = result.snapshot.stats?.toWeb(),
                        isIncomplete = result.snapshot.isIncomplete,
                        tags = result.snapshot.tags,
                        retentionReasons = result.retentionReasons
                    )
                }
                json.encodeToString(WebResult.success(webResults))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<List<WebSnapshotWithRetention>>(e.message ?: "Error listing snapshots with retention"))
            }
        }
    }

    /**
     * Delete multiple snapshots by their manifest IDs.
     * @param requestJson JSON-encoded WebDeleteSnapshotsRequest
     * @return JSON-encoded WebResult<Unit>
     */
    @JavascriptInterface
    fun deleteSnapshots(requestJson: String): String {
        return runBlocking {
            try {
                val request = json.decodeFromString<WebDeleteSnapshotsRequest>(requestJson)
                snapshotRepository.deleteSnapshots(request.snapshotIds)
                json.encodeToString(WebResult.success(Unit))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<Unit>(e.message ?: "Error deleting snapshots"))
            }
        }
    }

    /**
     * List snapshots, optionally filtered by source.
     * @param requestJson JSON-encoded WebSnapshotListRequest
     * @return JSON-encoded WebResult<List<WebSnapshotInfo>>
     */
    @JavascriptInterface
    fun listSnapshots(requestJson: String): String {
        return runBlocking {
            try {
                val request = json.decodeFromString<WebSnapshotListRequest>(requestJson)
                val snapshots = snapshotRepository.listSnapshots(request.source?.toDomain())
                json.encodeToString(WebResult.success(snapshots.map { it.toWeb() }))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<List<WebSnapshotInfo>>(e.message ?: "Error listing snapshots"))
            }
        }
    }

    /**
     * Get a single snapshot by ID.
     * @param snapshotId The snapshot manifest ID
     * @return JSON-encoded WebResult<WebSnapshotInfo?>
     */
    @JavascriptInterface
    fun getSnapshot(snapshotId: String): String {
        return runBlocking {
            try {
                val snapshot = snapshotRepository.getSnapshot(snapshotId)
                json.encodeToString(WebResult.success(snapshot?.toWeb()))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<WebSnapshotInfo?>(e.message ?: "Error getting snapshot"))
            }
        }
    }

    /**
     * List directory entries with pagination support.
     * @param requestJson JSON-encoded WebListDirectoryRequest
     * @return JSON-encoded WebResult<WebDirectoryPage>
     */
    @JavascriptInterface
    fun listDirectory(requestJson: String): String {
        return runBlocking {
            try {
                val request = json.decodeFromString<WebListDirectoryRequest>(requestJson)
                val all = snapshotRepository.browseDirectory(request.snapshotId, request.path)

                // Simple pagination
                val start = request.pageToken?.toIntOrNull() ?: 0
                val size = request.pageSize ?: all.size
                val slice = all.drop(start).take(size)
                val next = if (start + size < all.size) (start + size).toString() else null

                val page = WebDirectoryPage(
                    entries = slice.map { it.toWeb() },
                    nextPageToken = next
                )
                json.encodeToString(WebResult.success(page))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<WebDirectoryPage>(e.message ?: "Error listing directory"))
            }
        }
    }

    /**
     * Start a restore operation. Progress is pushed to JavaScript via KopiaEvents.onRestoreProgress.
     * @param requestJson JSON-encoded WebRestoreRequest
     */
    @JavascriptInterface
    fun startRestore(requestJson: String) {
        // Cancel any existing restore
        restoreJob?.cancel()
        snapshotRepository.cancelRestore()

        val request = json.decodeFromString<WebRestoreRequest>(requestJson)
        val options = request.options?.toDomain() ?: org.kopiaKt.app.domain.repository.RestoreOptions()

        restoreJob = scope.launch {
            try {
                snapshotRepository.restore(
                    snapshotId = request.snapshotId,
                    sourcePath = request.sourcePath,
                    destinationUri = request.destinationUri,
                    options = options
                ).collect { progress ->
                    pushRestoreProgress(progress.toWeb())

                    // Check for terminal state
                    if (progress.state.isTerminal()) {
                        // Flow will complete naturally
                    }
                }
            } catch (e: CancellationException) {
                // Job was cancelled externally - expected behavior
                throw e
            } catch (e: Exception) {
                pushRestoreProgress(WebRestoreProgress(
                    state = "FAILED",
                    totalFiles = 0,
                    restoredFiles = 0,
                    totalBytes = 0,
                    restoredBytes = 0,
                    errorMessage = e.message
                ))
            }
        }
    }

    /**
     * Cancel an in-progress restore operation.
     */
    @JavascriptInterface
    fun cancelRestore() {
        snapshotRepository.cancelRestore()
        restoreJob?.cancel()

        pushRestoreProgress(WebRestoreProgress(
            state = "CANCELLED",
            totalFiles = 0,
            restoredFiles = 0,
            totalBytes = 0,
            restoredBytes = 0,
            errorMessage = "Cancelled"
        ))
    }

    /**
     * Launch the Android Storage Access Framework folder picker.
     * Result is pushed to JavaScript via KopiaEvents.onDestinationPicked.
     */
    @JavascriptInterface
    fun pickRestoreDestination() {
        val key = "kopia_pick_${UUID.randomUUID()}"
        activity.runOnUiThread {
            var launcher: androidx.activity.result.ActivityResultLauncher<Uri?>? = null
            launcher = activity.activityResultRegistry.register(
                key,
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                launcher?.unregister()
                val result = if (uri == null) {
                    WebSafPickResult(uri = null, displayName = null)
                } else {
                    WebSafPickResult(
                        uri = uri.toString(),
                        displayName = uri.lastPathSegment
                    )
                }
                pushDestinationPicked(result)
            }
            launcher.launch(null)
        }
    }

    /**
     * Persist URI permissions for a SAF-selected folder.
     * @param requestJson JSON-encoded WebPersistUriRequest
     * @return JSON-encoded WebResult<Unit>
     */
    @JavascriptInterface
    fun persistUriPermission(requestJson: String): String {
        return try {
            val request = json.decodeFromString<WebPersistUriRequest>(requestJson)
            val flags = (if (request.read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (request.write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            context.contentResolver.takePersistableUriPermission(Uri.parse(request.uri), flags)
            json.encodeToString(WebResult.success(Unit))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<Unit>(e.message ?: "Failed to persist permission"))
        }
    }

    /**
     * Check if a password is stored for the given repository configuration.
     * @param configJson JSON-encoded ConnectionConfig
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun hasStoredPassword(configJson: String): String = runBlocking {
        try {
            val config = json.decodeFromString<WebConnectionConfig>(configJson)
            val connectionId = generateConnectionId(config)
            val hasPassword = credentialRepository.hasPassword(connectionId)
            json.encodeToString(WebResult.success(hasPassword))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "hasStoredPassword error", e)
            json.encodeToString(WebResult.error<Boolean>(e.message ?: "Failed to check stored password"))
        }
    }

    /**
     * Retrieve stored password for the given repository configuration.
     * @param configJson JSON-encoded ConnectionConfig
     * @return JSON-encoded WebResult<String?>
     */
    @JavascriptInterface
    fun getStoredPassword(configJson: String): String = runBlocking {
        try {
            val config = json.decodeFromString<WebConnectionConfig>(configJson)
            val connectionId = generateConnectionId(config)
            val password = credentialRepository.getPassword(connectionId)
            json.encodeToString(WebResult.success(password))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getStoredPassword error", e)
            json.encodeToString(WebResult.error<String?>(e.message ?: "Failed to get stored password"))
        }
    }

    /**
     * Store password for the given repository configuration using encrypted storage.
     * @param configJson JSON-encoded ConnectionConfig
     * @param password The password to store (will be encrypted)
     * @return JSON-encoded WebResult<Unit>
     */
    @JavascriptInterface
    fun storePassword(configJson: String, password: String): String = runBlocking {
        try {
            val config = json.decodeFromString<WebConnectionConfig>(configJson)
            val connectionId = generateConnectionId(config)
            credentialRepository.storePassword(connectionId, password)
            json.encodeToString(WebResult.success(Unit))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "storePassword error", e)
            json.encodeToString(WebResult.error<Unit>(e.message ?: "Failed to store password"))
        }
    }

    /**
     * Generate a stable connection ID from repository configuration.
     * Used as a key for storing/retrieving passwords.
     */
    private fun generateConnectionId(config: WebConnectionConfig): String {
        val parts = when (config.storageType) {
            "LOCAL_FILESYSTEM" ->
                listOf("local", config.local?.path ?: "")
            "S3" ->
                listOf("s3", config.s3?.bucket ?: "", config.s3?.endpoint ?: "")
            "WEBDAV" ->
                listOf("webdav", config.webdav?.url ?: "")
            "SFTP" ->
                listOf("sftp", config.sftp?.host ?: "", config.sftp?.port?.toString() ?: "")
            "SAF" ->
                listOf("saf", config.saf?.treeUri ?: "")
            else ->
                listOf("unknown", config.storageType)
        }
        return parts.joinToString(":").hashCode().toString()
    }

    /**
     * Push restore progress to JavaScript.
     */
    private fun pushRestoreProgress(progress: WebRestoreProgress) {
        val jsonStr = json.encodeToString(progress)
        webViewRef.get()?.post {
            webViewRef.get()?.evaluateJavascript(
                "window.KopiaEvents?.onRestoreProgress?.($jsonStr);",
                null
            )
        }
    }

    /**
     * Push SAF picker result to JavaScript.
     */
    private fun pushDestinationPicked(result: WebSafPickResult) {
        val jsonStr = json.encodeToString(result)
        webViewRef.get()?.post {
            webViewRef.get()?.evaluateJavascript(
                "window.KopiaEvents?.onDestinationPicked?.($jsonStr);",
                null
            )
        }
    }

    /**
     * Notify JavaScript when the Android system theme changes.
     * Called by the Activity when it detects a configuration change.
     */
    fun notifySystemThemeChanged() {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val theme = when (uiMode) {
            Configuration.UI_MODE_NIGHT_YES -> "dark"
            Configuration.UI_MODE_NIGHT_NO -> "light"
            else -> "light"
        }
        val jsonStr = json.encodeToString(theme)
        webViewRef.get()?.post {
            webViewRef.get()?.evaluateJavascript(
                "window.KopiaEvents?.onSystemThemeChanged?.($jsonStr);",
                null
            )
        }
    }

    /**
     * Clean up resources when the bridge is no longer needed.
     */
    fun cleanup() {
        restoreJob?.cancel()
        scope.cancel()
    }
}

package org.kopiaKt.app.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
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

/**
 * Hilt EntryPoint for accessing DI-managed services from the WebView bridge.
 * This allows non-Hilt classes (like KopiaWebBridge) to access Hilt-injected dependencies.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WebBridgeEntryPoint {
    fun repositoryManager(): KopiaRepositoryManager
    fun snapshotRepository(): SnapshotRepository
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val entryPoint = EntryPointAccessors.fromApplication(
        context,
        WebBridgeEntryPoint::class.java
    )

    private val repositoryManager get() = entryPoint.repositoryManager()
    private val snapshotRepository get() = entryPoint.snapshotRepository()

    private val webViewRef = AtomicReference<WebView?>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
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
     * Connect to a Kopia repository.
     * @param requestJson JSON-encoded WebConnectRequest
     * @return JSON-encoded WebResult<WebRepositoryConnection>
     */
    @JavascriptInterface
    fun connect(requestJson: String): String {
        return runBlocking {
            try {
                val request = json.decodeFromString<WebConnectRequest>(requestJson)
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
     * Clean up resources when the bridge is no longer needed.
     */
    fun cleanup() {
        restoreJob?.cancel()
        scope.cancel()
    }
}

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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.TaskKind
import org.kopiaKt.android.worker.TaskManager
import org.kopiaKt.app.BuildConfig
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.app.domain.repository.SnapshotRepository
import org.kopiaKt.snapshot.policy.PolicyManager
import java.security.MessageDigest
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
class KopiaWebBridge private constructor(
    private val context: Context?,
    private val activity: ComponentActivity?,
    private val containerView: android.view.View?,
    private val scope: CoroutineScope,
    private val _taskManager: TaskManager?,
    private val _sourceManager: BackupSourceManager?,
    private val _repositoryManager: KopiaRepositoryManager?,
) {
    /**
     * Primary constructor for production use with Android context and Hilt DI.
     */
    constructor(
        context: Context,
        activity: ComponentActivity,
        containerView: android.view.View,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ) : this(
        context = context,
        activity = activity,
        containerView = containerView,
        scope = scope,
        _taskManager = null,
        _sourceManager = null,
        _repositoryManager = null,
    )

    /**
     * Test constructor that accepts managers directly, avoiding Android/Hilt dependencies.
     */
    constructor(
        taskManager: TaskManager,
        sourceManager: BackupSourceManager,
        repositoryManager: KopiaRepositoryManager,
    ) : this(
        context = null,
        activity = null,
        containerView = null,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        _taskManager = taskManager,
        _sourceManager = sourceManager,
        _repositoryManager = repositoryManager,
    )

    private companion object {
        const val TAG = "KopiaWebBridge"

        // Safety net so an unreachable backend can't hang the connect/create coroutine indefinitely.
        const val NETWORK_TIMEOUT_MS = 120_000L
        const val MILLIS_PER_SECOND = 1000L
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context!!,
            WebBridgeEntryPoint::class.java,
        )
    }

    private val repositoryManager: KopiaRepositoryManager
        get() = _repositoryManager ?: entryPoint.repositoryManager()
    private val snapshotRepository get() = entryPoint.snapshotRepository()
    private val credentialRepository get() = entryPoint.credentialRepository()

    private val lazyTaskManager by lazy { TaskManager() }
    internal val taskManager: TaskManager
        get() = _taskManager ?: lazyTaskManager
    private val lazySourceManager by lazy { BackupSourceManager() }
    internal val sourceManager: BackupSourceManager
        get() = _sourceManager ?: lazySourceManager

    private val webViewRef = AtomicReference<WebView?>()

    // Shared with the bridge contract tests via WebModels.bridgeJson so the test pins can't drift.
    private val json = bridgeJson
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
    fun ping(): String = json.encodeToString(WebResult.success("pong"))

    /**
     * Update the status bar and navigation bar appearance based on the app's theme.
     * @param isDarkMode true for dark mode (light icons), false for light mode (dark icons)
     */
    @JavascriptInterface
    fun setStatusBarAppearance(isDarkMode: Boolean) {
        val act = activity ?: return
        act.runOnUiThread {
            val window = act.window

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
                isAppearanceLightStatusBars = !isDarkMode // Light mode = dark icons, Dark mode = light icons
                isAppearanceLightNavigationBars = !isDarkMode
            }

            // Also update the container view background (the WebView's parent)
            containerView?.setBackgroundColor(bgColor)
        }
    }

    /**
     * Get the current Android system theme mode.
     * @return JSON-encoded WebResult<String> - "light" or "dark"
     */
    @JavascriptInterface
    fun getSystemTheme(): String {
        val uiMode = (context?.resources?.configuration?.uiMode ?: 0) and Configuration.UI_MODE_NIGHT_MASK
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
        val act = activity ?: return
        val ctx = context ?: return
        act.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
                act.startActivity(intent)
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
        // Launch on the bridge's cancellable scope (cancelled by cleanup()), off the UI thread.
        scope.launch(Dispatchers.IO) {
            try {
                val request = json.decodeFromString<WebConnectRequest>(requestJson)

                // Check storage permission for local filesystem
                if (request.config.storageType == "LOCAL_FILESYSTEM") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        pushConnectResult(
                            WebResult.error<WebRepositoryConnection>(
                                "Storage permission required. Please grant \"All files access\" permission to access local repositories.",
                                WebErrorCodes.STORAGE_PERMISSION_REQUIRED,
                            ),
                        )
                        return@launch
                    }
                }

                // Repository encryption password resolution, in order: the explicit field, the legacy
                // 'password' field, then the securely-stored password for this repo. The stored
                // password is retrieved and used entirely in native code — it is never exposed to JS
                // (that is why getStoredPassword was removed): an empty password from the UI means
                // "use the saved one". Storage-specific credentials (s3.secretAccessKey,
                // webdav.password, sftp.password) must always be sent in their config objects.
                val repositoryPassword = request.repositoryPassword
                    .ifEmpty { request.password }
                    .ifEmpty { credentialRepository.getPassword(generateConnectionId(request.config)).orEmpty() }
                val result = withTimeout(NETWORK_TIMEOUT_MS) {
                    repositoryManager.connect(request.config.toDomain(), repositoryPassword)
                }
                pushConnectResult(
                    result.fold(
                        onSuccess = { WebResult.success(it.toWeb()) },
                        onFailure = { WebResult.error<WebRepositoryConnection>(it.message ?: "Unknown error") },
                    ),
                )
            } catch (ignored: TimeoutCancellationException) {
                // withTimeout's exception IS a CancellationException, so catch it FIRST and surface it —
                // otherwise the rethrow below drops the JS callback and the connect promise hangs forever.
                pushConnectResult(
                    WebResult.error<WebRepositoryConnection>(
                        "Connection timed out after ${NETWORK_TIMEOUT_MS / MILLIS_PER_SECOND}s",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pushConnectResult(WebResult.error<WebRepositoryConnection>(e.message ?: "Parse error"))
            }
        }

        // Return immediately - actual result will come via callback
        return json.encodeToString(WebResult.success("connecting"))
    }

    private fun pushConnectResult(result: WebResult<WebRepositoryConnection>) {
        pushCallback("window.__kopiaConnectCallback", json.encodeToString(result))
    }

    private fun callJavaScript(script: String) {
        activity?.runOnUiThread {
            webViewRef.get()?.evaluateJavascript(script, null)
        }
    }

    /**
     * Invoke a JS callback with [payload] passed as a properly-escaped JS string literal. Encoding
     * the string via JSON escapes quotes, backslashes, and all control chars < 0x20 (including the \r
     * the old hand-rolled `replace("\\", ...)` chain missed) into a valid JS string literal. (U+2028/
     * U+2029 are left raw — legal in JSON and, since ES2019, in JS string literals too, which every
     * WebView this app runs on supports.)
     */
    private fun pushCallback(callbackExpr: String, payload: String) {
        callJavaScript("$callbackExpr(${json.encodeToString(payload)})")
    }

    // ================================================================
    // Repository Creation Methods
    // ================================================================

    /**
     * Returns the list of supported hashing, encryption, and compression algorithms.
     * Algorithm IDs must match the core enum IDs exactly (e.g. "AES256-GCM-HMAC-SHA256").
     * @return JSON-encoded WebResult<WebSupportedAlgorithms>
     */
    @JavascriptInterface
    fun getSupportedAlgorithms(): String = json.encodeToString(
        WebResult.success(
            WebSupportedAlgorithms(
                hashing = listOf("BLAKE2B-256-128", "BLAKE3-256", "HMAC-SHA256-128"),
                encryption = listOf("AES256-GCM-HMAC-SHA256", "NONE"),
                compression = listOf("zstd", "lz4", "gzip", "pgzip", "deflate-default", "none"),
            ),
        ),
    )

    /**
     * Test whether the given storage configuration is reachable and writable.
     * For local filesystem paths, validates existence, creates the directory if needed,
     * and performs an actual write/delete test.
     * @param configJson JSON-encoded WebConnectionConfig
     * @return JSON-encoded WebResult<String> ("OK" on success)
     */
    @JavascriptInterface
    fun testStorageConnection(configJson: String): String {
        return runBlocking {
            try {
                val config = json.decodeFromString<WebConnectionConfig>(configJson)
                val domainConfig = config.toDomain()
                when (domainConfig) {
                    is org.kopiaKt.app.domain.model.ConnectionConfig.LocalFilesystem -> {
                        val dir = java.io.File(domainConfig.path)
                        if (!dir.exists()) {
                            if (!dir.mkdirs()) {
                                return@runBlocking json.encodeToString(
                                    WebResult.error<String>("Directory does not exist and cannot be created: ${domainConfig.path}"),
                                )
                            }
                        }
                        if (!dir.isDirectory) {
                            return@runBlocking json.encodeToString(
                                WebResult.error<String>("Path is not a directory: ${domainConfig.path}"),
                            )
                        }
                        if (!dir.canWrite()) {
                            return@runBlocking json.encodeToString(
                                WebResult.error<String>("Directory is not writable: ${domainConfig.path}"),
                            )
                        }
                        val testFile = java.io.File(dir, ".kopia-test-${System.currentTimeMillis()}")
                        try {
                            testFile.writeText("test")
                            testFile.delete()
                        } catch (e: Exception) {
                            return@runBlocking json.encodeToString(
                                WebResult.error<String>("Cannot write to directory: ${e.message}"),
                            )
                        }
                        json.encodeToString(WebResult.success("OK"))
                    }
                    // Remote backends are not actually contacted here, but the cleartext policy must
                    // still apply: otherwise Test Connection reports "OK" for an unacknowledged http://
                    // endpoint that the connect layer will refuse, and the create wizard would wave the
                    // user past a configuration that leaks credentials.
                    else -> {
                        validateRemoteConfig(domainConfig)
                        json.encodeToString(WebResult.success("OK"))
                    }
                }
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<String>(e.message ?: "Invalid configuration"))
            }
        }
    }

    /**
     * Applies the config-level policies a remote backend must satisfy before Test Connection may report
     * success. Remote backends are not actually contacted here, so without this the test would report
     * "OK" for a configuration the connect layer then refuses — waving the create wizard past a
     * credential-leaking or malformed setup.
     *
     * Checks the cleartext acknowledgment (the same gate the connect layer enforces) and the
     * well-formedness of any TLS trust material, so a malformed CA or pin is reported here rather than
     * only surfacing later at connect/create. Non-remote configs are unaffected.
     */
    private fun validateRemoteConfig(config: org.kopiaKt.app.domain.model.ConnectionConfig) {
        when (config) {
            is org.kopiaKt.app.domain.model.ConnectionConfig.S3 -> {
                org.kopiaKt.app.data.repository.requireCleartextAllowed(
                    config.endpoint,
                    config.allowCleartextHttp,
                )
                config.rootCaPem.takeIf { it.isNotBlank() }?.let {
                    org.kopiaKt.storage.tls.TlsTrust.trustManagerForRootCa(it.toByteArray())
                }
            }
            is org.kopiaKt.app.domain.model.ConnectionConfig.WebDAV -> {
                org.kopiaKt.app.data.repository.requireCleartextAllowed(
                    config.url,
                    config.allowCleartextHttp,
                )
                config.trustedServerCertificateFingerprint.takeIf { it.isNotBlank() }?.let {
                    org.kopiaKt.storage.tls.TlsTrust.normalizeSha256Fingerprint(it)
                }
            }
            else -> Unit
        }
    }

    /**
     * Create a new Kopia repository.
     * Result is pushed to JavaScript via KopiaEvents.onRepositoryCreated.
     * @param requestJson JSON-encoded WebCreateRepositoryRequest
     */
    @JavascriptInterface
    fun createRepository(requestJson: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = json.decodeFromString<WebCreateRepositoryRequest>(requestJson)
                val connectionConfig = request.config.toDomain()
                val keyDerivationAlgorithm = "scrypt-${BuildConfig.SCRYPT_N}-${BuildConfig.SCRYPT_R}-${BuildConfig.SCRYPT_P}"
                val options = org.kopiaKt.app.domain.repository.RepositoryCreateOptions(
                    description = request.options.description,
                    hashAlgorithm = request.options.hash,
                    encryptionAlgorithm = request.options.encryption,
                    keyDerivationAlgorithm = keyDerivationAlgorithm,
                )
                val result = withTimeout(NETWORK_TIMEOUT_MS) {
                    repositoryManager.create(
                        config = connectionConfig,
                        repositoryPassword = request.password,
                        options = options,
                    )
                }
                val webResult = result.fold(
                    onSuccess = {
                        WebResult.success(
                            WebRepositoryCreationResult(
                                storageType = request.config.storageType,
                                encryption = request.options.encryption,
                                hashing = request.options.hash,
                                description = request.options.description,
                            ),
                        )
                    },
                    onFailure = {
                        WebResult.error<WebRepositoryCreationResult>(it.message ?: "Repository creation failed")
                    },
                )
                pushCallback("window.KopiaEvents?.onRepositoryCreated?.", json.encodeToString(webResult))
            } catch (ignored: TimeoutCancellationException) {
                // See connect(): surface the timeout instead of letting the CancellationException
                // rethrow drop the callback and hang the create promise.
                pushCallback(
                    "window.KopiaEvents?.onRepositoryCreated?.",
                    json.encodeToString(
                        WebResult.error<WebRepositoryCreationResult>(
                            "Repository creation timed out after ${NETWORK_TIMEOUT_MS / MILLIS_PER_SECOND}s",
                        ),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pushCallback(
                    "window.KopiaEvents?.onRepositoryCreated?.",
                    json.encodeToString(WebResult.error<WebRepositoryCreationResult>(e.message ?: "Parse error")),
                )
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
    fun listSources(): String = runBlocking {
        try {
            val sources = snapshotRepository.listSources()
            json.encodeToString(WebResult.success(sources.map { it.toWeb() }))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<List<WebSourceInfo>>(e.message ?: "Error listing sources"))
        }
    }

    /**
     * List all snapshot sources with aggregated statistics.
     * @return JSON-encoded WebResult<List<WebSourceWithStats>>
     */
    @JavascriptInterface
    fun listSourcesWithStats(): String = runBlocking {
        try {
            val sources = snapshotRepository.listSourcesWithStats()
            val webSources = sources.map { src ->
                WebSourceWithStats(
                    source = src.source.toWeb(),
                    snapshotCount = src.snapshotCount,
                    latestSnapshotTime = src.latestSnapshotTime.toEpochMilli(),
                    totalFileCount = src.totalFileCount.toLong(),
                    totalFileSize = src.totalFileSize,
                )
            }
            json.encodeToString(WebResult.success(webSources))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "listSourcesWithStats ERROR", e)
            json.encodeToString(
                WebResult.error<List<WebSourceWithStats>>(
                    "listSourcesWithStats failed (${e::class.java.simpleName}): ${e.message ?: "Unknown error"}",
                ),
            )
        }
    }

    /**
     * List snapshots for a source with computed retention reasons.
     * @param requestJson JSON-encoded WebSnapshotListRequest
     * @return JSON-encoded WebResult<List<WebSnapshotWithRetention>>
     */
    @JavascriptInterface
    fun listSnapshotsWithRetention(requestJson: String): String = runBlocking {
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
                    retentionReasons = result.retentionReasons,
                )
            }
            json.encodeToString(WebResult.success(webResults))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<List<WebSnapshotWithRetention>>(e.message ?: "Error listing snapshots with retention"))
        }
    }

    /**
     * Delete multiple snapshots by their manifest IDs.
     * @param requestJson JSON-encoded WebDeleteSnapshotsRequest
     * @return JSON-encoded WebResult<Unit>
     */
    @JavascriptInterface
    fun deleteSnapshots(requestJson: String): String = runBlocking {
        try {
            val request = json.decodeFromString<WebDeleteSnapshotsRequest>(requestJson)
            snapshotRepository.deleteSnapshots(request.snapshotIds)
            json.encodeToString(WebResult.success(Unit))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<Unit>(e.message ?: "Error deleting snapshots"))
        }
    }

    /**
     * List snapshots, optionally filtered by source.
     * @param requestJson JSON-encoded WebSnapshotListRequest
     * @return JSON-encoded WebResult<List<WebSnapshotInfo>>
     */
    @JavascriptInterface
    fun listSnapshots(requestJson: String): String = runBlocking {
        try {
            val request = json.decodeFromString<WebSnapshotListRequest>(requestJson)
            val snapshots = snapshotRepository.listSnapshots(request.source?.toDomain())
            json.encodeToString(WebResult.success(snapshots.map { it.toWeb() }))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<List<WebSnapshotInfo>>(e.message ?: "Error listing snapshots"))
        }
    }

    /**
     * Get a single snapshot by ID.
     * @param snapshotId The snapshot manifest ID
     * @return JSON-encoded WebResult<WebSnapshotInfo?>
     */
    @JavascriptInterface
    fun getSnapshot(snapshotId: String): String = runBlocking {
        try {
            val snapshot = snapshotRepository.getSnapshot(snapshotId)
            json.encodeToString(WebResult.success(snapshot?.toWeb()))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<WebSnapshotInfo?>(e.message ?: "Error getting snapshot"))
        }
    }

    /**
     * List directory entries with pagination support.
     * @param requestJson JSON-encoded WebListDirectoryRequest
     * @return JSON-encoded WebResult<WebDirectoryPage>
     */
    @JavascriptInterface
    fun listDirectory(requestJson: String): String = runBlocking {
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
                nextPageToken = next,
            )
            json.encodeToString(WebResult.success(page))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<WebDirectoryPage>(e.message ?: "Error listing directory"))
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

        restoreJob = scope.launch {
            try {
                // Decode inside the guarded block: an exception thrown out of a @JavascriptInterface
                // method kills the process, so any script reaching the WebView could crash the app
                // with a malformed request.
                val request = json.decodeFromString<WebRestoreRequest>(requestJson)
                val options = request.options?.toDomain()
                    ?: org.kopiaKt.app.domain.repository.RestoreOptions()

                snapshotRepository.restore(
                    snapshotId = request.snapshotId,
                    sourcePath = request.sourcePath,
                    destinationUri = request.destinationUri,
                    options = options,
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
                pushRestoreProgress(
                    WebRestoreProgress(
                        state = "FAILED",
                        totalFiles = 0,
                        restoredFiles = 0,
                        totalBytes = 0,
                        restoredBytes = 0,
                        errorMessage = e.message,
                    ),
                )
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

        pushRestoreProgress(
            WebRestoreProgress(
                state = "CANCELLED",
                totalFiles = 0,
                restoredFiles = 0,
                totalBytes = 0,
                restoredBytes = 0,
                errorMessage = "Cancelled",
            ),
        )
    }

    /**
     * Launch the Android Storage Access Framework folder picker.
     * Result is pushed to JavaScript via KopiaEvents.onDestinationPicked.
     */
    @JavascriptInterface
    fun pickRestoreDestination() {
        val act = activity ?: return
        val key = "kopia_pick_${UUID.randomUUID()}"
        act.runOnUiThread {
            var launcher: androidx.activity.result.ActivityResultLauncher<Uri?>? = null
            launcher = act.activityResultRegistry.register(
                key,
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                launcher?.unregister()
                val result = if (uri == null) {
                    WebSafPickResult(uri = null, displayName = null)
                } else {
                    WebSafPickResult(
                        uri = uri.toString(),
                        displayName = uri.lastPathSegment,
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
    fun persistUriPermission(requestJson: String): String = try {
        val ctx = context ?: error("Context not available")
        val request = json.decodeFromString<WebPersistUriRequest>(requestJson)
        val flags = (if (request.read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (request.write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        ctx.contentResolver.takePersistableUriPermission(Uri.parse(request.uri), flags)
        json.encodeToString(WebResult.success(Unit))
    } catch (e: Exception) {
        json.encodeToString(WebResult.error<Unit>(e.message ?: "Failed to persist permission"))
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

    // Note: there is deliberately no getStoredPassword bridge method. The stored password is never
    // returned to JavaScript — connect() resolves it natively when the UI sends an empty password
    // (see the resolution order there). This keeps repository passwords off the JS surface, so a
    // compromised/injected bundle can't read them back. JS only learns whether one exists
    // (hasStoredPassword) so the UI can show a "using saved password" state.

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
     * Generate a stable connection ID from repository configuration, used as the key for
     * storing/retrieving passwords. Uses a SHA-256 digest of the canonical config rather than
     * `String.hashCode()`: a 32-bit hashCode collides in practice, and here a collision would return
     * or clobber the WRONG repository's stored password.
     */
    private fun generateConnectionId(config: WebConnectionConfig): String {
        val parts = when (config.storageType) {
            "LOCAL_FILESYSTEM" ->
                listOf("local", config.local?.path.orEmpty())
            "S3" ->
                listOf("s3", config.s3?.bucket.orEmpty(), config.s3?.endpoint.orEmpty())
            "WEBDAV" ->
                listOf("webdav", config.webdav?.url.orEmpty(), config.webdav?.username.orEmpty())
            "SFTP" ->
                // Include username + path: two repos on the same host:port are distinct credentials.
                listOf(
                    "sftp",
                    config.sftp?.host.orEmpty(),
                    config.sftp?.port?.toString().orEmpty(),
                    config.sftp?.username.orEmpty(),
                    config.sftp?.path.orEmpty(),
                )
            "SAF" ->
                listOf("saf", config.saf?.treeUri.orEmpty())
            else ->
                listOf("unknown", config.storageType)
        }
        val canonical = parts.joinToString(":")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Push restore progress to JavaScript.
     */
    private fun pushRestoreProgress(progress: WebRestoreProgress) {
        val jsonStr = json.encodeToString(progress)
        webViewRef.get()?.post {
            webViewRef.get()?.evaluateJavascript(
                "window.KopiaEvents?.onRestoreProgress?.($jsonStr);",
                null,
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
                null,
            )
        }
    }

    /**
     * Notify JavaScript when the Android system theme changes.
     * Called by the Activity when it detects a configuration change.
     */
    fun notifySystemThemeChanged() {
        val uiMode = (context?.resources?.configuration?.uiMode ?: 0) and Configuration.UI_MODE_NIGHT_MASK
        val theme = when (uiMode) {
            Configuration.UI_MODE_NIGHT_YES -> "dark"
            Configuration.UI_MODE_NIGHT_NO -> "light"
            else -> "light"
        }
        val jsonStr = json.encodeToString(theme)
        webViewRef.get()?.post {
            webViewRef.get()?.evaluateJavascript(
                "window.KopiaEvents?.onSystemThemeChanged?.($jsonStr);",
                null,
            )
        }
    }

    // ================================================================
    // Source Management Methods
    // ================================================================

    /**
     * List all configured backup sources with status info.
     * Returns WebSourceStatus[] matching the React UI's expected structure.
     * @return JSON-encoded WebResult<List<WebSourceStatus>>
     */
    @JavascriptInterface
    fun listAllSources(): String = try {
        val sources = sourceManager.listSources()
        json.encodeToString(WebResult.success(sources.map { it.toWebStatus() }))
    } catch (e: Exception) {
        json.encodeToString(WebResult.error<List<WebSourceStatus>>(e.message ?: "Error listing sources"))
    }

    /**
     * Create a new backup source.
     * @param requestJson JSON-encoded WebCreateSourceRequest
     * @return JSON-encoded WebResult<WebBackupSourceInfo>
     */
    @JavascriptInterface
    fun createSource(requestJson: String): String {
        return try {
            val request = json.decodeFromString<WebCreateSourceRequest>(requestJson)
            // Apply the wizard's policy (schedule/compression/exclusions) BEFORE creating the in-memory
            // source record. setPolicy does real repo I/O and can fail (not connected, read-only, write
            // error); sourceManager.createSource is a pure in-memory op that cannot. Doing the fallible
            // step first means a failure leaves no orphaned source with a missing policy. Store it under
            // the SAME SourceInfo the policy editor resolves (localSnapshotSourceInfo) so it persists and
            // shows up when the user opens the editor for this source.
            request.policy?.let { policy ->
                val repo = repositoryManager.getRepository()
                    ?: return json.encodeToString(
                        WebResult.error<WebBackupSourceInfo>(
                            "Repository not connected; cannot save the source policy",
                        ),
                    )
                runBlocking {
                    PolicyManager.setPolicy(repo, localSnapshotSourceInfo(request.path), policy)
                }
            }
            val source = sourceManager.createSource(request.path, request.displayName)
            json.encodeToString(WebResult.success(source.toWeb()))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<WebBackupSourceInfo>(e.message ?: "Error creating source"))
        }
    }

    /**
     * Delete a backup source by ID.
     * @param sourceId The source ID to delete
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun deleteSource(sourceId: String): String {
        return try {
            val existing = sourceManager.getSource(sourceId)
                ?: return json.encodeToString(
                    WebResult.error<Boolean>("Source not found: $sourceId"),
                )
            sourceManager.deleteSource(sourceId)
            json.encodeToString(WebResult.success(true))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<Boolean>(e.message ?: "Error deleting source"))
        }
    }

    /**
     * Get the current status of a backup source.
     * @param sourceId The source ID
     * @return JSON-encoded WebResult<WebSourceStatus>
     */
    @JavascriptInterface
    fun getSourceStatus(sourceId: String): String {
        return try {
            val source = sourceManager.getSource(sourceId)
                ?: return json.encodeToString(
                    WebResult.error<WebSourceStatus>("Source not found: $sourceId"),
                )
            json.encodeToString(WebResult.success(source.toWebStatus()))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<WebSourceStatus>(e.message ?: "Error getting source status"))
        }
    }

    /**
     * Pause a backup source.
     * @param sourceId The source ID to pause
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun pauseSource(sourceId: String): String {
        return try {
            val existing = sourceManager.getSource(sourceId)
                ?: return json.encodeToString(
                    WebResult.error<Boolean>("Source not found: $sourceId"),
                )
            sourceManager.pauseSource(sourceId)
            json.encodeToString(WebResult.success(true))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<Boolean>(e.message ?: "Error pausing source"))
        }
    }

    /**
     * Resume a paused backup source.
     * @param sourceId The source ID to resume
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun resumeSource(sourceId: String): String {
        return try {
            val existing = sourceManager.getSource(sourceId)
                ?: return json.encodeToString(
                    WebResult.error<Boolean>("Source not found: $sourceId"),
                )
            sourceManager.resumeSource(sourceId)
            json.encodeToString(WebResult.success(true))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<Boolean>(e.message ?: "Error resuming source"))
        }
    }

    // ================================================================
    // Backup Operations Methods
    // ================================================================

    /**
     * Start an estimation task for a backup source.
     * @param requestJson JSON-encoded WebEstimateBackupRequest
     * @return JSON-encoded WebResult<String> containing the task ID
     */
    @JavascriptInterface
    fun estimateBackup(requestJson: String): String = json.encodeToString(
        WebResult.error<String>("Backup estimation is not yet implemented"),
    )

    /**
     * Start a backup for the given source.
     * @param sourceId The source ID to back up
     * @return JSON-encoded WebResult<String> containing the task ID
     */
    @JavascriptInterface
    fun startBackup(sourceId: String): String = json.encodeToString(
        WebResult.error<String>("Backup execution is not yet implemented"),
    )

    /**
     * Cancel a running backup by task ID.
     * @param taskId The task ID to cancel
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun cancelBackup(taskId: String): String {
        return try {
            val task = taskManager.getTask(taskId)
                ?: return json.encodeToString(
                    WebResult.error<Boolean>("Task not found: $taskId"),
                )
            taskManager.cancelTask(taskId)
            json.encodeToString(WebResult.success(true))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<Boolean>(e.message ?: "Error cancelling backup"))
        }
    }

    // ================================================================
    // Task Management Methods
    // ================================================================

    /**
     * List all tracked tasks.
     * @return JSON-encoded WebResult<List<WebTaskInfo>>
     */
    @JavascriptInterface
    fun listTasks(): String = try {
        val tasks = taskManager.listTasks()
        json.encodeToString(WebResult.success(tasks.map { it.toWeb() }))
    } catch (e: Exception) {
        json.encodeToString(WebResult.error<List<WebTaskInfo>>(e.message ?: "Error listing tasks"))
    }

    /**
     * Get details of a specific task.
     * @param taskId The task ID
     * @return JSON-encoded WebResult<WebTaskInfo?>
     */
    @JavascriptInterface
    fun getTask(taskId: String): String = try {
        val task = taskManager.getTask(taskId)
        json.encodeToString(WebResult.success(task?.toWeb()))
    } catch (e: Exception) {
        json.encodeToString(WebResult.error<WebTaskInfo?>(e.message ?: "Error getting task"))
    }

    /**
     * Get log entries for a specific task.
     * @param taskId The task ID
     * @return JSON-encoded WebResult<List<WebTaskLogEntry>>
     */
    @JavascriptInterface
    fun getTaskLogs(taskId: String): String = json.encodeToString(
        WebResult.error<List<WebTaskLogEntry>>("Task log storage is not yet implemented"),
    )

    /**
     * Cancel a running task.
     * @param taskId The task ID to cancel
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun cancelTask(taskId: String): String {
        return try {
            val task = taskManager.getTask(taskId)
                ?: return json.encodeToString(
                    WebResult.error<Boolean>("Task not found: $taskId"),
                )
            taskManager.cancelTask(taskId)
            json.encodeToString(WebResult.success(true))
        } catch (e: Exception) {
            json.encodeToString(WebResult.error<Boolean>(e.message ?: "Error cancelling task"))
        }
    }

    // ================================================================
    // Policy Management Methods
    // ================================================================

    /**
     * Get the policy for a source.
     * @param requestJson JSON-encoded WebPolicySourceRequest
     * @return JSON-encoded WebResult<Policy?>
     */
    @JavascriptInterface
    fun getPolicy(requestJson: String): String {
        return runBlocking {
            try {
                val repo = repositoryManager.getRepository()
                    ?: return@runBlocking json.encodeToString(
                        WebResult.error<org.kopiaKt.snapshot.policy.Policy?>("Repository not connected"),
                    )
                val request = json.decodeFromString<WebPolicySourceRequest>(requestJson)
                val sourceInfo = request.toSnapshotSourceInfo()
                val policy = PolicyManager.getPolicy(repo, sourceInfo)
                json.encodeToString(WebResult.success(policy))
            } catch (e: Exception) {
                json.encodeToString(
                    WebResult.error<org.kopiaKt.snapshot.policy.Policy?>(e.message ?: "Error getting policy"),
                )
            }
        }
    }

    /**
     * Delete the policy for a source.
     * @param requestJson JSON-encoded WebPolicySourceRequest
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun deletePolicy(requestJson: String): String {
        return runBlocking {
            try {
                val repo = repositoryManager.getRepository()
                    ?: return@runBlocking json.encodeToString(
                        WebResult.error<Boolean>("Repository not connected"),
                    )
                val request = json.decodeFromString<WebPolicySourceRequest>(requestJson)
                val sourceInfo = request.toSnapshotSourceInfo()
                PolicyManager.deletePolicy(repo, sourceInfo)
                json.encodeToString(WebResult.success(true))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<Boolean>(e.message ?: "Error deleting policy"))
            }
        }
    }

    /**
     * List all policies in the repository.
     * @return JSON-encoded WebResult<List<WebPolicyListEntry>>
     */
    @JavascriptInterface
    fun listPolicies(): String {
        return runBlocking {
            try {
                val repo = repositoryManager.getRepository()
                    ?: return@runBlocking json.encodeToString(
                        WebResult.error<List<WebPolicyListEntry>>("Repository not connected"),
                    )
                val policies = PolicyManager.listPolicies(repo)
                val entries = policies.map { twp ->
                    WebPolicyListEntry(
                        source = twp.target.toWeb(),
                        policy = twp.policy,
                    )
                }
                json.encodeToString(WebResult.success(entries))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<List<WebPolicyListEntry>>(e.message ?: "Error listing policies"))
            }
        }
    }

    /**
     * Resolve the effective and defined policy for a source.
     * @param requestJson JSON-encoded WebPolicySourceRequest
     * @return JSON-encoded WebResult<WebResolvedPolicy>
     */
    @JavascriptInterface
    fun resolvePolicy(requestJson: String): String {
        return runBlocking {
            try {
                val repo = repositoryManager.getRepository()
                    ?: return@runBlocking json.encodeToString(
                        WebResult.error<WebResolvedPolicy>("Repository not connected"),
                    )
                val request = json.decodeFromString<WebPolicySourceRequest>(requestJson)
                val sourceInfo = request.toSnapshotSourceInfo()
                val effective = PolicyManager.getEffectivePolicy(repo, sourceInfo)
                val defined = PolicyManager.getPolicy(repo, sourceInfo)
                json.encodeToString(
                    WebResult.success(
                        WebResolvedPolicy(
                            effective = effective,
                            defined = defined,
                            upcomingSnapshotTimes = emptyList(),
                        ),
                    ),
                )
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<WebResolvedPolicy>(e.message ?: "Error resolving policy"))
            }
        }
    }

    /**
     * Set a policy for a source.
     * @param requestJson JSON-encoded WebSetPolicyRequest
     * @return JSON-encoded WebResult<Boolean>
     */
    @JavascriptInterface
    fun setPolicy(requestJson: String): String {
        return runBlocking {
            try {
                val repo = repositoryManager.getRepository()
                    ?: return@runBlocking json.encodeToString(
                        WebResult.error<Boolean>("Repository not connected"),
                    )
                val request = json.decodeFromString<WebSetPolicyRequest>(requestJson)
                val sourceInfo = request.source.toSnapshotSourceInfo()
                PolicyManager.setPolicy(repo, sourceInfo, request.policy)
                json.encodeToString(WebResult.success(true))
            } catch (e: Exception) {
                json.encodeToString(WebResult.error<Boolean>(e.message ?: "Error setting policy"))
            }
        }
    }

    // ================================================================
    // Maintenance Methods
    // ================================================================

    /**
     * Trigger a maintenance operation.
     * @param mode Maintenance mode (e.g. "QUICK", "FULL")
     * @return JSON-encoded WebResult<String> containing the task ID
     */
    @JavascriptInterface
    fun triggerMaintenance(mode: String): String = json.encodeToString(
        WebResult.error<String>("Maintenance is not yet implemented"),
    )

    /**
     * Get the current maintenance status based on task history.
     * @return JSON-encoded WebResult<WebMaintenanceStatus>
     */
    @JavascriptInterface
    fun getMaintenanceStatus(): String = try {
        val maintenanceTasks = taskManager.listTasks()
            .filter { it.kind == TaskKind.MAINTENANCE }
            .sortedByDescending { it.startTime }
        val lastTask = maintenanceTasks.firstOrNull()
        val status = if (lastTask != null) {
            WebMaintenanceStatus(
                lastRunTimeEpochMs = lastTask.startTime.toEpochMilli(),
                lastMode = lastTask.description.substringBefore(" maintenance", "QUICK"),
                lastSuccess = lastTask.status == org.kopiaKt.android.worker.TaskStatus.SUCCESS,
                lastError = lastTask.errorMessage,
            )
        } else {
            WebMaintenanceStatus()
        }
        json.encodeToString(WebResult.success(status))
    } catch (e: Exception) {
        json.encodeToString(WebResult.error<WebMaintenanceStatus>(e.message ?: "Error getting maintenance status"))
    }

    /**
     * Clean up resources when the bridge is no longer needed.
     */
    fun cleanup() {
        restoreJob?.cancel()
        scope.cancel()
    }
}

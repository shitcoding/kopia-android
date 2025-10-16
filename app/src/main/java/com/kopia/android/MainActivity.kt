package com.kopia.android

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.io.IOException
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.kopia.android.databinding.ActivityMainBinding
import com.kopia.android.repository.RepositoryManager
import com.kopia.android.repository.RepositorySelectionActivity
import com.kopia.android.service.KopiaServerService
import com.kopia.android.settings.SettingsActivity
import com.kopia.android.util.ErrorHandlingUtility
import com.kopia.android.util.FileUtils
import com.kopia.android.util.OptimizationUtility
import com.kopia.android.util.ProgressManager
import com.kopia.android.util.WebViewJavaScriptInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var progressManager: ProgressManager
    private lateinit var repositoryManager: RepositoryManager
    private lateinit var optimizationUtility: OptimizationUtility
    private var lastTriedUiPathRoot = true
    private lateinit var errorHandler: ErrorHandlingUtility
    private lateinit var jsInterface: WebViewJavaScriptInterface

    private var serverPort = 51515
    private var serverUrl = "http://127.0.0.1:$serverPort/"
    private var repositoryUri: Uri? = null
    private var autoStartServer = true

    // Track current file picker request from WebView
    private var currentPickerRequestId: String? = null

    // Shared preferences for storing settings
    private val sharedPrefs by lazy {
        getSharedPreferences("kopia_settings", Context.MODE_PRIVATE)
    }

    private fun loadKopiaUi(baseUrl: String, preferRoot: Boolean) {
        val creds = android.util.Base64.encodeToString("admin:admin".toByteArray(), android.util.Base64.NO_WRAP)
        val headers = mapOf("Authorization" to "Basic $creds")
        val target = if (preferRoot) baseUrl else (if (baseUrl.endsWith("/")) baseUrl + "ui/" else baseUrl + "/ui/")
        Log.d("MainActivity", "Loading Kopia UI target=$target preferRoot=$preferRoot")
        binding.webView.loadUrl(target, headers)
    }

    private fun ensureCanStartForegroundService() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val hasPermission =
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        lifecycleScope.launch {
            val ok = extractKopiaBinary()
            if (ok) {
                startKopiaServer()
            } else {
                binding.progressBar.visibility = View.GONE
                binding.statusText.text = "Kopia binary not found. Add it to assets and retry."
                binding.selectRepositoryButton.visibility = View.VISIBLE
            }
        }
    }

    // Register for repository directory selection result
    private val selectRepositoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val tree = DocumentFile.fromTreeUri(this, it)
            // Some top-level collections (e.g., Downloads root) are not selectable; require subfolder
            if (tree == null || !tree.canWrite()) {
                progressManager.showToast("This folder can't be used. Please create or choose a subfolder.")
                // Reopen picker to let user create/select a subfolder
                launchRepositoryPicker()
                return@registerForActivityResult
            }

            // Persist permission to access this directory
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            repositoryUri = it
            setupRepository(it)
        }
    }
    
    // Register for restore location selection result
    private val selectRestoreLocationLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Persist permission to access this directory
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Handle restore operation with the selected location
            // This would be implemented based on user selection of snapshot
        }
    }

    // Register for WebView file picker requests
    private val webViewFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val requestId = currentPickerRequestId
        if (requestId != null) {
            if (uri != null) {
                // Persist permission to access this directory
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                // Convert content URI to a real path if possible
                val path = FileUtils.getPathFromUri(this, uri) ?: uri.toString()
                jsInterface.sendPathToWebView(requestId, path)
            } else {
                // User cancelled
                jsInterface.sendPathToWebView(requestId, null)
            }
            currentPickerRequestId = null
        }
    }

    // Runtime permission launcher for notifications (Android 13+)
    private val requestNotifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                lifecycleScope.launch {
                    val ok = extractKopiaBinary()
                    if (ok) startKopiaServer() else {
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = "Kopia binary not found. Add it to assets and retry."
                        binding.selectRepositoryButton.visibility = View.VISIBLE
                    }
                }
            } else {
                binding.progressBar.visibility = View.GONE
                binding.statusText.text = "Notifications permission required to run server"
                binding.selectRepositoryButton.visibility = View.VISIBLE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if repository is configured
        // If not, redirect to RepositorySelectionActivity
        if (!isRepositoryConfigured()) {
            val intent = Intent(this, RepositorySelectionActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        progressManager = ProgressManager(this)
        repositoryManager = RepositoryManager(this)
        optimizationUtility = OptimizationUtility.getInstance(this)
        errorHandler = ErrorHandlingUtility.getInstance(this)

        // Apply performance optimizations
        optimizationUtility.schedulePeriodicOptimizations()

        // Load settings
        loadSettings()
        
        // Set up WebView
        setupWebView()

        // Set up UI elements
        binding.selectRepositoryButton.setOnClickListener { launchRepositoryPicker() }

        // Extract Kopia binary and start the server service if auto-start is enabled
        if (autoStartServer) {
            ensureCanStartForegroundService()
        } else {
            binding.progressBar.visibility = View.GONE
            binding.statusText.text = "Server auto-start disabled"
            binding.selectRepositoryButton.visibility = View.VISIBLE
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_repository -> {
                changeRepository()
                true
            }
            R.id.action_settings -> {
                SettingsActivity.start(this)
                true
            }
            R.id.action_restart_server -> {
                restartServer()
                true
            }
            R.id.action_diagnostic -> {
                startDiagnosticActivity()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun isRepositoryConfigured(): Boolean {
        val tempPrefs = getSharedPreferences("kopia_settings", Context.MODE_PRIVATE)
        return tempPrefs.getBoolean("repository_configured", false)
    }

    private fun loadSettings() {
        // Load server settings
        serverPort = sharedPrefs.getString("server_port", "51515")?.toIntOrNull() ?: 51515
        serverUrl = "http://127.0.0.1:$serverPort/"
        autoStartServer = sharedPrefs.getBoolean("auto_start", true)

        // Load repository settings
        val repoUriString = sharedPrefs.getString("repository_uri", null)
        if (repoUriString != null) {
            try {
                repositoryUri = Uri.parse(repoUriString)
            } catch (e: Exception) {
                progressManager.handleError(e, "Invalid repository URI")
            }
        }
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        // Enable cookies/session for Kopia UI
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                cm.setAcceptThirdPartyCookies(binding.webView, true)
            }
        } catch (_: Throwable) { }

        // Apply WebView optimizations
        optimizationUtility.optimizeWebView(binding.webView)

        // Initialize JavaScript interface for native Android features
        jsInterface = WebViewJavaScriptInterface(
            context = this,
            webView = binding.webView,
            filePickerCallback = { request ->
                currentPickerRequestId = request.requestId
                when (request.type) {
                    WebViewJavaScriptInterface.FilePickerType.DIRECTORY,
                    WebViewJavaScriptInterface.FilePickerType.SNAPSHOT_SOURCE -> {
                        webViewFilePickerLauncher.launch(null)
                    }
                    WebViewJavaScriptInterface.FilePickerType.FILE -> {
                        // For now, use directory picker for files as well
                        // Can be enhanced with OpenDocument launcher later
                        webViewFilePickerLauncher.launch(null)
                    }
                }
            }
        )
        binding.webView.addJavascriptInterface(jsInterface, "KopiaAndroid")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                binding.statusText.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE

                // Inject JavaScript to enable file picker integration
                injectFilePickerJavaScript()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                Log.w("MainActivity", "WebView HTTP error ${errorResponse.statusCode} for ${request.url}")
                // If the main frame returned 404 for one UI path, try the other path automatically
                if (request.isForMainFrame && errorResponse.statusCode == 404) {
                    serverUrl?.let { base ->
                        // flip path
                        lastTriedUiPathRoot = !lastTriedUiPathRoot
                        loadKopiaUi(base, lastTriedUiPathRoot)
                    }
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }

            // Handle HTTP Basic Auth challenge from Kopia server (dev defaults)
            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: android.webkit.HttpAuthHandler,
                host: String,
                realm: String
            ) {
                // Only auto-provide creds for our localhost server
                if (host == "127.0.0.1" || host == "localhost") {
                    handler.proceed("admin", "admin")
                } else {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm)
                }
            }
        }
    }

    private fun injectFilePickerJavaScript() {
        val jsCode = """
            (function() {
                // Skip injection if already injected
                if (window.KopiaAndroidInjected) return;
                window.KopiaAndroidInjected = true;

                // Store callbacks for path selection
                window.KopiaAndroid = window.KopiaAndroid || {};
                window.KopiaAndroid.pendingRequests = {};

                // Callback from Android when path is selected
                window.KopiaAndroid.onPathSelected = function(requestId, path) {
                    console.log('Path selected:', requestId, path);
                    const request = window.KopiaAndroid.pendingRequests[requestId];
                    if (request && request.inputElement) {
                        const input = request.inputElement;

                        // Set the value using React's descriptor if available
                        const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                            window.HTMLInputElement.prototype,
                            'value'
                        ).set;

                        if (nativeInputValueSetter) {
                            nativeInputValueSetter.call(input, path);
                        } else {
                            input.value = path;
                        }

                        // Trigger events that React listens to
                        const inputEvent = new Event('input', { bubbles: true });
                        const changeEvent = new Event('change', { bubbles: true });

                        // Dispatch events
                        input.dispatchEvent(inputEvent);
                        input.dispatchEvent(changeEvent);

                        // Also trigger focus/blur to ensure React updates
                        input.focus();
                        input.blur();

                        console.log('Path set to input field:', path);
                    }
                    delete window.KopiaAndroid.pendingRequests[requestId];
                };

                // Callback from Android when picker is cancelled
                window.KopiaAndroid.onPathCancelled = function(requestId) {
                    console.log('Path selection cancelled:', requestId);
                    delete window.KopiaAndroid.pendingRequests[requestId];
                };

                // Helper to add file picker button next to input fields
                function addFilePickerButton(input) {
                    // Skip if already enhanced
                    if (input.dataset.kopiaAndroidEnhanced) return;
                    input.dataset.kopiaAndroidEnhanced = 'true';

                    // Create a folder icon button
                    const button = document.createElement('button');
                    button.innerHTML = '\uD83D\uDCC2'; // Folder emoji
                    button.type = 'button';
                    button.style.cssText = 'margin-left: 4px; padding: 4px 8px; cursor: pointer;';
                    button.title = 'Choose directory';

                    button.onclick = function(e) {
                        e.preventDefault();
                        const requestId = 'picker_' + Date.now();
                        window.KopiaAndroid.pendingRequests[requestId] = { inputElement: input };

                        // Determine picker type based on input attributes
                        let pickerType = 'directory';
                        const name = input.name || input.id || '';
                        if (name.includes('snapshot') || name.includes('source')) {
                            pickerType = 'snapshot_source';
                        }

                        // Call Android native file picker
                        KopiaAndroid.openFilePicker(requestId, pickerType, input.value || null);
                    };

                    // Insert button after the input
                    input.parentNode.insertBefore(button, input.nextSibling);
                }

                // Look for path-related input fields and enhance them
                function enhancePathInputs() {
                    // Look for all input fields, including those without explicit type
                    const inputs = document.querySelectorAll('input[type="text"], input:not([type]), input[type=""]');
                    console.log('Kopia Android: Found ' + inputs.length + ' input fields to check');

                    let enhanced = 0;
                    inputs.forEach(input => {
                        // Get context from multiple sources
                        const label = input.labels && input.labels[0] ? input.labels[0].textContent : '';
                        const name = input.name || input.id || '';
                        const placeholder = input.placeholder || '';
                        const ariaLabel = input.getAttribute('aria-label') || '';
                        const value = input.value || '';

                        // Also check parent elements for context
                        const parentText = input.parentElement ? input.parentElement.textContent.substring(0, 100) : '';

                        // Check if this looks like a path input
                        const pathKeywords = ['path', 'directory', 'folder', 'location', 'repository', 'snapshot', 'config', 'file', 'source', 'destination', 'target'];
                        const searchText = (label + name + placeholder + ariaLabel + parentText).toLowerCase();

                        const hasPathKeyword = pathKeywords.some(keyword => searchText.includes(keyword));
                        const looksLikePath = value.includes('/') || value.includes('\\');

                        if (hasPathKeyword || looksLikePath) {
                            console.log('Kopia Android: Enhancing input - name:', name, 'placeholder:', placeholder, 'label:', label);
                            addFilePickerButton(input);
                            enhanced++;
                        }
                    });
                    console.log('Kopia Android: Enhanced ' + enhanced + ' input fields');
                }

                // Run enhancement on page load
                enhancePathInputs();

                // Re-run after a delay for React apps
                setTimeout(enhancePathInputs, 500);
                setTimeout(enhancePathInputs, 1000);
                setTimeout(enhancePathInputs, 2000);

                // Re-run when DOM changes (for React/dynamic content)
                let enhanceTimeout = null;
                const observer = new MutationObserver(() => {
                    // Debounce to avoid running too frequently
                    if (enhanceTimeout) clearTimeout(enhanceTimeout);
                    enhanceTimeout = setTimeout(enhancePathInputs, 300);
                });

                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });

                console.log('Kopia Android file picker integration ready');
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(jsCode, null)
    }

    private suspend fun extractKopiaBinary(): Boolean {
        return withContext(Dispatchers.IO) {
            val kopiaFile = File(filesDir, "kopia")
            try {
                // If binary already exists (e.g., previously extracted or pushed), accept it
                if (kopiaFile.exists()) {
                    kopiaFile.setExecutable(true)
                    Log.d("MainActivity", "Kopia binary already present at ${kopiaFile.absolutePath}")
                    return@withContext true
                }

                // Attempt to copy from assets if bundled
                try {
                    assets.open("kopia").use { input ->
                        kopiaFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    kopiaFile.setExecutable(true)
                    Log.d("MainActivity", "Kopia binary extracted from assets")
                    true
                } catch (fnf: Exception) {
                    // Asset not bundled
                    Log.e("MainActivity", "Kopia asset not found and no existing binary", fnf)
                    false
                }
            } catch (e: Exception) {
                val errorId = errorHandler.logError(
                    throwable = e,
                    userMessage = "Failed to prepare Kopia binary",
                    severity = ErrorHandlingUtility.ErrorSeverity.CRITICAL
                )
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, "Kopia binary missing", android.widget.Toast.LENGTH_LONG).show()
                    if (!errorHandler.attemptRecovery(errorId)) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Binary Preparation Failed")
                            .setMessage("Could not prepare the Kopia binary. Ensure it is bundled in assets or present in app internal storage.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                false
            }
        }
    }

    private fun launchRepositoryPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                // Hint to show advanced locations and Documents; not all devices honor these
                putExtra("android.content.extra.SHOW_ADVANCED", true)
                putExtra("android.provider.extra.INITIAL_URI", android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            selectRepositoryLauncher.launch(null)
        } catch (_: Exception) {
            // Fallback without extras
            selectRepositoryLauncher.launch(null)
        }
    }

    private suspend fun startKopiaServer() {
        progressManager.showProgress(getString(R.string.server_starting))
        Log.d("MainActivity", "startKopiaServer(): preparing intent for KopiaServerService on port $serverPort repoUri=${repositoryUri}")
        
        // Start the Kopia server service
        val serviceIntent = Intent(this, KopiaServerService::class.java).apply {
            putExtra("SERVER_PORT", serverPort)
            if (repositoryUri != null) {
                putExtra("REPOSITORY_URI", repositoryUri.toString())
            }
        }
        Log.d("MainActivity", "Calling startForegroundService(...) for KopiaServerService")
        startForegroundService(serviceIntent)
        Log.d("MainActivity", "Returned from startForegroundService(...) call")
        
        // Wait for the server to start and check if it's running
        Log.d("MainActivity", "Entering waitForServerToStart()")
        waitForServerToStart()
    }
    
    private fun restartServer() {
        lifecycleScope.launch {
            // Stop the current server
            stopService(Intent(this@MainActivity, KopiaServerService::class.java))
            
            // Wait a bit to ensure the server is stopped
            delay(1000)
            
            // Start the server again
            startKopiaServer()
        }
    }

    private suspend fun waitForServerToStart() {
        withContext(Dispatchers.IO) {
            var serverRunning = false
            var attempts = 0
            var lastError: Exception? = null
            val maxAttempts = 20  // Reduced from 40 for faster failure (~10s max instead of ~28s)

            while (!serverRunning && attempts < maxAttempts) {
                try {
                    val url = URL(serverUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 500  // Reduced from 1000ms
                    connection.readTimeout = 500     // Reduced from 1000ms
                    connection.requestMethod = "HEAD"

                    val responseCode = connection.responseCode
                    // Consider server 'up' if it responds with OK/redirect or auth required
                    serverRunning = when (responseCode) {
                        in 200..299, in 300..399, 401, 403 -> true
                        else -> false
                    }
                    Log.d("MainActivity", "Probe attempt=$attempts response=$responseCode")

                    connection.disconnect()
                } catch (e: Exception) {
                    // Only log every 5th attempt to reduce log spam
                    if (attempts % 5 == 0) {
                        Log.d("MainActivity", "Server not ready yet (attempt $attempts): ${e.message}")
                    }
                    lastError = e
                }

                attempts++
                // Use adaptive delay: faster checks initially, slower later
                // First 10 attempts: 300ms (total 3s)
                // Next 10 attempts: 500ms (total 5s)
                // Remaining attempts: 1000ms (total 20s)
                val delayMs = when {
                    attempts < 10 -> 300L
                    attempts < 20 -> 500L
                    else -> 1000L
                }
                delay(delayMs)
            }
            
            withContext(Dispatchers.Main) {
                if (serverRunning) {
                    Log.i("MainActivity", "Kopia server is up at $serverUrl, loading WebView UI")
                    // Clear any cached 401 text response and seed HTTP Basic credentials
                    binding.webView.clearCache(true)
                    binding.webView.clearHistory()
                    binding.webView.setHttpAuthUsernamePassword("127.0.0.1", "", "admin", "admin")
                    binding.webView.setHttpAuthUsernamePassword("localhost", "", "admin", "admin")
                    lastTriedUiPathRoot = true
                    loadKopiaUi(serverUrl, preferRoot = true)
                    binding.progressBar.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                } else {
                    Log.e("MainActivity", "Timed out waiting for Kopia server to start after $attempts attempts")

                    // Check if we have a specific error message from the server
                    val statusManager = com.kopia.android.service.ServerStatusManager.getInstance(this@MainActivity)
                    val errorMessage = statusManager.errorMessage.value

                    // Use error handling utility for better error reporting
                    val errorId = errorHandler.logError(
                        throwable = lastError ?: Exception("Timeout waiting for server"),
                        userMessage = "Failed to start Kopia server",
                        severity = ErrorHandlingUtility.ErrorSeverity.ERROR
                    )

                    // Determine specific error message and action
                    val isPasswordError = errorMessage?.contains("password", ignoreCase = true) == true

                    val (title, message, positiveButtonText) = if (errorMessage != null) {
                        when {
                            isPasswordError -> {
                                Triple(
                                    "Incorrect Password",
                                    "The repository password you entered is incorrect. Would you like to try again?",
                                    "Retry"
                                )
                            }
                            else -> {
                                Triple(
                                    "Server Error",
                                    "$errorMessage\n\nWould you like to change the repository and try again?",
                                    "Change Repository"
                                )
                            }
                        }
                    } else {
                        Triple(
                            "Server Start Failed",
                            "Could not connect to the Kopia server. This may be due to an incorrect repository password or a configuration issue. Would you like to change the repository and try again?",
                            "Change Repository"
                        )
                    }

                    // Show error with recovery option
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(positiveButtonText) { _, _ ->
                            // Clear the error before navigating away
                            statusManager.clearError()

                            if (isPasswordError) {
                                // Retry: Go back to password entry for the same repository
                                retryPasswordEntry()
                            } else {
                                // Change repository
                                changeRepository()
                            }
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            // Clear the error and navigate to repository selection
                            statusManager.clearError()

                            lifecycleScope.launch(Dispatchers.IO) {
                                // Stop the server
                                withContext(Dispatchers.Main) {
                                    stopService(Intent(this@MainActivity, KopiaServerService::class.java))
                                }

                                delay(500)

                                withContext(Dispatchers.Main) {
                                    // Navigate to repository selection
                                    val intent = Intent(this@MainActivity, com.kopia.android.repository.RepositorySelectionActivity::class.java)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                }
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun setupRepository(uri: Uri) {
        lifecycleScope.launch {
            progressManager.showProgress("Setting up repository...")
            
            try {
                // Connect to the repository
                val result = repositoryManager.connectRepository(uri)
                
                result.fold(
                    onSuccess = { message ->
                        progressManager.hideProgress()
                        progressManager.showToast(message)
                        
                        // Restart the server with the repository path
                        restartServer()
                    },
                    onFailure = { error ->
                        progressManager.handleError(error, "Failed to set up repository")
                        binding.selectRepositoryButton.visibility = View.VISIBLE
                    }
                )
            } catch (e: Exception) {
                progressManager.handleError(e, "Failed to set up repository")
                binding.selectRepositoryButton.visibility = View.VISIBLE
            }
        }
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage("Kopia Android App\nVersion 1.0.0")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
    
    private fun startDiagnosticActivity() {
        // Start the diagnostic activity
        com.kopia.android.diagnostic.DiagnosticActivity.start(this)
    }

    private fun retryPasswordEntry() {
        // Navigate back to password entry screen with current repository
        lifecycleScope.launch(Dispatchers.IO) {
            // Stop the server first
            withContext(Dispatchers.Main) {
                stopService(Intent(this@MainActivity, KopiaServerService::class.java))
            }

            // Wait for server to stop
            delay(500)

            // Clear repository configuration from Kopia but keep the URI and path
            try {
                val configDir = File(filesDir, ".kopia")
                val repoConfig = File(configDir, "repository.config")
                if (repoConfig.exists()) {
                    repoConfig.delete()
                    Log.d("MainActivity", "Deleted old repository.config for retry")
                }
                // Also delete the password file so user can enter new one
                val passwordFile = File(configDir, "repository.config.kopia-password")
                if (passwordFile.exists()) {
                    passwordFile.delete()
                    Log.d("MainActivity", "Deleted password file for retry")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to delete repository config for retry", e)
            }

            withContext(Dispatchers.Main) {
                // Get the current repository URI and whether it's existing
                val repoUriString = sharedPrefs.getString("repository_uri", null)
                val isExisting = !sharedPrefs.getBoolean("repository_is_new", false)

                if (repoUriString != null) {
                    val repoUri = Uri.parse(repoUriString)
                    // Navigate to password entry for the same repository
                    val intent = Intent(this@MainActivity, com.kopia.android.repository.RepositoryConfigActivity::class.java).apply {
                        putExtra("repository_uri", repoUriString)
                        putExtra("is_existing", true)  // Always treat as existing when retrying
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    // Fallback: go to repository selection if we somehow lost the URI
                    val intent = Intent(this@MainActivity, com.kopia.android.repository.RepositorySelectionActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun changeRepository() {
        // Clear repository configuration and navigate to selection screen
        AlertDialog.Builder(this)
            .setTitle(R.string.change_repository)
            .setMessage("Are you sure you want to change the repository? This will disconnect from the current repository.")
            .setPositiveButton("Change") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    // Stop the server first
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@MainActivity, KopiaServerService::class.java))
                    }

                    // Wait for server to stop
                    delay(500)

                    // Clear repository configuration from Kopia
                    try {
                        val configDir = File(filesDir, ".kopia")
                        val repoConfig = File(configDir, "repository.config")
                        if (repoConfig.exists()) {
                            repoConfig.delete()
                            Log.d("MainActivity", "Deleted old repository.config")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to delete repository.config", e)
                    }

                    // Clear all repository-related preferences
                    sharedPrefs.edit().apply {
                        remove("repository_uri")
                        remove("repository_path")
                        remove("repository_name")
                        remove("repository_password")
                        remove("repository_is_new")
                        remove("repository_configured_timestamp")
                        putBoolean("repository_configured", false)
                        apply()
                    }

                    withContext(Dispatchers.Main) {
                        // Navigate to repository selection
                        val intent = Intent(this@MainActivity, com.kopia.android.repository.RepositorySelectionActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Reload settings in case they were changed
        loadSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the Kopia server service when the activity is destroyed
        stopService(Intent(this, KopiaServerService::class.java))
    }
}

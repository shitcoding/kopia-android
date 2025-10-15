package com.kopia.android.util

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * JavaScript interface for WebView to communicate with native Android features.
 * Allows the Kopia Web UI to trigger Android file pickers and other native dialogs.
 */
class WebViewJavaScriptInterface(
    private val context: Context,
    private val webView: WebView,
    private val filePickerCallback: (FilePickerRequest) -> Unit
) {

    private val TAG = "WebViewJSInterface"

    /**
     * Request to open Android file picker from JavaScript
     * @param requestId Unique ID to track this request
     * @param type Type of picker: "directory", "file", or "snapshot_source"
     * @param currentPath Current path value (optional)
     */
    @JavascriptInterface
    fun openFilePicker(requestId: String, type: String, currentPath: String?) {
        Log.d(TAG, "openFilePicker called: requestId=$requestId, type=$type, currentPath=$currentPath")

        val request = FilePickerRequest(
            requestId = requestId,
            type = FilePickerType.fromString(type),
            currentPath = currentPath
        )

        // Call the callback on the main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            filePickerCallback(request)
        }
    }

    /**
     * Called from native code after user selects a path
     * This sends the result back to JavaScript
     */
    fun sendPathToWebView(requestId: String, path: String?) {
        val jsCode = if (path != null) {
            """
            if (window.KopiaAndroid && window.KopiaAndroid.onPathSelected) {
                window.KopiaAndroid.onPathSelected('$requestId', '$path');
            }
            """.trimIndent()
        } else {
            """
            if (window.KopiaAndroid && window.KopiaAndroid.onPathCancelled) {
                window.KopiaAndroid.onPathCancelled('$requestId');
            }
            """.trimIndent()
        }

        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    /**
     * Get list of recommended repository locations
     */
    @JavascriptInterface
    fun getRecommendedPaths(): String {
        val locations = FilesystemPermissionManager.getAccessibleDirectories(context)

        // Convert to JSON array
        val json = locations.joinToString(",\n  ") { location ->
            """
            {
              "name": "${location.name}",
              "path": "${location.path}",
              "description": "${location.description.replace("\"", "\\\"")}",
              "alwaysAccessible": ${location.alwaysAccessible}
            }
            """.trimIndent()
        }

        return "[\n  $json\n]"
    }

    /**
     * Check if app has filesystem permissions
     */
    @JavascriptInterface
    fun hasFilesystemPermission(): Boolean {
        return FilesystemPermissionManager.hasFullFilesystemAccess(context)
    }

    /**
     * Request filesystem permission
     */
    @JavascriptInterface
    fun requestFilesystemPermission() {
        Log.d(TAG, "requestFilesystemPermission called from JavaScript")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (context is android.app.Activity) {
                FilesystemPermissionManager.requestFilesystemPermission(context)
            }
        }
    }

    /**
     * Log message from JavaScript to Android logcat
     */
    @JavascriptInterface
    fun log(level: String, message: String) {
        when (level.lowercase()) {
            "debug", "d" -> Log.d(TAG, "[JS] $message")
            "info", "i" -> Log.i(TAG, "[JS] $message")
            "warn", "w" -> Log.w(TAG, "[JS] $message")
            "error", "e" -> Log.e(TAG, "[JS] $message")
            else -> Log.d(TAG, "[JS] $message")
        }
    }

    /**
     * Data class for file picker requests
     */
    data class FilePickerRequest(
        val requestId: String,
        val type: FilePickerType,
        val currentPath: String?
    )

    /**
     * Type of file picker to show
     */
    enum class FilePickerType {
        DIRECTORY,       // Pick a directory for repository
        FILE,            // Pick a file
        SNAPSHOT_SOURCE; // Pick directory to snapshot

        companion object {
            fun fromString(type: String): FilePickerType {
                return when (type.lowercase()) {
                    "directory", "dir" -> DIRECTORY
                    "file" -> FILE
                    "snapshot_source", "snapshot" -> SNAPSHOT_SOURCE
                    else -> DIRECTORY
                }
            }
        }
    }
}

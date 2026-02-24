package org.kopiaKt.app.bridge

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.pm.ApplicationInfo

/**
 * Configures a WebView for the Kopia app with appropriate settings.
 * Enables JavaScript, DOM storage, and disables zoom controls for app-like behavior.
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureForKopia() {
    // Enable WebView debugging for debug builds (required for Maestro devtools hierarchy)
    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (isDebuggable) {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    settings.apply {
        // Enable JavaScript for React app
        javaScriptEnabled = true

        // Enable DOM storage for React state persistence
        domStorageEnabled = true

        // Allow file access for loading from assets
        allowFileAccess = true
        allowContentAccess = true

        // Allow file URLs to access other file URLs (needed for local React app)
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = true

        // App-like behavior - disable zoom
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false

        // Performance settings
        cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        databaseEnabled = true

        // Allow mixed content for development
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Use wide viewport for responsive design
        useWideViewPort = true
        loadWithOverviewMode = true
    }

    // Prevent links from opening in external browser
    webViewClient = WebViewClient()
}

package org.kopiaKt.app

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import org.kopiaKt.app.bridge.KopiaWebBridge
import org.kopiaKt.app.bridge.configureForKopia

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var webBridge: KopiaWebBridge? = null
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWebView()
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            // Configure WebView settings for app-like behavior
            configureForKopia()

            // Create and attach the JavaScript bridge
            webBridge = KopiaWebBridge(
                context = applicationContext,
                activity = this@MainActivity
            ).also { bridge ->
                addJavascriptInterface(bridge, "KopiaBridge")
                bridge.attachWebView(this)
            }

            // Load the React app from assets
            loadUrl("file:///android_asset/react/index.html")
        }

        setContentView(webView)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webBridge?.cleanup()
        webView?.destroy()
        super.onDestroy()
    }
}

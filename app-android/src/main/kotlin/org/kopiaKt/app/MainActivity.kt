package org.kopiaKt.app

import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import org.kopiaKt.app.bridge.KopiaWebBridge
import org.kopiaKt.app.bridge.configureForKopia

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var webBridge: KopiaWebBridge? = null
    private var webView: WebView? = null
    private var container: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Set initial light theme colors
        // Background color matches the React app's --background: 220 20% 97% (light grayish)
        val lightBgColor = Color.parseColor("#F7F8FA")
        window.statusBarColor = lightBgColor
        window.navigationBarColor = lightBgColor
        window.decorView.setBackgroundColor(lightBgColor)

        // Configure status bar appearance - dark icons on light background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true  // Dark icons for light background
            isAppearanceLightNavigationBars = true  // Dark icons for navigation bar
        }

        setupWebView()
    }

    private fun setupWebView() {
        // Create a container that will handle system bar insets
        // Background color matches the React app's --background: 220 20% 97% (light grayish)
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }

        webView = WebView(this).apply {
            // Configure WebView settings for app-like behavior
            configureForKopia()

            // Create and attach the JavaScript bridge
            webBridge = KopiaWebBridge(
                context = applicationContext,
                activity = this@MainActivity,
                containerView = container!!
            ).also { bridge ->
                addJavascriptInterface(bridge, "KopiaBridge")
                bridge.attachWebView(this)
            }

            // Load the React app from assets
            loadUrl("file:///android_asset/react/index.html")
        }

        container!!.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Apply window insets to the container for proper safe area handling
        ViewCompat.setOnApplyWindowInsetsListener(container!!) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = insets.top,
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right
            )
            WindowInsetsCompat.CONSUMED
        }

        setContentView(container)
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

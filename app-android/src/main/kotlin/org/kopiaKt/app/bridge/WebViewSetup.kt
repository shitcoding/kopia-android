package org.kopiaKt.app.bridge

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/** Virtual origin the app is served from (see [WebViewAssetLoader] default authority). */
const val APP_ASSETS_HOST = "appassets.androidplatform.net"

/**
 * Path of the single React document. It is the ONLY navigable in-app document — all routing is
 * hash-based (HashRouter), so navigation never changes this path. Sub-resources (js/css) load via
 * [WebViewAssetLoader] through `shouldInterceptRequest`, not as navigations.
 */
const val APP_PATH = "/assets/react/index.html"

/**
 * URL the React app is loaded from. The [WebViewAssetLoader.AssetsPathHandler] registered under
 * `/assets/` maps this to `android_asset/react/index.html`; because the Vite build uses relative
 * (`./assets/...`) references, all sub-resources resolve back through the same handler.
 */
const val APP_URL = "https://$APP_ASSETS_HOST$APP_PATH"

/**
 * Configures a WebView for the Kopia app with a hardened, app-controlled origin.
 *
 * The React bundle is served over an HTTPS virtual origin via [WebViewAssetLoader] instead of
 * `file://`, which lets us disable all file/universal-URL access while still loading local assets.
 * Combined with [KopiaWebViewClient] confining navigation to that origin and a CSP in the bundle's
 * `index.html`, this shrinks the blast radius of the native `@JavascriptInterface` bridge: content
 * executing in the WebView can no longer read cross-origin files or navigate off-origin.
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureForKopia() {
    // Enable WebView debugging for debug builds (required for Maestro devtools hierarchy)
    val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (isDebuggable) {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    settings.apply {
        // Enable JavaScript for React app
        javaScriptEnabled = true

        // Enable DOM storage for React state persistence
        domStorageEnabled = true

        // Lock down file/content access: assets are served by WebViewAssetLoader (via
        // shouldInterceptRequest), which reads them through AssetManager, not the WebView's
        // file:// access. Disabling these closes the cross-origin file-read hole.
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false

        // App-like behavior - disable zoom
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false

        // Performance settings
        cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        databaseEnabled = true

        // Never load insecure content on the HTTPS virtual origin
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Use wide viewport for responsive design
        useWideViewPort = true
        loadWithOverviewMode = true
    }

    webViewClient = KopiaWebViewClient(assetLoader)
}

/**
 * Serves app assets from the virtual origin and confines navigation to it. External `http(s)` links
 * are handed to the system browser; anything else off-origin is blocked outright.
 */
class KopiaWebViewClient(
    private val assetLoader: WebViewAssetLoader,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse {
        // Serve local assets; deny everything else NATIVELY (403) rather than letting the WebView
        // reach the network. The app makes no legitimate network request (it talks to Kotlin over the
        // JS bridge, not HTTP), so this makes the origin boundary code-enforced instead of relying on
        // the bundle's CSP alone — closing off-origin iframe/subresource loads that never hit
        // shouldOverrideUrlLoading, where the bridge would otherwise be injected into a hostile frame.
        return assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        // In-app navigation is allowed ONLY for the exact React document on our HTTPS virtual origin.
        // Matching scheme + full authority + exact path (not host, not a /assets/ prefix) closes a
        // cleartext http://appassets… bypass (loader doesn't intercept http and NSC permits cleartext
        // → real network with the bridge attached), a :port/userinfo@ authority variant the loader
        // would miss, and keeps any other APK asset (e.g. a scriptable .svg) from becoming a
        // bridge-attached, CSP-less top-level document.
        if (url.scheme == "https" &&
            url.authority == APP_ASSETS_HOST &&
            url.path == APP_PATH
        ) {
            return false
        }
        // Hand a real external web link to the system browser, but ONLY for a genuine user-gesture
        // main-frame navigation. A programmatic location.href to an off-origin URL is an exfil vector
        // (it would carry query data out via the browser), so drop it instead of forwarding it.
        if ((url.scheme == "http" || url.scheme == "https") &&
            request.isForMainFrame &&
            request.hasGesture()
        ) {
            runCatching {
                view.context.startActivity(
                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "No activity to open external link", it) }
        }
        // Block every off-origin navigation (real external links handled above, everything else dropped).
        return true
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        HTTP_FORBIDDEN,
        "Forbidden",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val TAG = "KopiaWebView"
        const val HTTP_FORBIDDEN = 403
    }
}

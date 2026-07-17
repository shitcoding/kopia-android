package org.kopiaKt.app.bridge

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Regression lock for the hardened WebView surface (task-7). Guards that a future edit can't silently
 * reopen the holes this task closed: cross-origin file read, off-origin navigation into the bridge,
 * off-origin subresource/iframe loads, and unattended data exfiltration via external links.
 *
 * The settings flags are checked via mockk `verify` on the setters because Robolectric's
 * `ShadowWebSettings` does not shadow these getters (it would false-pass on a real WebView).
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class WebViewSecurityTest {

    @Test
    fun `configureForKopia disables file and universal URL access`() {
        mockkStatic(WebView::class)
        every { WebView.setWebContentsDebuggingEnabled(any()) } just Runs
        try {
            val settings = mockk<WebSettings>(relaxed = true)
            val webView = mockk<WebView>(relaxed = true)
            every { webView.context } returns RuntimeEnvironment.getApplication()
            every { webView.settings } returns settings

            webView.configureForKopia()

            verify { settings.allowFileAccess = false }
            verify { settings.allowContentAccess = false }
            @Suppress("DEPRECATION")
            verify { settings.allowFileAccessFromFileURLs = false }
            @Suppress("DEPRECATION")
            verify { settings.allowUniversalAccessFromFileURLs = false }
            verify { settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW }
            // Explicitly ensure the dangerous flags are never re-enabled.
            @Suppress("DEPRECATION")
            verify(exactly = 0) { settings.allowUniversalAccessFromFileURLs = true }
            @Suppress("DEPRECATION")
            verify(exactly = 0) { settings.allowFileAccessFromFileURLs = true }
            verify(exactly = 0) { settings.allowFileAccess = true }

            // Navigation is confined by our custom client, not a bare WebViewClient.
            val clientSlot = slot<WebViewClient>()
            verify { webView.webViewClient = capture(clientSlot) }
            assertThat(clientSlot.captured).isInstanceOf(KopiaWebViewClient::class.java)
        } finally {
            unmockkStatic(WebView::class)
        }
    }

    @Test
    fun `navigation is confined to the exact app document`() {
        val client = KopiaWebViewClient(mockk(relaxed = true))
        val webView = webView()

        // The exact React document on the virtual origin loads in-app (false = don't override).
        assertThat(client.shouldOverrideUrlLoading(webView, request("$APP_URL#/settings"))).isFalse()

        // Every bypass vector is blocked (true = overridden): off-origin host; cleartext http on the
        // virtual host; a :port authority variant the loader would miss; another document under
        // /assets/react/ (exact path only); an off-/assets/react/ path; and non-web schemes.
        listOf(
            "https://evil.example.com/steal",
            "http://$APP_ASSETS_HOST/assets/react/index.html",
            "https://$APP_ASSETS_HOST:8443/assets/react/index.html",
            "https://$APP_ASSETS_HOST/assets/react/other.svg",
            "https://$APP_ASSETS_HOST/assets/other.html",
            "javascript:alert(1)",
        ).forEach { url ->
            assertThat(client.shouldOverrideUrlLoading(webView, request(url))).isTrue()
        }
    }

    @Test
    fun `external links open in the browser only on a real user gesture`() {
        val context = mockk<Context>(relaxed = true)
        val webView = mockk<WebView>(relaxed = true)
        every { webView.context } returns context
        val client = KopiaWebViewClient(mockk(relaxed = true))

        // Programmatic off-origin nav (attacker location.href): blocked AND not handed to the browser.
        assertThat(
            client.shouldOverrideUrlLoading(webView, request("https://evil.example.com/?p=secret", gesture = false)),
        ).isTrue()
        verify(exactly = 0) { context.startActivity(any()) }

        // Real user-gesture external link: blocked in-app but handed to the system browser.
        assertThat(
            client.shouldOverrideUrlLoading(webView, request("https://docs.example.com/help", gesture = true)),
        ).isTrue()
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `off-origin subresources are denied natively while local assets pass through`() {
        val served = mockk<WebResourceResponse>(relaxed = true)
        val loader = mockk<WebViewAssetLoader>()
        every { loader.shouldInterceptRequest(any()) } returns null
        every { loader.shouldInterceptRequest(Uri.parse(APP_URL)) } returns served
        val client = KopiaWebViewClient(loader)
        val webView = webView()

        // A local asset the loader serves is passed straight through.
        assertThat(client.shouldInterceptRequest(webView, request(APP_URL))).isSameInstanceAs(served)

        // Anything the loader doesn't serve (off-origin iframe/subresource) is denied with 403,
        // natively — the WebView never reaches the network for it.
        val denied = client.shouldInterceptRequest(webView, request("https://evil.example.com/x"))
        assertThat(denied.statusCode).isEqualTo(403)
    }

    private fun webView(): WebView = mockk<WebView>(relaxed = true).also {
        every { it.context } returns mockk<Context>(relaxed = true)
    }

    private fun request(url: String, mainFrame: Boolean = true, gesture: Boolean = false): WebResourceRequest {
        val req = mockk<WebResourceRequest>()
        every { req.url } returns Uri.parse(url)
        every { req.isForMainFrame } returns mainFrame
        every { req.hasGesture() } returns gesture
        return req
    }
}

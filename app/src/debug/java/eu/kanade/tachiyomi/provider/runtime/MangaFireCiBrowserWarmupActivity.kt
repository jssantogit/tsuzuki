package eu.kanade.tachiyomi.provider.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A passive, debug-only Android WebView warmup for GitHub Actions emulators.
 * Unlike the interactive A54 Activity, this NEVER claims to solve a challenge
 * or confirm a user. API JSON must be verified independently by the real Host.
 */
internal object MangaFireCiBrowserWarmupGate {
    private val latch = AtomicReference(CountDownLatch(1))
    private val status = AtomicReference("TIMEOUT")

    fun reset() {
        status.set("TIMEOUT")
        latch.set(CountDownLatch(1))
    }

    fun completed(value: String) {
        if (status.compareAndSet("TIMEOUT", value)) {
            latch.get().countDown()
        }
    }

    fun await(timeoutSeconds: Long): String {
        latch.get().await(timeoutSeconds, TimeUnit.SECONDS)
        return status.get()
    }
}

class MangaFireCiBrowserWarmupActivity : Activity() {
    private var page: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            MangaFireCiBrowserWarmupGate.completed("PROFILE_UNSUPPORTED")
            finish()
            return
        }

        val view = WebView(this)
        page = view
        WebViewCompat.setProfile(view, ProviderBrowserProfiles.nameFor("app.tsuzuki.mangaall"))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val url = request.url
                return url.scheme != "https" || url.host != "mangafire.to" || url.port != -1
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.startsWith("https://mangafire.to/") == true) {
                    MangaFireCiBrowserWarmupGate.completed("PAGE_FINISHED")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true && errorResponse != null &&
                    errorResponse.statusCode >= 400
                ) {
                    MangaFireCiBrowserWarmupGate.completed("HTTP_REJECTED")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                MangaFireCiBrowserWarmupGate.completed("TLS_REJECTED")
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                MangaFireCiBrowserWarmupGate.completed("RENDERER_EXITED")
                return true
            }
        }
        setContentView(view)
        view.loadUrl("https://mangafire.to/")
    }

    override fun onDestroy() {
        page?.let { view ->
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
        }
        page = null
        super.onDestroy()
    }
}

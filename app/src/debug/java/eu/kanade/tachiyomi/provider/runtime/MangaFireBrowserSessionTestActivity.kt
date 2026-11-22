package eu.kanade.tachiyomi.provider.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug APK + explicitly opted-in instrumentation only. Never shipped in release.
 * A human may complete the site's own verification in the very same profile
 * used by the production Browser Host for app.tsuzuki.mangaall.
 */
internal object MangaFireBrowserSessionTestGate {
    private val latch = AtomicReference(CountDownLatch(1))

    fun reset() {
        latch.set(CountDownLatch(1))
    }

    fun completedByOperator() {
        latch.get().countDown()
    }

    fun awaitOperator(timeoutSeconds: Long): Boolean =
        latch.get().await(timeoutSeconds, TimeUnit.SECONDS)
}

class MangaFireBrowserSessionTestActivity : Activity() {
    private var page: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            setContentView(TextView(this).apply {
                text = "WebView com perfis isolados indisponível. Teste inconclusivo."
            })
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val status = TextView(this).apply {
            text = "Teste MangaFire. Conclua a verificação normal do site antes de tocar em Validar."
            setPadding(24, 18, 24, 18)
        }
        root.addView(status)

        val webView = WebView(this)
        page = webView
        // Use the same canonical name as ProviderHostInvocationFactory. No
        // session/cookie/token export, no use of the app's generic WebView.
        WebViewCompat.setProfile(
            webView,
            ProviderBrowserProfiles.nameFor("app.tsuzuki.mangaall"),
        )
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val url = request.url
                return url.scheme != "https" || url.host != "mangafire.to" || url.port != -1
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                status.text = "Erro TLS. Não prossiga; o teste será inconclusivo."
            }
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val complete = Button(this).apply {
            text = "Validar sessão"
            setOnClickListener {
                MangaFireBrowserSessionTestGate.completedByOperator()
                finish()
            }
        }
        root.addView(complete)
        setContentView(root)
        webView.loadUrl("https://mangafire.to/")
    }

    override fun onDestroy() {
        page?.let { view ->
            view.stopLoading()
            view.destroy()
        }
        page = null
        super.onDestroy()
    }
}

package eu.kanade.tachiyomi.provider.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import tachiyomi.core.provider.runtime.ProviderBrowserHostService
import tachiyomi.core.provider.runtime.ProviderHostServiceException
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AndroidProviderBrowserHostService(
    context: Context,
    private val policy: ProviderNetworkPolicy,
    private val providerProfileName: String,
    private val timeoutSeconds: Long = 15L,
    private val maxTextChars: Int = 64 * 1024,
) : ProviderBrowserHostService, AutoCloseable {

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeCancellations = ConcurrentHashMap<String, () -> Unit>()
    private val closed = AtomicBoolean(false)

    init {
        require(providerProfileName.isNotBlank()) { "Provider browser profile must not be blank" }
        require(timeoutSeconds > 0L) { "Provider browser timeout must be positive" }
        require(maxTextChars > 0) { "Provider browser text limit must be positive" }
    }

    override suspend fun readText(
        url: String,
        cssSelector: String,
    ): String {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Provider Browser Host Service must not block the Android main thread"
        }
        if (cssSelector.isBlank() || cssSelector.length > MAX_SELECTOR_CHARS) {
            throw ProviderHostServiceException("Provider browser selector is invalid")
        }

        if (closed.get()) {
            throw ProviderHostServiceException("Provider browser broker is closed")
        }
        val initialUrl = policy.validate(url)
        val operationId = UUID.randomUUID().toString()
        val result = AtomicReference<Result<String>?>(null)
        val finished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val webView = AtomicReference<WebView?>(null)

        fun complete(outcome: Result<String>) {
            if (!finished.compareAndSet(false, true)) return

            activeCancellations.remove(operationId)
            result.set(outcome)
            val destroy = {
                webView.getAndSet(null)?.let { view ->
                    runCatching { view.stopLoading() }
                    runCatching { view.destroy() }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                destroy()
            } else {
                mainHandler.post { destroy() }
            }
            latch.countDown()
        }

        activeCancellations[operationId] = {
            complete(
                Result.failure(
                    ProviderHostServiceException("Provider browser operation was cancelled"),
                ),
            )
        }
        if (closed.get()) {
            activeCancellations.remove(operationId)?.invoke()
        }

        mainHandler.post {
            if (finished.get()) return@post
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    throw ProviderHostServiceException("Provider browser requires WebView multi-profile support")
                }

                val view = WebView(context)
                webView.set(view)
                WebViewCompat.setProfile(view, providerProfileName)
                configure(view)

                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        if (request == null || !request.isForMainFrame) return false
                        return runCatching {
                            policy.validate(request.url.toString(), resolveAddress = false)
                            false
                        }.getOrElse { error ->
                            complete(Result.failure(error))
                            true
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        if (request == null) return null
                        return runCatching {
                            policy.validate(request.url.toString())
                            null
                        }.getOrElse { error ->
                            if (request.isForMainFrame) {
                                complete(Result.failure(error))
                            }
                            blockedResponse()
                        }
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        if (finished.get() || view == null || url == null) return
                        runCatching {
                            policy.validate(url, resolveAddress = false)
                        }.onFailure { error ->
                            complete(Result.failure(error))
                            return
                        }

                        val selector = JSONObject.quote(cssSelector)
                        view.evaluateJavascript(
                            """
                            (() => {
                              const node = document.querySelector($selector);
                              return node ? node.textContent : null;
                            })()
                            """.trimIndent(),
                        ) { raw ->
                            runCatching { decodeJavascriptText(raw) }
                                .onSuccess { complete(Result.success(it)) }
                                .onFailure { complete(Result.failure(it)) }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            complete(
                                Result.failure(
                                    ProviderHostServiceException("Provider browser navigation failed"),
                                ),
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handler?.cancel()
                        complete(
                            Result.failure(
                                ProviderHostServiceException("Provider browser TLS validation failed"),
                            ),
                        )
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        complete(
                            Result.failure(
                                ProviderHostServiceException("Provider browser renderer exited"),
                            ),
                        )
                        return true
                    }
                }

                view.loadUrl(initialUrl.toString())
            } catch (error: Throwable) {
                complete(Result.failure(error))
            }
        }

        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            complete(
                Result.failure(
                    ProviderHostServiceException("Provider browser operation timed out"),
                ),
            )
        }

        return requireNotNull(result.get()) {
            "Provider browser operation completed without a result"
        }.getOrElse { error ->
            if (error is ProviderHostServiceException) throw error
            throw ProviderHostServiceException("Provider browser operation failed", error)
        }
    }

    override fun close() {
        closed.set(true)
        activeCancellations.values.toList().forEach { cancel ->
            cancel()
        }
        activeCancellations.clear()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked by Provider network policy",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun decodeJavascriptText(rawValue: String): String {
        val value = try {
            JSONTokener(rawValue).nextValue()
        } catch (error: Exception) {
            throw IOException("Provider browser result could not be decoded", error)
        }
        if (value === JSONObject.NULL) {
            throw ProviderHostServiceException("Provider browser selector did not resolve to text")
        }
        val text = value as? String
            ?: throw ProviderHostServiceException("Provider browser result is not text")
        if (text.length > maxTextChars) {
            throw ProviderHostServiceException("Provider browser result exceeds the text limit")
        }
        return text
    }

    private companion object {
        const val MAX_SELECTOR_CHARS = 2 * 1024
    }
}

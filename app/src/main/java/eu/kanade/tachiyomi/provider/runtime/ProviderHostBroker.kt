package eu.kanade.tachiyomi.provider.runtime

import android.annotation.SuppressLint
import android.graphics.RenderNode
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ProviderHostPolicy(
    val allowedOrigins: Set<String>,
    val allowLocalNetwork: Boolean = false,
)

class ProviderHostBroker(
    private val context: android.content.Context,
    private val policy: ProviderHostPolicy,
    private val providerProfileName: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val allowedOrigins = policy.allowedOrigins
        .map(::parseOrigin)
        .toSet()

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(providerProfileName.isNotBlank()) { "Provider profile name must not be blank" }
        require(allowedOrigins.isNotEmpty()) { "At least one allowed origin is required" }
    }

    fun httpGet(url: String): String {
        var currentUrl = validateUrl(url, resolveAddress = true)
        var redirects = 0

        while (true) {
            val response = httpClient.newCall(
                Request.Builder()
                    .url(currentUrl)
                    .get()
                    .build(),
            ).execute()

            try {
                if (response.code in REDIRECT_CODES) {
                    if (redirects++ >= MAX_REDIRECTS) {
                        throw IOException("Provider HTTP redirect limit exceeded")
                    }

                    val location = response.header("Location")
                        ?: throw IOException("Provider HTTP redirect is missing Location")
                    val redirected = currentUrl.resolve(location)
                        ?: throw IOException("Provider HTTP redirect URL is invalid")
                    currentUrl = validateUrl(redirected.toString(), resolveAddress = true)
                    continue
                }

                if (!response.isSuccessful) {
                    throw IOException("Provider HTTP request failed with status ${response.code}")
                }

                val body = response.body
                return body?.readBoundedText() ?: ""
            } finally {
                response.close()
            }
        }
    }

    fun browserReadText(
        url: String,
        cssSelector: String,
    ): String {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Browser broker must not block the Android main thread"
        }
        require(cssSelector.isNotBlank()) { "CSS selector must not be blank" }

        val initialUrl = validateUrl(url, resolveAddress = true)
        val result = AtomicReference<Result<String>?>(null)
        val finished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val webView = AtomicReference<WebView?>(null)

        fun complete(outcome: Result<String>) {
            if (!finished.compareAndSet(false, true)) return

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
                mainHandler.post(destroy)
            }
            latch.countDown()
        }

        mainHandler.post {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    throw UnsupportedOperationException("WebView multi-profile support is required")
                }

                val view = WebView(context)
                webView.set(view)

                WebViewCompat.setProfile(view, providerProfileName)
                configureWebView(view)

                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        if (request == null || !request.isForMainFrame) return false

                        return runCatching {
                            validateUrl(request.url.toString(), resolveAddress = false)
                            false
                        }.getOrElse { error ->
                            complete(Result.failure(error))
                            true
                        }
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        if (finished.get() || view == null || url == null) return

                        runCatching {
                            validateUrl(url, resolveAddress = false)
                        }.onFailure { error ->
                            complete(Result.failure(error))
                            return
                        }

                        val quotedSelector = JSONObject.quote(cssSelector)
                        view.evaluateJavascript(
                            """
                            (() => {
                              const node = document.querySelector($quotedSelector);
                              return node ? node.textContent : null;
                            })()
                            """.trimIndent(),
                        ) { rawValue ->
                            runCatching {
                                decodeJavascriptString(rawValue)
                            }.onSuccess { value ->
                                complete(Result.success(value))
                            }.onFailure { error ->
                                complete(Result.failure(error))
                            }
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
                                    IOException(
                                        "Provider browser navigation failed: ${error?.errorCode ?: -1}",
                                    ),
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
                        complete(Result.failure(IOException("Provider browser TLS validation failed")))
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        complete(Result.failure(IOException("Provider browser renderer exited")))
                        return true
                    }
                }

                view.loadUrl(initialUrl.toString())
            } catch (error: Throwable) {
                complete(Result.failure(error))
            }
        }

        if (!latch.await(BROWSER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            complete(Result.failure(IOException("Provider browser operation timed out")))
        }

        return requireNotNull(result.get()) {
            "Provider browser operation completed without a result"
        }.getOrThrow()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
    }

    private fun validateUrl(
        rawUrl: String,
        resolveAddress: Boolean,
    ): HttpUrl {
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw SecurityException("Provider URL must be an absolute HTTP(S) URL")

        if (url.scheme != "http" && url.scheme != "https") {
            throw SecurityException("Provider URL scheme is not allowed")
        }
        if (url.origin() !in allowedOrigins) {
            throw SecurityException("Provider URL origin is not allowed")
        }

        if (!policy.allowLocalNetwork) {
            if (isObviouslyLocalHost(url.host)) {
                throw SecurityException("Provider local-network access is not allowed")
            }
            if (resolveAddress && resolvesToLocalNetwork(url.host)) {
                throw SecurityException("Provider local-network access is not allowed")
            }
        }

        return url
    }

    private fun parseOrigin(rawOrigin: String): Origin {
        val url = rawOrigin.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Allowed origin must be HTTP(S)")

        require(url.scheme == "http" || url.scheme == "https") {
            "Allowed origin must use HTTP(S)"
        }
        require(url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "Allowed origin must not contain path, query, or fragment"
        }

        return url.origin()
    }

    private fun HttpUrl.origin(): Origin = Origin(
        scheme = scheme,
        host = host,
        port = port,
    )

    private fun isObviouslyLocalHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true

        val literal = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return host.any { it == ':' } || host.all { it.isDigit() || it == '.' }
            && literal.isLocalNetworkAddress()
    }

    private fun resolvesToLocalNetwork(host: String): Boolean =
        InetAddress.getAllByName(host).any(InetAddress::isLocalNetworkAddress)

    private fun InetAddress.isLocalNetworkAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress) {
            return true
        }
        if (this is Inet6Address) {
            val first = address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            if (first and 0xFE == 0xFC) return true
        }
        return false
    }

    private fun ResponseBody.readBoundedText(): String {
        charStream().use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(4_096)

            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (output.length + read > MAX_TEXT_CHARS) {
                    throw IOException("Provider broker response exceeds text limit")
                }
                output.append(buffer, 0, read)
            }

            return output.toString()
        }
    }

    private fun decodeJavascriptString(rawValue: String): String {
        val value = JSONTokener(rawValue).nextValue()
        if (value === JSONObject.NULL) {
            throw IOException("Provider browser selector did not resolve to text")
        }

        val text = value as? String
            ?: throw IOException("Provider browser result is not text")
        if (text.length > MAX_TEXT_CHARS) {
            throw IOException("Provider broker response exceeds text limit")
        }
        return text
    }

    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
    )

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_TEXT_CHARS = 64 * 1024
        const val BROWSER_TIMEOUT_SECONDS = 15L
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

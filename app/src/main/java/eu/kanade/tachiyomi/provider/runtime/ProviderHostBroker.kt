package eu.kanade.tachiyomi.provider.runtime

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    private val resourceLock = Any()
    private val resourceCounter = AtomicLong()
    private val resources = linkedMapOf<String, ByteArray>()
    private var resourceBytes = 0L

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

    fun binaryFetch(url: String): String {
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
                        throw IOException("Provider binary redirect limit exceeded")
                    }

                    val location = response.header("Location")
                        ?: throw IOException("Provider binary redirect is missing Location")
                    val redirected = currentUrl.resolve(location)
                        ?: throw IOException("Provider binary redirect URL is invalid")
                    currentUrl = validateUrl(redirected.toString(), resolveAddress = true)
                    continue
                }

                if (!response.isSuccessful) {
                    throw IOException("Provider binary request failed with status ${response.code}")
                }

                val bytes = response.body?.readBoundedBytes() ?: ByteArray(0)
                return storeResource(bytes)
            } finally {
                response.close()
            }
        }
    }

    fun binaryZipEntry(
        resourceHandle: String,
        entryName: String,
    ): String {
        require(entryName.isNotBlank()) { "ZIP entry name must not be blank" }

        ZipInputStream(ByteArrayInputStream(resource(resourceHandle))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == entryName) {
                    val bytes = zip.readBoundedBytes()
                    zip.closeEntry()
                    return storeResource(bytes)
                }
                zip.closeEntry()
            }
        }

        throw IOException("Provider ZIP entry was not found")
    }

    fun binaryAesCbcDecrypt(
        resourceHandle: String,
        keyHex: String,
        ivHex: String,
    ): String {
        val key = decodeHex(keyHex)
        val iv = decodeHex(ivHex)
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24, or 32 bytes"
        }
        require(iv.size == 16) { "AES-CBC IV must be 16 bytes" }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        return storeResource(cipher.doFinal(resource(resourceHandle)))
    }

    fun binaryImageCrop(
        resourceHandle: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): String {
        require(width > 0 && height > 0) { "Image crop dimensions must be positive" }

        val bytes = resource(resourceHandle)
        val source = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
        ) ?: throw IOException("Provider image resource could not be decoded")

        try {
            require(x >= 0 && y >= 0 && x + width <= source.width && y + height <= source.height) {
                "Image crop is outside source bounds"
            }
            val cropped = Bitmap.createBitmap(source, x, y, width, height)
            try {
                val bytes = ByteArrayOutputStream().use { output ->
                    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("Provider cropped image could not be encoded")
                    }
                    output.toByteArray()
                }
                return storeResource(bytes)
            } finally {
                if (cropped !== source) {
                    cropped.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    fun binaryImagePixel(
        resourceHandle: String,
        x: Int,
        y: Int,
    ): String {
        val bytes = resource(resourceHandle)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Provider image resource could not be decoded")

        try {
            require(x in 0 until bitmap.width && y in 0 until bitmap.height) {
                "Image pixel is outside source bounds"
            }
            return String.format(Locale.US, "%08X", bitmap.getPixel(x, y))
        } finally {
            bitmap.recycle()
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
                mainHandler.post { destroy() }
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

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        if (request == null) return null

                        return runCatching {
                            validateUrl(request.url.toString(), resolveAddress = false)
                            null
                        }.getOrElse { error ->
                            if (request.isForMainFrame) {
                                complete(Result.failure(error))
                            }
                            blockedWebResourceResponse()
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

    private fun blockedWebResourceResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked by ProviderHostPolicy",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

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

        val looksLikeIpLiteral = host.any { it == ':' } || host.all { it.isDigit() || it == '.' }
        if (!looksLikeIpLiteral) return false

        val literal = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return literal.isLocalNetworkAddress()
    }

    private fun resolvesToLocalNetwork(host: String): Boolean =
        InetAddress.getAllByName(host).any { it.isLocalNetworkAddress() }

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

    private fun storeResource(bytes: ByteArray): String {
        if (bytes.size > MAX_BINARY_RESOURCE_BYTES) {
            throw IOException("Provider binary resource exceeds per-resource limit")
        }

        return synchronized(resourceLock) {
            if (resourceBytes + bytes.size > MAX_BINARY_STORE_BYTES) {
                throw IOException("Provider binary resource store limit exceeded")
            }

            val handle = "res:${resourceCounter.incrementAndGet()}"
            resources[handle] = bytes
            resourceBytes += bytes.size
            handle
        }
    }

    private fun resource(handle: String): ByteArray = synchronized(resourceLock) {
        resources[handle] ?: throw SecurityException("Provider binary resource handle is invalid")
    }

    private fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex value must have an even length" }
        return ByteArray(value.length / 2) { index ->
            val offset = index * 2
            value.substring(offset, offset + 2).toIntOrNull(16)?.toByte()
                ?: throw IllegalArgumentException("Hex value contains invalid characters")
        }
    }

    private fun ResponseBody.readBoundedBytes(): ByteArray =
        byteStream().use { input -> input.readBoundedBytes() }

    private fun java.io.InputStream.readBoundedBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0

        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BINARY_RESOURCE_BYTES) {
                throw IOException("Provider binary resource exceeds per-resource limit")
            }
            output.write(buffer, 0, read)
        }

        return output.toByteArray()
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
        const val MAX_BINARY_RESOURCE_BYTES = 16 * 1024 * 1024
        const val MAX_BINARY_STORE_BYTES = 32L * 1024L * 1024L
        const val BROWSER_TIMEOUT_SECONDS = 15L
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

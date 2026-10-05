package tachiyomi.core.provider.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProviderHttpSessionStore {

    private val sessions = ConcurrentHashMap<String, CookieJar>()

    fun cookieJar(providerId: String): CookieJar {
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        return sessions.computeIfAbsent(providerId) { InMemoryProviderCookieJar() }
    }

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

class DefaultProviderHttpHostService(
    private val owner: ProviderResourceOwner,
    private val resources: ProviderResourceStore,
    private val policy: ProviderNetworkPolicy,
    cookieJar: CookieJar,
    baseClient: OkHttpClient = OkHttpClient(),
    private val maxRedirects: Int = 5,
    private val maxTextChars: Int = ProviderHttpProtocol.MAX_RESPONSE_BODY_CHARS,
    private val maxResponseBytes: Int = 16 * 1024 * 1024,
) : ProviderHttpHostService, AutoCloseable {

    private val activeCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    private val closed = AtomicBoolean(false)

    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .dns { hostname -> policy.resolvePublicAddresses(hostname) }
        .build()

    init {
        require(maxRedirects >= 0) { "Provider HTTP redirect limit must not be negative" }
        require(maxTextChars in 1..ProviderHttpProtocol.MAX_RESPONSE_BODY_CHARS) {
            "Provider HTTP text limit is outside supported bounds"
        }
        require(maxResponseBytes > 0) { "Provider HTTP byte limit must be positive" }
    }

    override suspend fun request(request: ProviderHttpRequest): ProviderHttpResponse =
        withContext(Dispatchers.IO) {
            execute(
                request = request,
                requireSuccessful = false,
            ) { statusCode, body ->
                ProviderHttpResponse(
                    statusCode = statusCode,
                    body = body?.readBoundedText(maxTextChars).orEmpty(),
                )
            }
        }

    override suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        execute(
            request = ProviderHttpRequest(
                method = ProviderHttpMethod.GET,
                url = url,
            ),
            requireSuccessful = true,
        ) { _, body ->
            body?.readBoundedText(maxTextChars).orEmpty()
        }
    }

    override suspend fun getResource(url: String): ProviderResourceHandle = withContext(Dispatchers.IO) {
        val bytes = execute(
            request = ProviderHttpRequest(
                method = ProviderHttpMethod.GET,
                url = url,
            ),
            requireSuccessful = true,
        ) { _, body ->
            body?.readBoundedBytes(maxResponseBytes) ?: ByteArray(0)
        }
        resources.put(
            owner = owner,
            kind = ProviderResourceKind.HTML,
            bytes = bytes,
        )
    }

    suspend fun getBinaryBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        execute(
            request = ProviderHttpRequest(
                method = ProviderHttpMethod.GET,
                url = url,
            ),
            requireSuccessful = true,
        ) { _, body ->
            body?.readBoundedBytes(maxResponseBytes) ?: ByteArray(0)
        }
    }

    private fun <T> execute(
        request: ProviderHttpRequest,
        requireSuccessful: Boolean,
        consume: (Int, ResponseBody?) -> T,
    ): T {
        if (closed.get()) {
            throw ProviderHostServiceException("Provider HTTP broker is closed")
        }

        var currentUrl = policy.validate(request.url, resolveAddress = false)
        var currentMethod = request.method
        var currentHeaders = request.headers
        var currentBody = request.body
        var redirects = 0

        while (true) {
            val call = client.newCall(
                buildRequest(
                    url = currentUrl,
                    method = currentMethod,
                    headers = currentHeaders,
                    body = currentBody,
                ),
            )
            activeCalls += call
            if (closed.get()) {
                activeCalls -= call
                call.cancel()
                throw ProviderHostServiceException("Provider HTTP broker is closed")
            }
            val response = try {
                call.execute()
            } catch (error: ProviderNetworkPolicyException) {
                activeCalls -= call
                throw error
            } catch (error: Exception) {
                activeCalls -= call
                throw ProviderHostServiceException("Provider HTTP request failed", error)
            }

            try {
                if (response.code in REDIRECT_CODES) {
                    if (redirects >= maxRedirects) {
                        throw ProviderHostServiceException("Provider HTTP redirect limit exceeded")
                    }
                    redirects += 1

                    val location = response.header("Location")
                        ?: throw ProviderHostServiceException("Provider HTTP redirect is missing Location")
                    val redirected = currentUrl.resolve(location)
                        ?: throw ProviderHostServiceException("Provider HTTP redirect URL is invalid")
                    val validated = policy.validate(redirected.toString(), resolveAddress = false)

                    if (!sameOrigin(currentUrl, validated)) {
                        currentHeaders = emptyMap()
                    }
                    if (
                        response.code in REDIRECT_TO_GET_CODES &&
                        currentMethod != ProviderHttpMethod.GET
                    ) {
                        currentMethod = ProviderHttpMethod.GET
                        currentBody = null
                        currentHeaders = currentHeaders.filterKeys {
                            !it.equals("Content-Type", ignoreCase = true)
                        }
                    }
                    currentUrl = validated
                    continue
                }

                if (requireSuccessful && !response.isSuccessful) {
                    throw ProviderHostServiceException("Provider HTTP response was not successful")
                }
                return consume(response.code, response.body)
            } finally {
                activeCalls -= call
                response.close()
            }
        }
    }

    private fun buildRequest(
        url: HttpUrl,
        method: ProviderHttpMethod,
        headers: Map<String, String>,
        body: String?,
    ): Request {
        val contentType = headers.entries
            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()

        val requestBody = when (method) {
            ProviderHttpMethod.GET -> null
            ProviderHttpMethod.DELETE -> body?.toRequestBody(contentType)
            ProviderHttpMethod.POST,
            ProviderHttpMethod.PUT,
            ProviderHttpMethod.PATCH,
            -> body.orEmpty().toRequestBody(contentType)
        }

        return Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .method(method.name, requestBody)
            .build()
    }

    override fun close() {
        closed.set(true)
        activeCalls.toList().forEach { call ->
            call.cancel()
        }
        activeCalls.clear()
    }

    private companion object {
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        val REDIRECT_TO_GET_CODES = setOf(301, 302, 303)

        fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
            first.scheme == second.scheme &&
                first.host == second.host &&
                first.port == second.port
    }
}

private class InMemoryProviderCookieJar : CookieJar {

    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { existing ->
            existing.expiresAt <= now ||
                cookies.any { incoming ->
                    incoming.name == existing.name &&
                        incoming.domain == existing.domain &&
                        incoming.path == existing.path
                }
        }
        this.cookies += cookies.filter { it.expiresAt > now }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}

private fun ResponseBody.readBoundedText(maxChars: Int): String {
    charStream().use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)

        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (output.length + read > maxChars) {
                throw ProviderHostServiceException("Provider HTTP text response exceeds the size limit")
            }
            output.append(buffer, 0, read)
        }
        return output.toString()
    }
}

private fun ResponseBody.readBoundedBytes(maxBytes: Int): ByteArray =
    byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                throw ProviderHostServiceException("Provider HTTP binary response exceeds the size limit")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

package tachiyomi.core.provider.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
    private val maxTextChars: Int = 64 * 1024,
    private val maxResponseBytes: Int = 16 * 1024 * 1024,
) : ProviderHttpHostService {

    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .dns { hostname -> policy.resolvePublicAddresses(hostname) }
        .build()

    init {
        require(maxRedirects >= 0) { "Provider HTTP redirect limit must not be negative" }
        require(maxTextChars > 0) { "Provider HTTP text limit must be positive" }
        require(maxResponseBytes > 0) { "Provider HTTP byte limit must be positive" }
    }

    override suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        execute(url) { body ->
            body?.readBoundedText(maxTextChars).orEmpty()
        }
    }

    override suspend fun getResource(url: String): ProviderResourceHandle = withContext(Dispatchers.IO) {
        val bytes = execute(url) { body ->
            body?.readBoundedBytes(maxResponseBytes) ?: ByteArray(0)
        }
        resources.put(
            owner = owner,
            kind = ProviderResourceKind.HTML,
            bytes = bytes,
        )
    }

    suspend fun getBinaryBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        execute(url) { body ->
            body?.readBoundedBytes(maxResponseBytes) ?: ByteArray(0)
        }
    }

    private fun <T> execute(
        rawUrl: String,
        consume: (ResponseBody?) -> T,
    ): T {
        var currentUrl = policy.validate(rawUrl, resolveAddress = false)
        var redirects = 0

        while (true) {
            val response = try {
                client.newCall(
                    Request.Builder()
                        .url(currentUrl)
                        .get()
                        .build(),
                ).execute()
            } catch (error: ProviderNetworkPolicyException) {
                throw error
            } catch (error: Exception) {
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
                    currentUrl = policy.validate(redirected.toString(), resolveAddress = false)
                    continue
                }

                if (!response.isSuccessful) {
                    throw ProviderHostServiceException("Provider HTTP response was not successful")
                }
                return consume(response.body)
            } finally {
                response.close()
            }
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
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

package eu.kanade.tachiyomi.provider.repository

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import tachiyomi.core.provider.supplychain.ProviderRepositoryTransport
import tachiyomi.core.provider.supplychain.ProviderSupplyChainException
import tachiyomi.core.provider.supplychain.SignedProviderRepositoryIndex
import tachiyomi.core.provider.supplychain.decodeProviderSignedIndexEnvelope

class AndroidProviderRepositoryTransport(
    client: OkHttpClient,
    private val allowInsecureHttp: Boolean = false,
    private val maxRedirects: Int = 5,
    private val maxIndexBytes: Int = 2 * 1024 * 1024,
    private val maxArtifactBytes: Int = 16 * 1024 * 1024,
) : ProviderRepositoryTransport {

    private val client = client.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(maxRedirects >= 0) { "Provider repository redirect limit must not be negative" }
        require(maxIndexBytes > 0) { "Provider repository index limit must be positive" }
        require(maxArtifactBytes > 0) { "Provider repository artifact limit must be positive" }
    }

    override suspend fun fetchIndex(indexUrl: String): SignedProviderRepositoryIndex =
        withContext(Dispatchers.IO) {
            val bytes = fetchBytes(indexUrl, maxIndexBytes)
            val text = try {
                bytes.decodeToString()
            } catch (error: Exception) {
                throw ProviderSupplyChainException("Repository index envelope is not valid UTF-8", error)
            }
            decodeProviderSignedIndexEnvelope(text)
        }

    override suspend fun fetchArtifact(artifactUrl: String): ByteArray =
        withContext(Dispatchers.IO) {
            fetchBytes(artifactUrl, maxArtifactBytes)
        }

    private fun fetchBytes(
        rawUrl: String,
        maxBytes: Int,
    ): ByteArray {
        var current = validateUrl(rawUrl)
        var redirects = 0

        while (true) {
            val response = try {
                client.newCall(
                    Request.Builder()
                        .url(current)
                        .get()
                        .build(),
                ).execute()
            } catch (error: Exception) {
                throw ProviderSupplyChainException("Provider repository request failed", error)
            }

            try {
                if (response.code in REDIRECT_CODES) {
                    if (redirects >= maxRedirects) {
                        throw ProviderSupplyChainException("Provider repository redirect limit exceeded")
                    }
                    redirects += 1
                    val location = response.header("Location")
                        ?: throw ProviderSupplyChainException(
                            "Provider repository redirect is missing Location",
                        )
                    val redirected = current.resolve(location)
                        ?: throw ProviderSupplyChainException(
                            "Provider repository redirect URL is invalid",
                        )
                    current = validateUrl(redirected.toString())
                    continue
                }

                if (!response.isSuccessful) {
                    throw ProviderSupplyChainException(
                        "Provider repository request returned HTTP ${response.code}",
                    )
                }

                return response.body.readBoundedBytes(maxBytes)
            } finally {
                response.close()
            }
        }
    }

    private fun validateUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull()
            ?: throw ProviderSupplyChainException("Provider repository URL is malformed")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ProviderSupplyChainException("Provider repository URL credentials are not allowed")
        }
        val validScheme = url.scheme == "https" || (allowInsecureHttp && url.scheme == "http")
        if (!validScheme) {
            throw ProviderSupplyChainException("Provider repository URL must use HTTPS")
        }
        return url
    }

    private fun ResponseBody?.readBoundedBytes(maxBytes: Int): ByteArray {
        if (this == null) return ByteArray(0)

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
                    throw ProviderSupplyChainException(
                        "Provider repository response exceeds the configured size limit",
                    )
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

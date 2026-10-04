package tachiyomi.core.provider.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ProviderHttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

@Serializable
data class ProviderHttpRequest(
    val method: ProviderHttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    init {
        require(url.isNotBlank() && url.length <= MAX_URL_CHARS) {
            "Provider HTTP request URL is invalid"
        }
        require(headers.size <= MAX_HEADERS) {
            "Provider HTTP request has too many headers"
        }
        require(
            headers.all { (name, value) ->
                HEADER_NAME.matches(name) &&
                    name.lowercase() !in FORBIDDEN_REQUEST_HEADERS &&
                    value.length <= MAX_HEADER_VALUE_CHARS &&
                    !value.contains('\r') &&
                    !value.contains('\n')
            },
        ) {
            "Provider HTTP request header is invalid"
        }
        require(body == null || body.length <= MAX_BODY_CHARS) {
            "Provider HTTP request body exceeds the text limit"
        }
        require(method != ProviderHttpMethod.GET || body == null) {
            "Provider HTTP GET requests cannot contain a body"
        }
    }

    internal companion object {
        const val MAX_URL_CHARS = 8 * 1024
        const val MAX_HEADERS = 32
        const val MAX_HEADER_VALUE_CHARS = 8 * 1024
        const val MAX_BODY_CHARS = 64 * 1024
        val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
        val FORBIDDEN_REQUEST_HEADERS = setOf(
            "connection",
            "content-length",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )
    }
}

@Serializable
data class ProviderHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    init {
        require(statusCode in 100..599) {
            "Provider HTTP response status is invalid"
        }
        require(body.length <= ProviderHttpProtocol.MAX_RESPONSE_BODY_CHARS) {
            "Provider HTTP response body exceeds the text limit"
        }
    }
}

object ProviderHttpProtocol {
    const val MAX_REQUEST_JSON_CHARS = 96 * 1024
    const val MAX_RESPONSE_JSON_CHARS = 96 * 1024
    const val MAX_RESPONSE_BODY_CHARS = 64 * 1024

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: ProviderHttpRequest): String =
        json.encodeToString(request).also(::requireRequestBound)

    fun decodeRequest(value: String): ProviderHttpRequest {
        requireRequestBound(value)
        return try {
            json.decodeFromString(value)
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider HTTP request is malformed", error)
        }
    }

    fun encodeResponse(response: ProviderHttpResponse): String =
        json.encodeToString(response).also(::requireResponseBound)

    fun decodeResponse(value: String): ProviderHttpResponse {
        requireResponseBound(value)
        return try {
            json.decodeFromString(value)
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider HTTP response is malformed", error)
        }
    }

    private fun requireRequestBound(value: String) {
        if (value.length > MAX_REQUEST_JSON_CHARS) {
            throw ProviderHostServiceException("Provider HTTP request exceeds the IPC size limit")
        }
    }

    private fun requireResponseBound(value: String) {
        if (value.length > MAX_RESPONSE_JSON_CHARS) {
            throw ProviderHostServiceException("Provider HTTP response exceeds the IPC size limit")
        }
    }
}

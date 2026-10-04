package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.provider.ProviderId
import java.net.URI
import java.security.MessageDigest

typealias ProviderCursor = tachiyomi.domain.tsuzuki.provider.ProviderCursor
typealias ProviderPage<T> = tachiyomi.domain.tsuzuki.provider.ProviderPage<T>
typealias ProviderManagedResourceRef = tachiyomi.domain.tsuzuki.provider.ProviderManagedResourceRef
typealias ProviderManagedFileFormat = tachiyomi.domain.tsuzuki.provider.ProviderManagedFileFormat
typealias ProviderManagedResourceResolver = tachiyomi.domain.tsuzuki.provider.ProviderManagedResourceResolver

data class ProviderBindingRef(
    val providerId: ProviderId,
    val facetId: String?,
    val externalWorkId: String,
) {
    init {
        require(externalWorkId.isNotBlank()) { "Provider external work ID must not be blank" }
        require(externalWorkId.length <= MAX_EXTERNAL_ID_CHARS) {
            "Provider external work ID is too long"
        }
        require(facetId == null || (facetId.isNotBlank() && facetId.length <= MAX_FACET_ID_CHARS)) {
            "Provider facet ID is invalid"
        }
    }

    private companion object {
        const val MAX_EXTERNAL_ID_CHARS = 1024
        const val MAX_FACET_ID_CHARS = 128
    }
}

/**
 * Stable operational producer identity for chapter evidence emitted by one exact Provider binding.
 *
 * Provider chapter IDs are not guaranteed to be globally unique across facets/works, so evidence
 * persistence must not scope them only by [ProviderId]. The opaque digest keeps canonical evidence
 * independent from Provider internals while allowing callers with the binding to recover the same
 * producer identity deterministically.
 */
fun ProviderBindingRef.evidenceProducerId(): String {
    val identity = buildString {
        append(providerId.value)
        append('\u0000')
        append(facetId.orEmpty())
        append('\u0000')
        append(externalWorkId)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    return "provider-binding:$digest"
}

data class ProviderWorkCandidate(
    val externalWorkId: String,
    val title: String,
    val aliases: List<String> = emptyList(),
    val url: String? = null,
    val language: String? = null,
) {
    init {
        require(externalWorkId.isNotBlank()) { "Provider work candidate ID must not be blank" }
        require(title.isNotBlank()) { "Provider work candidate title must not be blank" }
        require(aliases.size <= MAX_ALIASES) { "Provider work candidate has too many aliases" }
        require(aliases.all { it.isNotBlank() && it.length <= MAX_TEXT_CHARS }) {
            "Provider work candidate alias is invalid"
        }
        require(language == null || (language.isNotBlank() && language.length <= 32)) {
            "Provider work candidate language is invalid"
        }
    }

    private companion object {
        const val MAX_ALIASES = 32
        const val MAX_TEXT_CHARS = 1024
    }
}

data class ProviderChapterObservation(
    val providerChapterId: String,
    val rawLabel: String,
    val rawNumber: Double? = null,
    val volume: Int? = null,
    val title: String? = null,
    val language: String? = null,
    val scanlationGroup: String? = null,
    val releaseDateMillis: Long? = null,
) {
    init {
        require(providerChapterId.isNotBlank()) { "Provider chapter ID must not be blank" }
        require(providerChapterId.length <= MAX_ID_CHARS) { "Provider chapter ID is too long" }
        require(rawLabel.isNotBlank()) { "Provider chapter label must not be blank" }
        require(rawLabel.length <= MAX_LABEL_CHARS) { "Provider chapter label is too long" }
        require(rawNumber == null || rawNumber.isFinite()) { "Provider raw chapter number must be finite" }
        require(volume == null || volume >= 0) { "Provider chapter volume must not be negative" }
        require(title == null || title.length <= MAX_LABEL_CHARS) { "Provider chapter title is too long" }
        require(language == null || (language.isNotBlank() && language.length <= 32)) {
            "Provider chapter language is invalid"
        }
        require(scanlationGroup == null || scanlationGroup.length <= MAX_LABEL_CHARS) {
            "Provider scanlation group is too long"
        }
        require(releaseDateMillis == null || releaseDateMillis >= 0L) {
            "Provider chapter release date must not be negative"
        }
    }

    private companion object {
        const val MAX_ID_CHARS = 1024
        const val MAX_LABEL_CHARS = 4096
    }
}

data class ProviderPageRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val allowedOrigins: Set<String>,
    val allowLocalNetwork: Boolean = false,
) {
    init {
        val uri = runCatching { URI(url) }.getOrNull()
        require(
            uri != null &&
                (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null,
        ) {
            "Provider page URL must be an absolute HTTP(S) URL without credentials"
        }
        require(headers.size <= MAX_HEADERS) { "Provider page request has too many headers" }
        require(allowedOrigins.isNotEmpty()) { "Provider page request requires network authority" }
        require(allowedOrigins.size <= MAX_ORIGINS) { "Provider page request has too many allowed origins" }
        require(allowedOrigins.all { it.isNotBlank() && it.length <= MAX_ORIGIN_CHARS }) {
            "Provider page request origin authority is invalid"
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
            "Provider page request header is invalid"
        }
    }

    private companion object {
        const val MAX_HEADERS = 32
        const val MAX_HEADER_VALUE_CHARS = 8192
        const val MAX_ORIGINS = 64
        const val MAX_ORIGIN_CHARS = 2048
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

sealed interface ProviderReadingDelivery {
    data class PageList(
        val pages: List<ProviderPageRequest>,
    ) : ProviderReadingDelivery {
        init {
            require(pages.isNotEmpty()) { "Provider page delivery must contain at least one page" }
            require(pages.size <= MAX_PAGES) { "Provider page delivery contains too many pages" }
        }

        private companion object {
            const val MAX_PAGES = 5000
        }
    }

    data class ManagedFile(
        val resource: ProviderManagedResourceRef,
        val format: ProviderManagedFileFormat,
    ) : ProviderReadingDelivery
}

data class ProviderReadingLookupRequest(
    val titles: List<String>,
    val cursor: ProviderCursor? = null,
) {
    init {
        require(titles.isNotEmpty()) { "Provider lookup requires at least one title" }
        require(titles.size <= MAX_TITLES) { "Provider lookup has too many titles" }
        require(titles.all { it.isNotBlank() && it.length <= MAX_TITLE_CHARS }) {
            "Provider lookup title is invalid"
        }
    }

    private companion object {
        const val MAX_TITLES = 16
        const val MAX_TITLE_CHARS = 1024
    }
}

data class ProviderReadingChaptersRequest(
    val binding: ProviderBindingRef,
    val cursor: ProviderCursor? = null,
)

data class ProviderReadingPagesRequest(
    val binding: ProviderBindingRef,
    val providerChapterId: String,
) {
    init {
        require(providerChapterId.isNotBlank()) { "Provider chapter ID must not be blank" }
        require(providerChapterId.length <= 1024) { "Provider chapter ID is too long" }
    }
}

enum class ProviderErrorCode {
    UNAVAILABLE,
    PERMISSION_DENIED,
    HOST_API_UNSUPPORTED,
    SCRIPT_ERROR,
    TIMEOUT,
    RUNTIME_DIED,
    NETWORK_POLICY,
    NETWORK_ERROR,
    BROWSER_ERROR,
    RESOURCE_LIMIT,
    MALFORMED_RESULT,
    AUTH_REQUIRED,
    ACQUISITION_FAILED,
}

data class ProviderError(
    val code: ProviderErrorCode,
    val retryable: Boolean,
)

sealed interface ProviderCallResult<out T> {
    data class Success<T>(val value: T) : ProviderCallResult<T>

    data class Failure(val error: ProviderError) : ProviderCallResult<Nothing>
}

interface ProviderReadingGateway {
    suspend fun lookup(
        providerId: ProviderId,
        request: ProviderReadingLookupRequest,
    ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>>

    suspend fun chapters(
        providerId: ProviderId,
        request: ProviderReadingChaptersRequest,
    ): ProviderCallResult<ProviderPage<ProviderChapterObservation>>

    suspend fun pages(
        providerId: ProviderId,
        request: ProviderReadingPagesRequest,
    ): ProviderCallResult<ProviderReadingDelivery>
}

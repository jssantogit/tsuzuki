package tachiyomi.domain.tsuzuki.reader.model

import java.net.URI

data class PreparedHttpPage(
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
            "Prepared Reader page URL must be absolute HTTP(S) without credentials"
        }
        require(headers.size <= MAX_HEADERS) { "Prepared Reader page has too many headers" }
        require(allowedOrigins.isNotEmpty()) { "Prepared Reader page requires network authority" }
        require(allowedOrigins.size <= MAX_ORIGINS) { "Prepared Reader page has too many allowed origins" }
        require(allowedOrigins.all { it.isNotBlank() && it.length <= MAX_ORIGIN_CHARS }) {
            "Prepared Reader page origin authority is invalid"
        }
        require(
            headers.all { (name, value) ->
                HEADER_NAME.matches(name) &&
                    value.length <= MAX_HEADER_VALUE_CHARS &&
                    !value.contains('\r') &&
                    !value.contains('\n')
            },
        ) {
            "Prepared Reader page header is invalid"
        }
    }

    private companion object {
        const val MAX_HEADERS = 32
        const val MAX_HEADER_VALUE_CHARS = 8192
        const val MAX_ORIGINS = 64
        const val MAX_ORIGIN_CHARS = 2048
        val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
    }
}

/** Provider-neutral payload already prepared for a Reader transport. */
sealed interface PreparedChapterContent {
    data class HttpPages(
        val pages: List<PreparedHttpPage>,
    ) : PreparedChapterContent {
        init {
            require(pages.isNotEmpty()) { "Prepared Reader HTTP delivery must contain pages" }
            require(pages.size <= MAX_PAGES) { "Prepared Reader HTTP delivery contains too many pages" }
        }

        private companion object {
            const val MAX_PAGES = 5000
        }
    }

    data class MihonOperational(
        val mangaId: Long,
        val chapterId: Long,
        val sourceId: Long,
    ) : PreparedChapterContent

    data class LocalArchive(
        val uri: String,
    ) : PreparedChapterContent

    data class LocalDirectory(
        val uri: String,
    ) : PreparedChapterContent

    data class CanonicalDownload(
        val uri: String,
        val format: String,
    ) : PreparedChapterContent
}

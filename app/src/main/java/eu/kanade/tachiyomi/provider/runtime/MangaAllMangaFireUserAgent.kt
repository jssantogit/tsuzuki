package eu.kanade.tachiyomi.provider.runtime

import okhttp3.Request

/**
 * The signed MangaALL code10 MangaFire adapter supplies Accept but no User-Agent.
 * Keep its HTTPS API requests on the same UA family as its Android WebView.
 *
 * This is deliberately not a browser clearance bridge: no cookies, auth data,
 * WebView storage, extra requests or provider-script changes are involved.
 */
internal object MangaAllMangaFireUserAgent {
    private const val PROVIDER_ID = "app.tsuzuki.mangaall"
    private const val ORIGIN = "https://mangafire.to"
    private const val HOST = "mangafire.to"

    fun enabled(
        providerId: String,
        networkOrigins: Set<String>,
        browserOrigins: Set<String>,
    ): Boolean = providerId == PROVIDER_ID &&
        ORIGIN in networkOrigins &&
        ORIGIN in browserOrigins

    fun align(request: Request, webViewUserAgent: String): Request {
        val url = request.url
        if (url.scheme != "https" || url.host != HOST || url.port != 443 ||
            request.header("User-Agent") != null
        ) {
            return request
        }
        // A malformed or unavailable UA must never change the request.
        if (webViewUserAgent.length !in 1..512 ||
            !webViewUserAgent.startsWith("Mozilla/5.0") ||
            webViewUserAgent.any { it == '\r' || it == '\n' }
        ) {
            return request
        }
        return request.newBuilder()
            .header("User-Agent", webViewUserAgent)
            .build()
    }
}

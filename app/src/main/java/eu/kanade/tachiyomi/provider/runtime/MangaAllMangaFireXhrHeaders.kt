package eu.kanade.tachiyomi.provider.runtime

import okhttp3.Request

/**
 * The Keiyoushi MangaFire adapter sends its signed /api/ GETs as Fetch.XHR.
 * code10 sends only Accept. Preserve the signed URL and provider headers,
 * and add only the ordinary same-origin XHR request metadata it omitted.
 *
 * The caller MUST already hold both MangaALL MangaFire HTTP/browser grants.
 * This is not a browser session, cookie bridge, WAF solver or retry.
 */
internal object MangaAllMangaFireXhrHeaders {
    fun align(request: Request): Request {
        val url = request.url
        if (request.method != "GET" ||
            url.scheme != "https" ||
            url.host != "mangafire.to" ||
            url.port != 443 ||
            !url.encodedPath.startsWith("/api/")
        ) {
            return request
        }
        val builder = request.newBuilder()
        fun fill(name: String, value: String) {
            if (request.header(name) == null) builder.header(name, value)
        }
        fill("Referer", "https://mangafire.to/")
        fill("Sec-Fetch-Site", "same-origin")
        fill("Sec-Fetch-Mode", "cors")
        fill("Sec-Fetch-Dest", "empty")
        return builder.build()
    }
}

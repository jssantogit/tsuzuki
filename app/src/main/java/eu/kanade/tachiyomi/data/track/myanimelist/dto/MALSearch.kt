package eu.kanade.tachiyomi.data.track.myanimelist.dto

import eu.kanade.tachiyomi.data.track.myanimelist.MalSearchWindow
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class MALSearchResult(
    val data: List<MALSearchResultNode>,
    val paging: MALSearchPaging,
)

@Serializable
data class MALSearchResultNode(
    val node: MALManga,
)

@Serializable
data class MALSearchPaging(
    val next: String? = null,
)

/** Validate the server cursor without navigating a provider-supplied URL. */
internal fun MALSearchResult.toSearchWindow(trackerId: Long, offset: Int, limit: Int): MalSearchWindow {
    require(offset >= 0 && limit in 1..100)
    val items = data.map { it.node.toTrackSearch(trackerId) }
    check(items.size <= limit && items.all { it.remote_id > 0 })
    check(items.distinctBy { it.remote_id }.size == items.size)
    val next = paging.next
    if (next != null) {
        check(items.size == limit)
        val url = requireNotNull(next.toHttpUrlOrNull())
        check(url.scheme == "https" && url.host == "api.myanimelist.net" && url.encodedPath == "/v2/manga")
        check(url.queryParameterValues("offset").size == 1)
        check(url.queryParameter("offset")?.toLongOrNull() == offset.toLong() + items.size)
        check(url.queryParameterValues("limit").size <= 1)
        check(url.queryParameter("limit")?.let { it.toIntOrNull() == limit } != false)
    }
    return MalSearchWindow(items, next != null)
}

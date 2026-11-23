package eu.kanade.tachiyomi.provider.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * After a rejected MangaFire API main frame, reading some subsequent WebView
 * body is NOT enough. A challenge or generic HTML page may load successfully.
 *
 * This is deliberately a shallow transport sanity check. The original signed
 * provider remains responsible for source schema, identity and chapter proof.
 * Never log or retain the response body or any challenge material.
 */
internal object MangaAllMangaFireBrowserApiEvidence {
    // A challenge may redirect to another same-site URL that returns a
    // valid-looking JSON wrapper. It must never substitute for the signed
    // API URL that code10 requested, including its query and VRF signature.
    fun isOriginalApiResponse(requestedUrl: String, finalUrl: String, text: String): Boolean =
        requestedUrl == finalUrl && isApiJson(text)

    fun isApiJson(text: String): Boolean {
        val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return false
        if ("error" in value) return false
        return value["items"] is JsonArray || value["data"] is JsonObject
    }
}

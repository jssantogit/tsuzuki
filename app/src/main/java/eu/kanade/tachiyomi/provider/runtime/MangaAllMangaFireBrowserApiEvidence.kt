package eu.kanade.tachiyomi.provider.runtime

import org.json.JSONObject
import org.json.JSONTokener

/**
 * After a rejected MangaFire API main frame, reading some subsequent WebView
 * body is NOT enough. A challenge or generic HTML page may load successfully.
 *
 * This is deliberately a shallow transport sanity check. The original signed
 * provider remains responsible for source schema, identity and chapter proof.
 * Never log or retain the response body or any challenge material.
 */
internal object MangaAllMangaFireBrowserApiEvidence {
    fun isApiJson(text: String): Boolean {
        val value = try {
            JSONTokener(text).nextValue() as? JSONObject
        } catch (_: Exception) {
            null
        } ?: return false
        if (value.has("error")) return false
        return value.optJSONArray("items") != null || value.optJSONObject("data") != null
    }
}

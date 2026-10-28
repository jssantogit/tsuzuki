package eu.kanade.tachiyomi.data.tsuzuki.inventory

/**
 * MangaUpdates publishes a numeric API series ID but external MangaDex links
 * may use the site's base-36 slug. Normalize before comparing trusted IDs.
 * Never treat an invalid or overflowing slug as an identity.
 */
internal fun normalizeMangaUpdatesWorkId(value: String?): String? {
    val raw = value?.trim() ?: return null
    val parsed = when {
        raw.matches(Regex("[0-9]{1,18}")) -> raw.toLongOrNull()
        raw.matches(Regex("[a-zA-Z0-9]{2,12}")) && raw.any(Char::isLetter) ->
            raw.lowercase().toLongOrNull(36)
        else -> null
    }
    return parsed?.takeIf { it > 0L }?.toString()
}

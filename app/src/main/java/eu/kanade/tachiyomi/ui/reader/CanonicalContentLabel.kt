package eu.kanade.tachiyomi.ui.reader

import tachiyomi.domain.tsuzuki.content.ContentOption

/** An explicit active source label for both manually and automatically resolved chapters. */
internal fun canonicalContentLabel(
    sourceName: String?,
    option: ContentOption?,
    fallbackLabel: String = "Local",
): String = listOfNotNull(
    sourceName ?: option?.addonId?.value ?: fallbackLabel,
    option?.language?.takeIf(String::isNotBlank),
    option?.scanlationGroup?.takeIf(String::isNotBlank),
).joinToString(" · ")

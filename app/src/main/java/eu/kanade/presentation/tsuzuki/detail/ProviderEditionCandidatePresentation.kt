package eu.kanade.presentation.tsuzuki.detail

import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate
import java.util.Locale

/**
 * Informational source metadata only. These labels never establish chapter
 * completeness, canonical identity, translation equivalence or Reader authority.
 */
internal fun editionCandidateMetadata(candidate: ProviderEditionCandidate): List<String> {
    // These vals are stable for Kotlin smart casts across the Domain/App module boundary.
    val reported = candidate.chapterCount
    val observed = candidate.observedChapterCount
    val hasMore = candidate.coverageHasMore
    val chapters = when {
        reported != null && (observed == null || observed <= reported) ->
            "$reported informados pela fonte"
        observed != null && reported != null ->
            "$observed observados (contagem informada divergente)"
        observed != null && hasMore == true ->
            "ao menos $observed observados (parcial)"
        observed != null -> "$observed observados"
        else -> "não informado"
    }

    val language = candidate.language
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { tag ->
            val localized = Locale.forLanguageTag(tag.replace('_', '-'))
                .getDisplayName(Locale.forLanguageTag("pt-BR"))
            (localized.takeIf(String::isNotBlank) ?: tag).replaceFirstChar(Char::uppercaseChar)
        } ?: "não informado"

    val authors = candidate.authors.map(String::trim).filter(String::isNotBlank).distinct()
    val authorText = when {
        authors.isEmpty() -> "Autor: não informado"
        authors.size == 1 -> "Autor: ${authors.single()}"
        authors.size <= 2 -> "Autores: ${authors.joinToString(", ")}"
        else -> "Autores: ${authors.take(2).joinToString(", ")} e mais ${authors.size - 2}"
    }

    val sourceLabel = when (candidate.contentSourceId) {
        "mangadex" -> "Fonte: MangaDex"
        "mangadot" -> "Fonte: MangaDot"
        "mangafire" -> "Fonte: MangaFire"
        else -> null
    }
    return listOfNotNull(
        sourceLabel,
        "Capítulos: $chapters",
        "Idioma: $language",
        authorText,
    )
}

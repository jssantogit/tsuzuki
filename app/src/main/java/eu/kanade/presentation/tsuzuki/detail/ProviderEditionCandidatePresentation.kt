package eu.kanade.presentation.tsuzuki.detail

import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate
import java.util.Locale

/**
 * Informational source metadata only. These labels never establish chapter
 * completeness, canonical identity, translation equivalence or Reader authority.
 */
internal fun editionCandidateMetadata(candidate: ProviderEditionCandidate): List<String> {
    val chapters = when {
        candidate.chapterCount != null &&
            (candidate.observedChapterCount == null || candidate.observedChapterCount <= candidate.chapterCount) ->
            "${candidate.chapterCount} informados pela fonte"
        candidate.observedChapterCount != null && candidate.chapterCount != null ->
            "${candidate.observedChapterCount} observados (contagem informada divergente)"
        candidate.observedChapterCount != null && candidate.coverageHasMore == true ->
            "ao menos ${candidate.observedChapterCount} observados (parcial)"
        candidate.observedChapterCount != null -> "${candidate.observedChapterCount} observados"
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

    return listOf(
        "Capítulos: $chapters",
        "Idioma: $language",
        authorText,
    )
}

package eu.kanade.presentation.tsuzuki.detail

import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionChapterInspection
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
            "ao menos $observed observados (amostra parcial)"
        observed != null -> "$observed observados"
        else -> "não verificados; selecione para consultar"
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
    // The lookup facet is what the user REQUESTED, not proof that a manga
    // actually has translated chapters. Show it independently from the
    // source-attested language so two MangaAll candidates remain distinguishable.
    val requestedLanguage = if (sourceLabel != null) {
        candidate.ref.facetId?.trim()?.takeIf(String::isNotBlank)?.let { tag ->
            Locale.forLanguageTag(tag.replace('_', '-'))
                .getDisplayName(Locale.forLanguageTag("pt-BR"))
                .replaceFirstChar(Char::uppercaseChar)
        } ?: "não informado"
    } else {
        null
    }

    // MangaAll results often have no source-attested work metadata. Do not
    // fill candidate cards with repeated "não informado" rows: absent data
    // remains unknown rather than being rendered as empty promises.
    val hasContentSource = sourceLabel != null
    return listOfNotNull(
        sourceLabel,
        "Capítulos: $chapters",
        observedChapterRange(candidate.coverageMinMilli?.toLong(), candidate.coverageMaxMilli?.toLong()),
        requestedLanguage?.let { "Idioma solicitado: $it" },
        "Idioma: $language".takeUnless { hasContentSource && candidate.language.isNullOrBlank() },
        authorText.takeUnless { hasContentSource && authors.isEmpty() },
    )
}

/** A number observed in a source's bounded preview is not the total chapter count. */
private fun observedChapterRange(min: Long?, max: Long?): String? {
    if (min == null || max == null) return null
    val start = formatSourceChapterMilli(min)
    val end = formatSourceChapterMilli(max)
    return if (min == max) {
        "Capítulo observado: $start"
    } else {
        "Intervalo observado: caps. $start–$end"
    }
}

private fun formatSourceChapterMilli(milli: Long): String {
    val remainder = (milli % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (remainder.isEmpty()) "${milli / 1000}" else "${milli / 1000}.$remainder"
}

/** Full source scan is opt-in and read-only. It never certifies an editorial edition. */
internal fun editionChapterInspectionMetadata(inspection: ProviderEditionChapterInspection): List<String> {
    val count = when {
        inspection.complete -> "Registros de capítulos: ${inspection.observedRecords} (consulta completa da fonte)"
        inspection.observedRecords > 0 ->
            "Registros de capítulos: ao menos ${inspection.observedRecords} (consulta parcial)"
        else -> "Não foi possível verificar a quantidade de capítulos"
    }
    return listOfNotNull(
        count,
        observedChapterRange(inspection.minChapterMilli, inspection.maxChapterMilli),
    )
}

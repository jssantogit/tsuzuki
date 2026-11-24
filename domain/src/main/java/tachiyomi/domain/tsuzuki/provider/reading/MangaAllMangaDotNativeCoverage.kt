package tachiyomi.domain.tsuzuki.provider.reading

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Projects a genuinely observed first chapter page from the experimental
 * code11 MangaDot native snapshot into bounded work-level coverage evidence.
 *
 * The caller MUST supply the result of the exact [binding] chapter request,
 * not a source lookup count, fixture in a live run, or another HID's page.
 * This is not an edition association, chapter mapping, or Reader authority.
 */
object MangaAllMangaDotNativeCoverage {
    fun fromFirstPage(
        artifactVersionCode: Long,
        binding: ProviderBindingRef,
        candidate: ProviderWorkCandidate,
        page: ProviderPage<ProviderChapterObservation>,
    ): ProviderWorkCandidate? {
        if (artifactVersionCode != 11L ||
            binding.providerId.value != "app.tsuzuki.mangaall" ||
            binding.facetId !in setOf("en", "pt-br") ||
            candidate.externalWorkId != binding.externalWorkId ||
            !WORK_ID.matches(binding.externalWorkId) ||
            candidate.detailOutcome !in VERIFIED_DETAILS ||
            candidate.authors.isEmpty()
        ) {
            return null
        }
        val sourceId = binding.externalWorkId.removePrefix("mangadot:")
        if (candidate.url != "https://mangadot.net/manga/$sourceId" &&
            candidate.url != "https://mangadot.net/manga/$sourceId/"
        ) {
            return null
        }
        if (page.items.isEmpty() || page.items.size > 256 || page.parallelCursors.isNotEmpty()) return null
        val seen = mutableSetOf<String>()
        val numbers = mutableListOf<Int>()
        for (row in page.items) {
            if (!CHAPTER_ID.matches(row.providerChapterId) || !seen.add(row.providerChapterId)) return null
            if (row.language != null && !row.language.equals(binding.facetId, ignoreCase = true)) return null
            val value = row.rawNumber ?: continue
            if (value !in 0.0..1_000_000.0) return null
            val scaled = value * 1000
            val milli = scaled.roundToInt()
            if (abs(scaled - milli) > 0.000001) return null
            // The scoped endpoint alone is not explicit evidence of the row's
            // language. Keep unknown rows unknown; do not manufacture a facet.
            if (row.language != null && numbers.size < MAX_PREVIEW_ROWS) numbers += milli
        }
        if (numbers.isEmpty()) return null
        return candidate.copy(
            coverageOutcome = ProviderCoverageOutcome.OBSERVED,
            coverageObservedCount = numbers.size,
            coverageMinMilli = numbers.min(),
            coverageMaxMilli = numbers.max(),
            coverageHasMore = page.nextCursor != null || page.items.size > MAX_PREVIEW_ROWS,
        )
    }

    private const val MAX_PREVIEW_ROWS = 200
    private val WORK_ID = Regex("mangadot:[1-9][0-9]{0,15}")
    private val CHAPTER_ID = Regex("mangadot:(uploads|chapters):[1-9][0-9]{0,15}")
    private val VERIFIED_DETAILS = setOf(
        ProviderWorkDetailOutcome.VERIFIED_NO_ALIASES,
        ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES,
    )
}

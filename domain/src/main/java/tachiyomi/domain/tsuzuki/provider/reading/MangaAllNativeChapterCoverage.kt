package tachiyomi.domain.tsuzuki.provider.reading

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Trusted adapter contract for native chapter evidence. Version and URL
 * constraints belong to the adapter; row validation belongs to the motor.
 * A contract is not an installation grant or editorial identity proof.
 */
internal data class NativeSourceCoverageContract(
    val sourceId: String,
    val versions: Set<Long>,
    val languages: Set<String>,
    val workKey: Regex,
    val chapterKey: Regex,
    val workUrl: (String, String?) -> Boolean,
) {
    fun supports(version: Long, binding: ProviderBindingRef): Boolean =
        binding.providerId.value == "app.tsuzuki.mangaall" &&
            version in versions &&
            binding.facetId in languages &&
            binding.externalWorkId.substringBefore(':') == sourceId &&
            workKey.matches(binding.externalWorkId.substringAfter(':', ""))
}

internal class NativeChapterCoveragePolicy(contracts: List<NativeSourceCoverageContract>) {
    private val bySource = contracts.associateBy { it.sourceId }

    init {
        require(bySource.size == contracts.size)
    }

    fun supports(version: Long, binding: ProviderBindingRef): Boolean =
        bySource[binding.externalWorkId.substringBefore(':')]?.supports(version, binding) == true

    fun sourceIds(version: Long, language: String?): Set<String> =
        bySource.values.filter { version in it.versions && language in it.languages }.map { it.sourceId }.toSet()

    fun project(
        version: Long,
        binding: ProviderBindingRef,
        candidate: ProviderWorkCandidate,
        page: ProviderPage<ProviderChapterObservation>,
        independentlyCorroboratedWithoutAuthors: Boolean = false,
    ): ProviderWorkCandidate? {
        val contract = bySource[binding.externalWorkId.substringBefore(':')] ?: return null
        if (!contract.supports(version, binding) ||
            candidate.externalWorkId != binding.externalWorkId ||
            candidate.detailOutcome !in VERIFIED_DETAILS ||
            (candidate.authors.isEmpty() && !independentlyCorroboratedWithoutAuthors) ||
            !contract.workUrl(binding.externalWorkId.substringAfter(':'), candidate.url)
        ) {
            return null
        }
        if (page.items.isEmpty() || page.items.size > 256 || page.parallelCursors.isNotEmpty()) return null
        val seen = mutableSetOf<String>()
        val numbers = mutableListOf<Int>()
        for (row in page.items) {
            val source = row.providerChapterId.substringBefore(':', "")
            val key = row.providerChapterId.substringAfter(':', "")
            if (source != contract.sourceId || !contract.chapterKey.matches(key) || !seen.add(row.providerChapterId)) {
                return null
            }
            if (row.language != null && !row.language.equals(binding.facetId, ignoreCase = true)) return null
            val value = row.rawNumber ?: continue
            if (value !in 0.0..1_000_000.0) return null
            val scaled = value * 1000
            val milli = scaled.roundToInt()
            if (abs(scaled - milli) > 0.000001) return null
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

    private companion object {
        const val MAX_PREVIEW_ROWS = 200
        val VERIFIED_DETAILS = setOf(
            ProviderWorkDetailOutcome.VERIFIED_NO_ALIASES,
            ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES,
        )
    }
}

/** Reviewed source descriptors. Adding a source does not change the projector. */
object MangaAllNativeChapterCoverage {
    private val policy = NativeChapterCoveragePolicy(
        listOf(
            NativeSourceCoverageContract(
                sourceId = "mangadot",
                versions = setOf(11L),
                languages = setOf("en", "pt-br"),
                workKey = Regex("[1-9][0-9]{0,15}"),
                chapterKey = Regex("(uploads|chapters):[1-9][0-9]{0,15}"),
                workUrl = { id, url ->
                    url == "https://mangadot.net/manga/$id" || url == "https://mangadot.net/manga/$id/"
                },
            ),
        ),
    )

    fun supports(version: Long, binding: ProviderBindingRef): Boolean = policy.supports(version, binding)

    fun sourceIds(version: Long, language: String?): Set<String> = policy.sourceIds(version, language)

    internal fun project(
        version: Long,
        binding: ProviderBindingRef,
        candidate: ProviderWorkCandidate,
        page: ProviderPage<ProviderChapterObservation>,
        independentlyCorroboratedWithoutAuthors: Boolean = false,
    ): ProviderWorkCandidate? =
        policy.project(version, binding, candidate, page, independentlyCorroboratedWithoutAuthors)
}

package eu.kanade.tachiyomi.provider.reading

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingNoMatchReason
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading

/**
 * Read-only Reader source gate on genuinely persisted canonical chapters.
 *
 * Samples uniformly across the SQL inventory plus exceptional number/part
 * boundaries. Does NOT select an edition or invoke pages(): CDN/headers were
 * intentionally redacted from live captures. A verified work or chapter alone
 * never establishes Reader page delivery or visible pixels.
 */
internal object MangaAllReaderSqliteProbe {
    internal data class Report(
        val totalSqlChapters: Int,
        val inspectedChapters: Int,
        val withOneOption: Int,
        val ambiguousOptions: Int,
        val withoutOptions: Int,
        val unavailableReasons: String,
    ) {
        init {
            require(withOneOption + ambiguousOptions + withoutOptions == inspectedChapters)
            require(inspectedChapters <= totalSqlChapters)
        }

        val sourceStage: String
            get() = when {
                totalSqlChapters == 0 -> "READER_NO_SQL_CHAPTERS"
                withOneOption == 0 && ambiguousOptions == 0 -> "READER_NO_ELIGIBLE_OPTIONS"
                ambiguousOptions > 0 -> "READER_OPTIONS_AMBIGUOUS"
                withoutOptions > 0 -> "READER_OPTIONS_PARTIAL"
                else -> "READER_OPTIONS_SAMPLED"
            }
    }

    internal fun sample(chapters: List<CanonicalChapter>, max: Int = 12): List<CanonicalChapter> {
        require(max in 1..32)
        if (chapters.size <= max) return chapters
        val selected = linkedSetOf<Int>()
        val spread = minOf(max - 3, chapters.size)
        for (position in 0 until spread) {
            selected += position * (chapters.lastIndex) / (spread - 1).coerceAtLeast(1)
        }
        for (position in chapters.indices) {
            val chapter = chapters[position]
            if (chapter.baseNumber == 0 ||
                chapter.part != null ||
                chapter.decimalFraction != null ||
                chapter.alphaSuffix != null
            ) {
                selected += position
                if (selected.size >= max) break
            }
        }
        selected += chapters.lastIndex
        return selected.sorted().take(max).map(chapters::get)
    }

    internal suspend fun inspect(
        chapters: List<CanonicalChapter>,
        resolver: ResolveProviderChapterReading,
    ): Report {
        val candidates = sample(chapters)
        var unique = 0
        var multiple = 0
        var absent = 0
        val reasons = mutableListOf<ProviderReadingNoMatchReason>()
        for (chapter in candidates) {
            val options = resolver.options(chapter.id)
            check(options.all { it.canonicalChapterId == chapter.id }) {
                "READER_SOURCE_CROSSED_CANONICAL_CHAPTER"
            }
            when {
                options.isEmpty() -> {
                    absent++
                    reasons += resolver.auditNoMatch(chapter.id).reason
                }
                options.size == 1 -> unique++
                else -> multiple++
            }
        }
        return Report(
            totalSqlChapters = chapters.size,
            inspectedChapters = candidates.size,
            withOneOption = unique,
            ambiguousOptions = multiple,
            withoutOptions = absent,
            unavailableReasons = reasons.groupingBy { it }.eachCount().entries
                .sortedBy { it.key.name }
                .joinToString(",") { "${it.key.name}:${it.value}" }
                .ifEmpty { "NONE" },
        )
    }
}

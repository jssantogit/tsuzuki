package tachiyomi.domain.tsuzuki.chapter.model

import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation

/** A Tsuzuki-owned logical chapter, independent of any source release. */
data class CanonicalChapter(
    val id: String,
    val canonicalTitleId: String,
    val displayNumber: String,
    val volume: Int? = null,
    val title: String? = null,
    val type: CanonicalChapterType = CanonicalChapterType.UNKNOWN,
    val baseNumber: Int? = null,
    val part: Int? = null,
    val alphaSuffix: String? = null,
    val confidence: Double = 0.0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val confirmation: CanonicalChapterConfirmation = CanonicalChapterConfirmation.PROVISIONAL,
    /** Null identifies legacy rows; exact numbering is activated only with explicit trusted evidence. */
    val numberingKind: CanonicalChapterNumberingKind? = null,
    val decimalFraction: CanonicalChapterDecimalFraction? = null,
) {

    val identity: CanonicalChapterIdentity
        get() = CanonicalChapterIdentity(
            type = type,
            baseNumber = baseNumber,
            part = part,
            alphaSuffix = alphaSuffix,
            numberingKind = numberingKind,
            decimalFraction = decimalFraction,
        )

    /** Ordering is always derived from the structured canonical identity. */
    val sortKey: String
        get() = identity.sortKey
}

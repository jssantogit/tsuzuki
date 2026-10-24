package tachiyomi.domain.tsuzuki.chapter.model

/**
 * Opt-in exact chapter numbering for v2 data. Null retains legacy semantics.
 * Existing rows must not be assigned a kind without unambiguous raw evidence.
 */
enum class CanonicalChapterNumberingKind {
    DECIMAL,
    ORDINAL_PART,
}

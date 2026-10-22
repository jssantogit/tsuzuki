package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.orderCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalChapterRepositoryImpl(
    private val database: Database,
) : CanonicalChapterRepository {

    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> {
        return database.tsuzuki_canonical_chaptersQueries
            .getTsuzukiCanonicalChaptersByTitle(canonicalTitleId, ::mapChapter)
            .awaitAsList()
            .let(::orderCanonicalChapters)
    }

    override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> {
        return database.tsuzuki_canonical_chaptersQueries
            .getTsuzukiCanonicalChaptersByTitle(canonicalTitleId, ::mapChapter)
            .subscribeToList()
            .map(::orderCanonicalChapters)
    }

    override suspend fun getById(id: String): CanonicalChapter? {
        return database.tsuzuki_canonical_chaptersQueries
            .getTsuzukiCanonicalChapterById(id, ::mapChapter)
            .awaitAsOneOrNull()
    }

    override suspend fun getVariantBySourceIdentity(
        sourceId: Long,
        sourceChapterId: String,
    ): ChapterVariant? {
        return database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantBySourceIdentity(
                sourceId = sourceId,
                sourceChapterId = sourceChapterId,
                mapper = ::mapVariant,
            )
            .awaitAsOneOrNull()
    }

    override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> {
        return database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantsByCanonicalChapter(canonicalChapterId, ::mapVariant)
            .awaitAsList()
    }

    override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> {
        return database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantsBySourceMapping(sourceMappingId, ::mapVariant)
            .awaitAsList()
    }

    override suspend fun upsert(chapter: CanonicalChapter) {
        database.transaction {
            validateExactChapterWrite(chapter)
            database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
                id = chapter.id,
                canonicalTitleId = chapter.canonicalTitleId,
                displayNumber = chapter.displayNumber,
                volume = chapter.volume?.toLong(),
                title = chapter.title,
                type = chapter.type.name,
                baseNumber = chapter.baseNumber?.toLong(),
                part = chapter.part?.toLong(),
                alphaSuffix = chapter.alphaSuffix,
                confidence = chapter.confidence,
                createdAt = chapter.createdAt,
                updatedAt = chapter.updatedAt,
                confirmationState = chapter.confirmation.name,
                numberingKind = chapter.numberingKind?.name,
                decimalNumerator = chapter.decimalFraction?.numerator?.toLong(),
                decimalScale = chapter.decimalFraction?.scale?.toLong(),
            )
        }
    }

    override suspend fun upsertVariant(variant: ChapterVariant) {
        upsertVariantInternal(variant)
    }

    override suspend fun consolidateExactDuplicateIfSafe(
        canonicalTitleId: String,
        preferredChapterId: String,
        duplicateChapterId: String,
    ): Boolean {
        require(preferredChapterId != duplicateChapterId) { "Duplicate chapter ids must differ" }
        val preferred = getById(preferredChapterId) ?: return false
        val duplicate = getById(duplicateChapterId) ?: return false
        require(preferred.canonicalTitleId == canonicalTitleId && duplicate.canonicalTitleId == canonicalTitleId) {
            "Duplicate chapters must belong to the requested canonical title"
        }
        require(
            preferred.type == CanonicalChapterType.REGULAR &&
                duplicate.type == CanonicalChapterType.REGULAR &&
                preferred.identity == duplicate.identity &&
                preferred.volume == duplicate.volume,
        ) { "Only exact numbered regular chapter duplicates may be consolidated" }

        val protectedState = database.tsuzuki_canonical_chaptersQueries
            .countTsuzukiCanonicalChapterProtectedState(duplicateChapterId)
            .awaitAsOne()
        if (protectedState > 0L) return false

        database.transaction {
            database.tsuzuki_chapter_evidenceQueries.rekeyTsuzukiChapterEvidenceMapping(
                preferredChapterId = preferredChapterId,
                duplicateChapterId = duplicateChapterId,
            )
            database.tsuzuki_chapter_variantsQueries.rekeyTsuzukiChapterVariantsCanonicalChapter(
                preferredChapterId = preferredChapterId,
                duplicateChapterId = duplicateChapterId,
            )
            database.tsuzuki_canonical_chaptersQueries.deleteTsuzukiCanonicalChapterById(duplicateChapterId)
        }
        return true
    }

    override suspend fun upsertBatch(
        chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    ) {
        database.transaction {
            chapters.forEach { chapter ->
                validateExactChapterWrite(chapter)
                database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
                    id = chapter.id,
                    canonicalTitleId = chapter.canonicalTitleId,
                    displayNumber = chapter.displayNumber,
                    volume = chapter.volume?.toLong(),
                    title = chapter.title,
                    type = chapter.type.name,
                    baseNumber = chapter.baseNumber?.toLong(),
                    part = chapter.part?.toLong(),
                    alphaSuffix = chapter.alphaSuffix,
                    confidence = chapter.confidence,
                    createdAt = chapter.createdAt,
                    updatedAt = chapter.updatedAt,
                    confirmationState = chapter.confirmation.name,
                    numberingKind = chapter.numberingKind?.name,
                    decimalNumerator = chapter.decimalFraction?.numerator?.toLong(),
                    decimalScale = chapter.decimalFraction?.scale?.toLong(),
                )
            }
            variants.forEach { variant -> upsertVariantInternal(variant) }
        }
    }

    /**
     * Surface a deterministic conflict to repository callers. The SQLDelight
     * upsert has its own atomic WHERE guard, so another writer racing this
     * read cannot silently replace an already stored exact identity.
     */
    private suspend fun validateExactChapterWrite(chapter: CanonicalChapter) {
        if (chapter.numberingKind != null) {
            // Validate newly constructed exact rows before persisting them.
            chapter.identity
        }
        val existing = getById(chapter.id) ?: return
        if (existing.numberingKind != null) {
            check(existing.identity == chapter.identity && existing.volume == chapter.volume) {
                "Cannot rewrite exact chapter identity for ${chapter.id}"
            }
        }
    }

    private suspend fun upsertVariantInternal(variant: ChapterVariant) {
        val existing = database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantBySourceIdentity(
                sourceId = variant.sourceId,
                sourceChapterId = variant.sourceChapterId,
                mapper = ::mapVariant,
            )
            .awaitAsOneOrNull()
        val stableVariant = if (existing == null || existing.id == variant.id) {
            variant
        } else {
            variant.copy(id = existing.id)
        }
        database.tsuzuki_chapter_variantsQueries.upsertTsuzukiChapterVariant(
            id = stableVariant.id,
            canonicalChapterId = stableVariant.canonicalChapterId,
            sourceMappingId = stableVariant.sourceMappingId,
            sourceId = stableVariant.sourceId,
            mihonMangaId = stableVariant.mihonMangaId,
            mihonChapterId = stableVariant.mihonChapterId,
            sourceChapterId = stableVariant.sourceChapterId,
            sourceChapterUrl = stableVariant.sourceChapterUrl,
            language = stableVariant.language,
            scanlationGroup = stableVariant.scanlationGroup,
            version = stableVariant.version,
            releaseDate = stableVariant.releaseDate,
            rawName = stableVariant.rawName,
            rawNumberHint = stableVariant.rawNumberHint,
            rawSourceOrder = stableVariant.rawSourceOrder,
            rawSourceMetadata = MemoColumnAdapter.encode(stableVariant.rawSourceMetadata),
            createdAt = stableVariant.createdAt,
            updatedAt = stableVariant.updatedAt,
        )
    }

    private fun mapChapter(
        id: String,
        canonicalTitleId: String,
        displayNumber: String,
        volume: Long?,
        title: String?,
        type: String,
        baseNumber: Long?,
        part: Long?,
        alphaSuffix: String?,
        confidence: Double,
        createdAt: Long,
        updatedAt: Long,
        confirmationState: String,
        numberingKind: String?,
        decimalNumerator: Long?,
        decimalScale: Long?,
    ): CanonicalChapter {
        if (numberingKind != null) {
            // Never narrow a corrupted SQLite INTEGER into another valid v2
            // chapter number. Legacy rows retain their historical decoding.
            check(baseNumber != null && baseNumber in 0L..Int.MAX_VALUE.toLong()) {
                "Out-of-range exact chapter base number for chapter $id"
            }
            check(part != null && part in 0L..Int.MAX_VALUE.toLong()) {
                "Out-of-range exact chapter part for chapter $id"
            }
            check(volume == null || volume in 0L..Int.MAX_VALUE.toLong()) {
                "Out-of-range exact chapter volume for chapter $id"
            }
        }
        val fraction = decodeStoredDecimalFraction(
            chapterId = id,
            numberingKind = numberingKind,
            decimalNumerator = decimalNumerator,
            decimalScale = decimalScale,
        )
        return CanonicalChapter(
            id = id,
            canonicalTitleId = canonicalTitleId,
            displayNumber = displayNumber,
            volume = volume?.toInt(),
            title = title,
            type = CanonicalChapterType.valueOf(type),
            baseNumber = baseNumber?.toInt(),
            part = part?.toInt(),
            alphaSuffix = alphaSuffix,
            confidence = confidence,
            createdAt = createdAt,
            updatedAt = updatedAt,
            confirmation = CanonicalChapterConfirmation.valueOf(confirmationState),
            numberingKind = numberingKind?.let(CanonicalChapterNumberingKind::valueOf),
            decimalFraction = fraction,
        ).also { chapter ->
            if (chapter.numberingKind != null) {
                try {
                    chapter.identity
                } catch (error: IllegalArgumentException) {
                    throw IllegalStateException("Stored exact chapter identity is inconsistent for chapter $id", error)
                }
            }
        }
    }

    private fun decodeStoredDecimalFraction(
        chapterId: String,
        numberingKind: String?,
        decimalNumerator: Long?,
        decimalScale: Long?,
    ): CanonicalChapterDecimalFraction? {
        if (numberingKind != CanonicalChapterNumberingKind.DECIMAL.name) {
            check(decimalNumerator == null && decimalScale == null) {
                "Unexpected decimal metadata on non-decimal chapter $chapterId"
            }
            return null
        }

        check(decimalNumerator != null && decimalScale != null) {
            "Incomplete exact decimal metadata for chapter $chapterId"
        }
        check(decimalNumerator in 1L..Int.MAX_VALUE.toLong() && decimalScale in 1L..9L) {
            "Out-of-range exact decimal metadata for chapter $chapterId"
        }
        return try {
            CanonicalChapterDecimalFraction(decimalNumerator.toInt(), decimalScale.toInt())
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Invalid exact decimal metadata for chapter $chapterId", error)
        }
    }

    private fun mapVariant(
        id: String,
        canonicalChapterId: String,
        sourceMappingId: String,
        sourceId: Long,
        mihonMangaId: Long?,
        mihonChapterId: Long?,
        sourceChapterId: String,
        sourceChapterUrl: String?,
        language: String,
        scanlationGroup: String?,
        version: Long?,
        releaseDate: Long?,
        rawName: String,
        rawNumberHint: Double?,
        rawSourceOrder: Long?,
        rawSourceMetadata: ByteArray,
        createdAt: Long,
        updatedAt: Long,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = canonicalChapterId,
        sourceMappingId = sourceMappingId,
        sourceId = sourceId,
        mihonMangaId = mihonMangaId,
        mihonChapterId = mihonChapterId,
        sourceChapterId = sourceChapterId,
        sourceChapterUrl = sourceChapterUrl,
        language = language,
        scanlationGroup = scanlationGroup,
        version = version,
        releaseDate = releaseDate,
        rawName = rawName,
        rawNumberHint = rawNumberHint,
        rawSourceOrder = rawSourceOrder,
        rawSourceMetadata = MemoColumnAdapter.decode(rawSourceMetadata),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

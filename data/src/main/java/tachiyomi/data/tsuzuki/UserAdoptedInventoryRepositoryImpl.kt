package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.PreviewUserAdoptedInventory
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionConfirmation
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionPreview
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionReviewRequest
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import java.util.UUID

/**
 * User approval is an explicit local decision, not producer-supplied editorial
 * trust. The persisted ledger is re-read and revalidated under the chapter
 * mutation gate AND SQLite transaction immediately before each INSERT.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class UserAdoptedInventoryRepositoryImpl(
    private val database: Database,
    private val ledger: InventoryEvidenceRepository,
    private val chapters: CanonicalChapterRepository,
    private val mutationGate: ChapterMutationGate,
) : UserAdoptionRepository {
    private val planner = PreviewUserAdoptedInventory()

    override suspend fun adoptedChapterIds(canonicalTitleId: String): Set<String> =
        database.tsuzuki_user_adoption_receiptsQueries
            .getTsuzukiUserAdoptedChapterIdsByTitle(canonicalTitleId)
            .awaitAsList()
            .toSet()

    override suspend fun preview(request: UserAdoptionReviewRequest): UserAdoptionPreview =
        planner.execute(
            scope = request.scope,
            storedClaims = ledger.byTitle(request.scope.canonicalTitleId),
            selectedSourceRecordIds = request.sourceRecordIds,
            existingChapters = chapters.getByCanonicalTitleId(request.scope.canonicalTitleId),
        )

    override suspend fun adopt(confirmation: UserAdoptionConfirmation): UserAdoptionCommitResult {
        if (
            !confirmation.explicitlyConfirmed ||
            confirmation.acceptedAt < 0L ||
            confirmation.reviewedFingerprint.isBlank()
        ) {
            return UserAdoptionCommitResult.Refused
        }

        return try {
            mutationGate.withLock {
                database.transactionWithResult {
                    currentCoroutineContext().ensureActive()
                    val request = confirmation.request
                    // Only the persisted source ledger is consulted. Caller
                    // cannot fabricate a review or substitute a new revision.
                    val actual = preview(request) as? UserAdoptionPreview.Ready
                        ?: throw AdoptionRefused()
                    if (actual.fingerprint != confirmation.reviewedFingerprint) throw AdoptionRefused()

                    val receiptId = UUID.randomUUID().toString()
                    val scope = actual.scope
                    val adopted = actual.units.map { unit ->
                        CanonicalChapter(
                            id = UUID.randomUUID().toString(),
                            canonicalTitleId = scope.canonicalTitleId,
                            displayNumber = unit.label,
                            volume = unit.volume,
                            type = CanonicalChapterType.REGULAR,
                            baseNumber = unit.identity.baseNumber,
                            part = unit.identity.part,
                            alphaSuffix = unit.identity.alphaSuffix,
                            confidence = 1.0,
                            createdAt = confirmation.acceptedAt,
                            updatedAt = confirmation.acceptedAt,
                            // Confirmation here is the user's explicit decision.
                            // The durable receipt distinguishes it from editorial authority.
                            confirmation = CanonicalChapterConfirmation.CONFIRMED,
                            numberingKind = unit.identity.numberingKind,
                            decimalFraction = unit.identity.decimalFraction,
                        )
                    }
                    chapters.upsertBatch(adopted, emptyList())
                    adopted.zip(actual.units).forEach { (chapter, unit) ->
                        val source = unit.provenance
                        database.tsuzuki_user_adoption_receiptsQueries.insertTsuzukiUserAdoptionReceipt(
                            canonicalChapterId = chapter.id,
                            receiptId = receiptId,
                            canonicalTitleId = scope.canonicalTitleId,
                            producerId = scope.producerId,
                            externalWorkId = scope.externalWorkId,
                            editionKey = requireNotNull(scope.editionKey),
                            scopeLanguage = requireNotNull(scope.language),
                            sourceRecordId = unit.sourceRecordId,
                            sourceKind = source.sourceKind.name,
                            derivation = unit.derivation.name,
                            originReference = source.originReference,
                            exactRevision = source.exactRevision,
                            attributionReference = source.attributionReference,
                            licenseReference = source.licenseReference,
                            snapshotDigest = source.snapshotDigest,
                            sourceUnitId = source.sourceUnitId,
                            structuralCategory = source.structuralCategory,
                            acceptedLabel = unit.label,
                            identityKey = unit.identity.sortKey,
                            volumeKey = (unit.volume ?: 0).toLong(),
                            observedAt = unit.observedAt,
                            acceptedAt = confirmation.acceptedAt,
                            reviewFingerprint = actual.fingerprint,
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    UserAdoptionCommitResult.Adopted(receiptId, adopted.map(CanonicalChapter::id))
                }
            }
        } catch (_: AdoptionRefused) {
            UserAdoptionCommitResult.Refused
        }
    }

    private class AdoptionRefused : IllegalStateException("User-adoption review is no longer valid")
}

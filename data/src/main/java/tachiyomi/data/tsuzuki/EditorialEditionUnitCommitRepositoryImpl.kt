package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.inventory.EditorialEditionPreflight
import tachiyomi.domain.tsuzuki.chapter.inventory.EditorialEditionPreviewUnit
import tachiyomi.domain.tsuzuki.chapter.inventory.EditorialUnitCommitRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.EditorialUnitCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.HostEditorialEditionAuthorization
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.PreviewEditorialEditionInventory
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import java.util.UUID

/**
 * Narrow transactional projection for already-verified, individual editorial
 * evidence. Not connected to automatic refresh, UI or any production producer.
 *
 * Reuses the canonical repository's sole chapter writer and mutation gate.
 * Edition ownership is an additive local SQLDelight link, never a v1 sync key.
 * A real Host must supply independently checked and revocable authorization.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EditorialEditionUnitCommitRepositoryImpl(
    private val database: Database,
    private val chapters: CanonicalChapterRepository,
    private val mutationGate: ChapterMutationGate,
) : EditorialUnitCommitRepository {

    override suspend fun commit(
        batch: InventoryEvidenceBatch,
        authorization: HostEditorialEditionAuthorization,
    ): EditorialUnitCommitResult = try {
        mutationGate.withLock {
            database.transactionWithResult {
                currentCoroutineContext().ensureActive()
                val scope = batch.scope
                // Consent is local-only and separate from producer-supplied identity
                // and the Host's independent editorial/publisher verification.
                if (!hasPersistedEditionConsent(scope)) throw EditorialCommitRefused()
                val linked = database.tsuzuki_editorial_chapter_bindingsQueries
                    .getTsuzukiEditorialBindingsByTitle(scope.canonicalTitleId) {
                            id,
                            titleId,
                            producerId,
                            workId,
                            editionKey,
                            language,
                            recordId,
                            upstreamId,
                            identityKey,
                            volumeKey,
                            observedAt,
                        ->
                        StoredEditorialBinding(
                            chapterId = id,
                            canonicalTitleId = titleId,
                            producerId = producerId,
                            externalWorkId = workId,
                            editionKey = editionKey,
                            language = language,
                            sourceRecordId = recordId,
                            upstreamSourceId = upstreamId,
                            identityKey = identityKey,
                            volumeKey = volumeKey,
                            observedAt = observedAt,
                        )
                    }
                    .awaitAsList()
                val existing = chapters.getByCanonicalTitleId(scope.canonicalTitleId)
                val owned = linked.filter {
                    it.producerId == scope.producerId &&
                        it.externalWorkId == scope.externalWorkId &&
                        it.editionKey == scope.editionKey &&
                        it.language == scope.language
                }
                val ownedById = owned.associateBy { it.sourceRecordId }
                val requestedIds = batch.claims.mapTo(hashSetOf()) { it.sourceRecordId }
                val replayIds = owned.filter { it.sourceRecordId in requestedIds }
                    .mapTo(hashSetOf()) { it.chapterId }
                val preflight = PreviewEditorialEditionInventory(authorization).execute(
                    batch = batch,
                    existingCanonicalChapters = existing.filterNot { it.id in replayIds },
                )
                val ready = preflight as? EditorialEditionPreflight.Ready ?: throw EditorialCommitRefused()

                // The ledger can never re-home an already owned ID, revision or
                // language. The old canonical row itself must still agree too.
                val existingById = existing.associateBy { it.id }
                val chapterIds = mutableListOf<String>()
                val newlyCreated = mutableListOf<CanonicalChapter>()
                val insertions = mutableListOf<Pair<CanonicalChapter, EditorialEditionPreviewUnit>>()
                ready.units.forEach { unit ->
                    val previous = ownedById[unit.sourceRecordId]
                    if (previous != null) {
                        val canonical = existingById[previous.chapterId] ?: throw EditorialCommitRefused()
                        if (
                            previous.canonicalTitleId != scope.canonicalTitleId ||
                            previous.identityKey != unit.identity.sortKey ||
                            previous.volumeKey != (unit.volume ?: 0).toLong() ||
                            previous.upstreamSourceId != unit.upstreamSourceId ||
                            canonical.identity != unit.identity ||
                            canonical.volume != unit.volume ||
                            canonical.type != CanonicalChapterType.REGULAR ||
                            canonical.confirmation != CanonicalChapterConfirmation.CONFIRMED ||
                            canonical.displayNumber != unit.label
                        ) {
                            throw EditorialCommitRefused()
                        }
                        chapterIds += canonical.id
                    } else {
                        val chapter = CanonicalChapter(
                            id = UUID.randomUUID().toString(),
                            canonicalTitleId = scope.canonicalTitleId,
                            displayNumber = unit.label,
                            volume = unit.volume,
                            type = CanonicalChapterType.REGULAR,
                            baseNumber = unit.identity.baseNumber,
                            part = unit.identity.part,
                            alphaSuffix = unit.identity.alphaSuffix,
                            confidence = 1.0,
                            createdAt = batch.observedAt,
                            updatedAt = batch.observedAt,
                            confirmation = CanonicalChapterConfirmation.CONFIRMED,
                            numberingKind = unit.identity.numberingKind,
                            decimalFraction = unit.identity.decimalFraction,
                        )
                        chapterIds += chapter.id
                        newlyCreated += chapter
                        insertions += chapter to unit
                    }
                }
                // Canonical chapter and scoped ownership must commit or roll back together.
                chapters.upsertBatch(newlyCreated, emptyList())
                insertions.forEach { (chapter, unit) ->
                    database.tsuzuki_editorial_chapter_bindingsQueries.insertTsuzukiEditorialChapterBinding(
                        canonicalChapterId = chapter.id,
                        canonicalTitleId = scope.canonicalTitleId,
                        producerId = scope.producerId,
                        externalWorkId = scope.externalWorkId,
                        editionKey = requireNotNull(scope.editionKey),
                        scopeLanguage = requireNotNull(scope.language),
                        sourceRecordId = unit.sourceRecordId,
                        upstreamSourceId = unit.upstreamSourceId,
                        identityKey = unit.identity.sortKey,
                        volumeKey = (unit.volume ?: 0).toLong(),
                        observedAt = batch.observedAt,
                    )
                }
                currentCoroutineContext().ensureActive()
                // This check is inside the transaction, AFTER the INSERTs. A
                // withdrawn consent throws, rolling back both tables atomically.
                if (!hasPersistedEditionConsent(scope)) throw EditorialCommitRefused()
                if (authorization.approvedScope(scope.producerId, scope.canonicalTitleId) != scope) {
                    throw EditorialCommitRefused()
                }
                EditorialUnitCommitResult.Applied(chapterIds, insertions.size)
            }
        }
    } catch (_: EditorialCommitRefused) {
        EditorialUnitCommitResult.Refused
    }

    /**
     * SQLite calls run inside the same mutation-gated transaction as the
     * chapters and the edition bindings. A UI toggle alone is insufficient.
     * Missing, malformed, or absent explicit inventory opt-in is always denied.
     */
    private suspend fun hasPersistedEditionConsent(scope: InventoryScope): Boolean {
        val edition = scope.editionKey?.takeIf(String::isNotBlank) ?: return false
        val language = scope.language?.takeIf(String::isNotBlank) ?: return false
        val setting = database.tsuzuki_integration_settingsQueries
            .getTsuzukiIntegrationSetting(scope.producerId)
            .awaitAsOneOrNull() ?: return false
        if (!setting.enabled) return false
        val enabled = runCatching {
            val settings = Json.parseToJsonElement(setting.config_json) as? JsonObject
            settings?.get(IntegrationCapability.CHAPTER_INVENTORY.configKey)
                ?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)
        if (!enabled) return false
        return database.tsuzuki_editorial_edition_consentsQueries
            .countActiveTsuzukiEditorialEditionConsent(
                canonicalTitleId = scope.canonicalTitleId,
                producerId = scope.producerId,
                externalWorkId = scope.externalWorkId,
                editionKey = edition,
                scopeLanguage = language,
            ).awaitAsOne() == 1L
    }

    private data class StoredEditorialBinding(
        val chapterId: String,
        val canonicalTitleId: String,
        val producerId: String,
        val externalWorkId: String,
        val editionKey: String,
        val language: String,
        val sourceRecordId: String,
        val upstreamSourceId: String,
        val identityKey: String,
        val volumeKey: Long,
        val observedAt: Long,
    )

    private class EditorialCommitRefused : IllegalStateException("Edition or Host authorization changed")
}

package tachiyomi.data.tsuzuki.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryCandidateProvenance
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryCandidateSourceKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaim
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryStoredBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryStoredClaim

/**
 * Isolated append/update-only metadata ledger. Never touches canonical,
 * progress, download, Reader, backup or sync v1 tables.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IndependentInventoryRepositoryImpl(
    private val database: Database,
) : InventoryEvidenceRepository {

    override suspend fun byTitle(canonicalTitleId: String): List<InventoryStoredClaim> =
        database.tsuzuki_independent_inventory_claimsQueries
            .getTsuzukiIndependentInventoryClaimsByTitle(canonicalTitleId) {
                    titleId,
                    producerId,
                    externalWorkId,
                    editionKey,
                    scopeLanguage,
                    sourceRecordId,
                    rawLabel,
                    rawVolume,
                    language,
                    claimKind,
                    derivation,
                    upstreamSourceId,
                    workVerified,
                    editionVerified,
                    observedAt,
                    provenanceJson,
                ->
                InventoryStoredClaim(
                    scope = InventoryScope(
                        canonicalTitleId = titleId,
                        producerId = producerId,
                        externalWorkId = externalWorkId,
                        editionKey = editionKey.ifBlank { null },
                        language = scopeLanguage.ifBlank { null },
                        workIdentityVerified = workVerified != 0L,
                        editionIdentityVerified = editionVerified != 0L,
                    ),
                    claim = InventoryClaim(
                        sourceRecordId = sourceRecordId,
                        label = rawLabel,
                        volumeLabel = rawVolume,
                        language = language,
                        kind = InventoryClaimKind.valueOf(claimKind),
                        derivation = InventoryDerivation.valueOf(derivation),
                        upstreamSourceId = upstreamSourceId,
                        observedAt = observedAt,
                        provenance = CandidateProvenanceJsonCodec.decode(provenanceJson),
                    ),
                )
            }
            .awaitAsList()

    override suspend fun batchesByTitle(canonicalTitleId: String): List<InventoryStoredBatch> =
        database.tsuzuki_independent_inventory_claimsQueries
            .getTsuzukiIndependentInventoryPagesByTitle(canonicalTitleId) {
                    titleId,
                    producerId,
                    externalWorkId,
                    editionKey,
                    pageKey,
                    language,
                    workVerified,
                    editionVerified,
                    observedAt,
                    outcome,
                    itemCount,
                    declaredTotal,
                    nextCursor,
                ->
                InventoryStoredBatch(
                    scope = InventoryScope(
                        canonicalTitleId = titleId,
                        producerId = producerId,
                        externalWorkId = externalWorkId,
                        editionKey = editionKey.ifBlank { null },
                        language = language.ifBlank { null },
                        workIdentityVerified = workVerified != 0L,
                        editionIdentityVerified = editionVerified != 0L,
                    ),
                    pageKey = pageKey,
                    observedAt = observedAt,
                    outcome = InventoryFetchOutcome.valueOf(outcome),
                    itemCount = itemCount.toInt(),
                    declaredTotal = declaredTotal?.toInt(),
                    nextCursor = nextCursor,
                )
            }
            .awaitAsList()

    override suspend fun saveBatch(batch: InventoryEvidenceBatch) {
        val scope = batch.scope
        database.transactionWithResult {
            batch.claims.forEach { claim ->
                database.tsuzuki_independent_inventory_claimsQueries.upsertTsuzukiIndependentInventoryClaim(
                    canonicalTitleId = scope.canonicalTitleId,
                    producerId = scope.producerId,
                    externalWorkId = scope.externalWorkId,
                    editionKey = scope.editionKey.orEmpty(),
                    scopeLanguage = scope.language.orEmpty(),
                    sourceRecordId = claim.sourceRecordId,
                    rawLabel = claim.label,
                    rawVolume = claim.volumeLabel,
                    language = claim.language,
                    claimKind = claim.kind.name,
                    derivation = claim.derivation.name,
                    upstreamSourceId = claim.upstreamSourceId,
                    workVerified = if (scope.workIdentityVerified) 1L else 0L,
                    editionVerified = if (scope.editionIdentityVerified) 1L else 0L,
                    observedAt = claim.observedAt,
                    provenanceJson = CandidateProvenanceJsonCodec.encode(claim.provenance),
                )
            }
            database.tsuzuki_independent_inventory_claimsQueries.upsertTsuzukiIndependentInventoryPage(
                canonicalTitleId = scope.canonicalTitleId,
                producerId = scope.producerId,
                externalWorkId = scope.externalWorkId,
                editionKey = scope.editionKey.orEmpty(),
                pageKey = batch.pageKey,
                language = scope.language.orEmpty(),
                workVerified = if (scope.workIdentityVerified) 1L else 0L,
                editionVerified = if (scope.editionIdentityVerified) 1L else 0L,
                observedAt = batch.observedAt,
                outcome = batch.outcome.name,
                itemCount = batch.claims.size.toLong(),
                declaredTotal = batch.declaredTotal?.toLong(),
                nextCursor = batch.nextCursor,
            )
        }
    }
}

/** Stores bounded provenance pointers only; malformed or legacy values are not trusted. */
internal object CandidateProvenanceJsonCodec {
    fun encode(provenance: InventoryCandidateProvenance?): String? = provenance?.let {
        buildJsonObject {
            put("sourceKind", JsonPrimitive(it.sourceKind.name))
            put("originReference", JsonPrimitive(it.originReference))
            put("exactRevision", JsonPrimitive(it.exactRevision))
            put("attributionReference", it.attributionReference?.let { value -> JsonPrimitive(value) } ?: JsonNull)
            put("licenseReference", it.licenseReference?.let { value -> JsonPrimitive(value) } ?: JsonNull)
            put("snapshotDigest", JsonPrimitive(it.snapshotDigest))
            put("sourceUnitId", JsonPrimitive(it.sourceUnitId))
            put("structuralCategory", JsonPrimitive(it.structuralCategory))
        }.toString()
    }

    fun decode(encoded: String?): InventoryCandidateProvenance? = encoded?.let { raw ->
        if (raw.length > MAX_ENCODED_LENGTH) return@let null
        runCatching {
            val value = Json.parseToJsonElement(raw) as? JsonObject
                ?: error("Candidate provenance is not an object")
            InventoryCandidateProvenance(
                sourceKind = InventoryCandidateSourceKind.valueOf(value.requiredString("sourceKind")),
                originReference = value.requiredString("originReference"),
                exactRevision = value.requiredString("exactRevision"),
                attributionReference = value.optionalString("attributionReference"),
                licenseReference = value.optionalString("licenseReference"),
                snapshotDigest = value.requiredString("snapshotDigest"),
                sourceUnitId = value.requiredString("sourceUnitId"),
                structuralCategory = value.requiredString("structuralCategory"),
            )
        }.getOrNull()
    }

    private fun JsonObject.requiredString(key: String): String =
        optionalString(key) ?: error("Candidate provenance field $key is missing")

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.let { value ->
            if (value == JsonNull) {
                null
            } else {
                value.jsonPrimitive.let { primitive ->
                    require(primitive.isString) { "Candidate provenance field $key is not a string" }
                    primitive.contentOrNull
                }
            }
        }

    // Covers the bounded model even when control characters expand to six JSON escape characters each.
    private const val MAX_ENCODED_LENGTH = 65536
}

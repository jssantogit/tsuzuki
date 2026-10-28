package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationRequest
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationResult
import tachiyomi.domain.tsuzuki.model.VerifiedInventoryWorkAssociationRequest
import tachiyomi.domain.tsuzuki.model.VerifiedInventoryWorkAssociationResult
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalTitleRepositoryImpl(
    private val database: Database,
) : CanonicalTitleRepository {

    override suspend fun getById(id: String): CanonicalTitle? {
        return database.tsuzuki_titlesQueries
            .getTsuzukiTitleById(id, ::mapTitle)
            .awaitAsOneOrNull()
    }

    override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> {
        return database.tsuzuki_titlesQueries
            .getTsuzukiTitleById(id, ::mapTitle)
            .subscribeToOneOrNull()
    }

    override fun getAllAsFlow(): Flow<List<CanonicalTitle>> {
        return database.tsuzuki_titlesQueries
            .getAllTsuzukiTitles(::mapTitle)
            .subscribeToList()
    }

    override suspend fun getExternalIdentities(canonicalTitleId: String): List<ExternalIdentity> {
        return database.tsuzuki_external_identitiesQueries
            .getTsuzukiExternalIdentitiesByTitle(canonicalTitleId) {
                    titleId,
                    provider,
                    externalId,
                    verified,
                    createdAt,
                ->
                ExternalIdentity(
                    canonicalTitleId = titleId,
                    provider = provider,
                    externalId = externalId,
                    verified = verified,
                    createdAt = createdAt,
                )
            }
            .awaitAsList()
    }

    override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
        val titleId = database.tsuzuki_external_identitiesQueries
            .getTsuzukiTitleIdByExternalIdentity(provider, externalId)
            .awaitAsOneOrNull()
            ?: return null
        return getById(titleId)
    }

    override suspend fun getOrCreateByExternalIdentity(
        title: CanonicalTitle,
        identity: ExternalIdentity,
    ): CanonicalTitle {
        require(identity.canonicalTitleId == title.id) {
            "External identity must reference the candidate canonical title"
        }

        return try {
            database.transactionWithResult {
                val existingTitleId = database.tsuzuki_external_identitiesQueries
                    .getTsuzukiTitleIdByExternalIdentity(identity.provider, identity.externalId)
                    .awaitAsOneOrNull()

                if (existingTitleId != null) {
                    database.tsuzuki_titlesQueries
                        .getTsuzukiTitleById(existingTitleId, ::mapTitle)
                        .awaitAsOneOrNull()
                        ?: error("External identity points to a missing canonical title")
                } else {
                    database.tsuzuki_titlesQueries.insertTsuzukiTitle(
                        id = title.id,
                        displayTitle = title.displayTitle,
                        identityState = title.identityState.name,
                        createdAt = title.createdAt,
                        updatedAt = title.updatedAt,
                    )
                    database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
                        canonicalTitleId = identity.canonicalTitleId,
                        provider = identity.provider,
                        externalId = identity.externalId,
                        verified = identity.verified,
                        createdAt = identity.createdAt,
                    )
                    title
                }
            }
        } catch (e: Exception) {
            getByExternalIdentity(identity.provider, identity.externalId)?.let { return it }
            throw e
        }
    }

    override suspend fun insert(title: CanonicalTitle) {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = title.id,
            displayTitle = title.displayTitle,
            identityState = title.identityState.name,
            createdAt = title.createdAt,
            updatedAt = title.updatedAt,
        )
    }

    override suspend fun upsert(title: CanonicalTitle) {
        database.tsuzuki_titlesQueries.upsertTsuzukiTitle(
            id = title.id,
            displayTitle = title.displayTitle,
            identityState = title.identityState.name,
            createdAt = title.createdAt,
            updatedAt = title.updatedAt,
        )
    }

    override suspend fun addExternalIdentity(identity: ExternalIdentity) {
        database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
            canonicalTitleId = identity.canonicalTitleId,
            provider = identity.provider,
            externalId = identity.externalId,
            verified = identity.verified,
            createdAt = identity.createdAt,
        )
    }

    override suspend fun associateMangaDexWorkIfCurrent(
        request: MangaDexWorkAssociationRequest,
        isAuthorized: () -> Boolean,
    ): MangaDexWorkAssociationResult {
        val result = associateVerifiedInventoryWorkIfCurrent(
            VerifiedInventoryWorkAssociationRequest(
                canonicalTitleId = request.canonicalTitleId,
                producerId = MANGADEX_PROVIDER,
                externalWorkId = request.mangaDexWorkId,
                expectedIntegrationSettings = request.expectedIntegrationSettings,
                expectedVerifiedCrosswalks = request.expectedVerifiedCrosswalks,
            ),
            isAuthorized,
        )
        return when (result) {
            VerifiedInventoryWorkAssociationResult.ASSOCIATED -> MangaDexWorkAssociationResult.ASSOCIATED
            VerifiedInventoryWorkAssociationResult.ALREADY_ASSOCIATED ->
                MangaDexWorkAssociationResult.ALREADY_ASSOCIATED
            VerifiedInventoryWorkAssociationResult.REFUSED -> MangaDexWorkAssociationResult.REFUSED
            VerifiedInventoryWorkAssociationResult.UNSUPPORTED -> MangaDexWorkAssociationResult.UNSUPPORTED
        }
    }

    override suspend fun associateVerifiedInventoryWorkIfCurrent(
        request: VerifiedInventoryWorkAssociationRequest,
        isAuthorized: () -> Boolean,
    ): VerifiedInventoryWorkAssociationResult = try {
        database.transactionWithResult {
            currentCoroutineContext().ensureActive()
            if (!request.isValid() || !isAuthorized() || !hasExpectedPermission(request)) {
                return@transactionWithResult VerifiedInventoryWorkAssociationResult.REFUSED
            }

            val title = database.tsuzuki_titlesQueries
                .getTsuzukiTitleById(request.canonicalTitleId, ::mapTitle)
                .awaitAsOneOrNull()
                ?: return@transactionWithResult VerifiedInventoryWorkAssociationResult.REFUSED
            val currentIdentities = identitiesFor(title.id)
            if (!sameVerifiedCrosswalks(currentIdentities, request.expectedVerifiedCrosswalks)) {
                return@transactionWithResult VerifiedInventoryWorkAssociationResult.REFUSED
            }

            val owner = ownerOf(request.producerId, request.externalWorkId)
            val currentProducerLinks = currentIdentities.filter { it.provider == request.producerId }
            if (owner != null) {
                val alreadyAssociated = owner == request.canonicalTitleId &&
                    currentProducerLinks.any {
                        it.externalId == request.externalWorkId && it.verified
                    }
                if (!alreadyAssociated) return@transactionWithResult VerifiedInventoryWorkAssociationResult.REFUSED
                ensureCurrentBeforeCommit(request, request.expectedVerifiedCrosswalks, isAuthorized)
                return@transactionWithResult VerifiedInventoryWorkAssociationResult.ALREADY_ASSOCIATED
            }
            if (currentProducerLinks.isNotEmpty()) {
                return@transactionWithResult VerifiedInventoryWorkAssociationResult.REFUSED
            }

            ensureCurrentBeforeMutation(request, isAuthorized)
            val identity = ExternalIdentity(
                canonicalTitleId = request.canonicalTitleId,
                provider = request.producerId,
                externalId = request.externalWorkId,
                verified = true,
                createdAt = Clock.System.now().toEpochMilliseconds(),
            )
            database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
                canonicalTitleId = identity.canonicalTitleId,
                provider = identity.provider,
                externalId = identity.externalId,
                verified = identity.verified,
                createdAt = identity.createdAt,
            )

            currentCoroutineContext().ensureActive()
            val expectedAfterInsert = request.expectedVerifiedCrosswalks + identity
            ensureCurrentBeforeCommit(request, expectedAfterInsert, isAuthorized)
            VerifiedInventoryWorkAssociationResult.ASSOCIATED
        }
    } catch (_: AssociationRefusedException) {
        VerifiedInventoryWorkAssociationResult.REFUSED
    } catch (cancelled: CancellationException) {
        throw cancelled
    }

    private suspend fun ensureCurrentBeforeCommit(
        request: VerifiedInventoryWorkAssociationRequest,
        expectedCrosswalks: List<ExternalIdentity>,
        isAuthorized: () -> Boolean,
    ) {
        currentCoroutineContext().ensureActive()
        val currentIdentities = identitiesFor(request.canonicalTitleId)
        if (
            !isAuthorized() ||
            !hasExpectedPermission(request) ||
            !titleExists(request.canonicalTitleId) ||
            !sameVerifiedCrosswalks(currentIdentities, expectedCrosswalks) ||
            !sameProducerLinks(currentIdentities, expectedCrosswalks, request.producerId) ||
            ownerOf(request.producerId, request.externalWorkId) != request.canonicalTitleId
        ) {
            throw AssociationRefusedException()
        }
        currentCoroutineContext().ensureActive()
        if (!isAuthorized()) throw AssociationRefusedException()
    }

    private suspend fun hasExpectedPermission(request: VerifiedInventoryWorkAssociationRequest): Boolean {
        val expected = request.expectedIntegrationSettings
        if (!expected.hasInventoryPermission(request.producerId)) return false
        val actual = database.tsuzuki_integration_settingsQueries
            .getTsuzukiIntegrationSetting(request.producerId) { integrationId, enabled, configJson, updatedAt ->
                IntegrationSettings(
                    integrationId = tachiyomi.domain.tsuzuki.integration.IntegrationId(integrationId),
                    enabled = enabled,
                    configJson = configJson,
                    updatedAt = updatedAt,
                )
            }
            .awaitAsOneOrNull()
        return actual == expected
    }

    private suspend fun ensureCurrentBeforeMutation(
        request: VerifiedInventoryWorkAssociationRequest,
        isAuthorized: () -> Boolean,
    ) {
        currentCoroutineContext().ensureActive()
        val currentIdentities = identitiesFor(request.canonicalTitleId)
        if (
            !isAuthorized() ||
            !hasExpectedPermission(request) ||
            !titleExists(request.canonicalTitleId) ||
            !sameVerifiedCrosswalks(currentIdentities, request.expectedVerifiedCrosswalks) ||
            currentIdentities.any { it.provider == request.producerId } ||
            ownerOf(request.producerId, request.externalWorkId) != null
        ) {
            throw AssociationRefusedException()
        }
        currentCoroutineContext().ensureActive()
        if (!isAuthorized()) throw AssociationRefusedException()
    }

    private suspend fun titleExists(id: String): Boolean = database.tsuzuki_titlesQueries
        .getTsuzukiTitleById(id, ::mapTitle)
        .awaitAsOneOrNull() != null

    private suspend fun identitiesFor(titleId: String): List<ExternalIdentity> = database
        .tsuzuki_external_identitiesQueries
        .getTsuzukiExternalIdentitiesByTitle(titleId) { id, provider, externalId, verified, createdAt ->
            ExternalIdentity(id, provider, externalId, verified, createdAt)
        }
        .awaitAsList()

    private suspend fun ownerOf(producerId: String, workId: String): String? = database.tsuzuki_external_identitiesQueries
        .getTsuzukiTitleIdByExternalIdentity(producerId, workId)
        .awaitAsOneOrNull()

    private fun mapTitle(
        id: String,
        displayTitle: String,
        identityState: String,
        createdAt: Long,
        updatedAt: Long,
    ) = CanonicalTitle(
        id = id,
        displayTitle = displayTitle,
        identityState = CanonicalIdentityState.valueOf(identityState),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun sameVerifiedCrosswalks(
        actual: List<ExternalIdentity>,
        expected: List<ExternalIdentity>,
    ): Boolean = actual.filter { it.verified }.sortedWith(EXTERNAL_IDENTITY_ORDER) ==
        expected.sortedWith(EXTERNAL_IDENTITY_ORDER)

    private fun sameProducerLinks(
        actual: List<ExternalIdentity>,
        expected: List<ExternalIdentity>,
        producerId: String,
    ): Boolean = actual.filter { it.provider == producerId }
        .sortedWith(EXTERNAL_IDENTITY_ORDER) ==
        expected.filter { it.provider == producerId }.sortedWith(EXTERNAL_IDENTITY_ORDER)

    private fun VerifiedInventoryWorkAssociationRequest.isValid(): Boolean {
        val identityIsValid = when (producerId) {
            MANGADEX_PROVIDER -> MANGADEX_WORK_UUID_REGEX.matches(externalWorkId) &&
                expectedVerifiedCrosswalks.any { it.provider != MANGADEX_PROVIDER }
            MANGAUPDATES_PROVIDER -> MANGAUPDATES_WORK_ID_REGEX.matches(externalWorkId) &&
                externalWorkId.toLongOrNull()?.let { it > 0L } == true &&
                expectedVerifiedCrosswalks.any { it.provider == MANGADEX_PROVIDER }
            else -> false
        }
        return canonicalTitleId.isNotBlank() &&
            identityIsValid &&
            expectedIntegrationSettings.hasInventoryPermission(producerId) &&
            expectedVerifiedCrosswalks.isNotEmpty() &&
            expectedVerifiedCrosswalks.all {
                it.canonicalTitleId == canonicalTitleId &&
                    it.provider.isNotBlank() &&
                    it.externalId.isNotBlank() &&
                    it.verified
            } &&
            expectedVerifiedCrosswalks.distinct().size == expectedVerifiedCrosswalks.size
    }

    private fun IntegrationSettings.hasInventoryPermission(producerId: String): Boolean {
        if (integrationId.value != producerId || !enabled) return false
        val config = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull()
            ?: return false
        return (config[CHAPTER_INVENTORY_CAPABILITY] as? JsonPrimitive)?.booleanOrNull == true
    }

    private class AssociationRefusedException : Exception()

    private companion object {
        const val MANGADEX_PROVIDER = "mangadex"
        const val MANGAUPDATES_PROVIDER = "mangaupdates"
        val MANGAUPDATES_WORK_ID_REGEX = Regex("""^[0-9]{1,18}$""")
        const val CHAPTER_INVENTORY_CAPABILITY = "chapter_inventory"
        val MANGADEX_WORK_UUID_REGEX = Regex(
            """^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$""",
        )
        val EXTERNAL_IDENTITY_ORDER = compareBy<ExternalIdentity>(
            { it.canonicalTitleId },
            { it.provider },
            { it.externalId },
            { it.createdAt },
            { it.verified },
        )
    }
}

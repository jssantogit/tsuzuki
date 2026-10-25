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
    ): MangaDexWorkAssociationResult = try {
        database.transactionWithResult {
            currentCoroutineContext().ensureActive()
            if (!request.isValid() || !isAuthorized() || !hasExpectedPermission(request)) {
                return@transactionWithResult MangaDexWorkAssociationResult.REFUSED
            }

            val title = database.tsuzuki_titlesQueries
                .getTsuzukiTitleById(request.canonicalTitleId, ::mapTitle)
                .awaitAsOneOrNull()
                ?: return@transactionWithResult MangaDexWorkAssociationResult.REFUSED
            val currentIdentities = identitiesFor(title.id)
            if (!sameVerifiedCrosswalks(currentIdentities, request.expectedVerifiedCrosswalks)) {
                return@transactionWithResult MangaDexWorkAssociationResult.REFUSED
            }

            val owner = ownerOf(request.mangaDexWorkId)
            val currentMangaDexLinks = currentIdentities.filter { it.provider == MANGADEX_PROVIDER }
            if (owner != null) {
                val alreadyAssociated = owner == request.canonicalTitleId &&
                    currentMangaDexLinks.any {
                        it.externalId == request.mangaDexWorkId && it.verified
                    }
                if (!alreadyAssociated) return@transactionWithResult MangaDexWorkAssociationResult.REFUSED
                ensureCurrentBeforeCommit(request, request.expectedVerifiedCrosswalks, isAuthorized)
                return@transactionWithResult MangaDexWorkAssociationResult.ALREADY_ASSOCIATED
            }
            if (currentMangaDexLinks.isNotEmpty()) {
                return@transactionWithResult MangaDexWorkAssociationResult.REFUSED
            }

            ensureCurrentBeforeMutation(request, isAuthorized)
            val identity = ExternalIdentity(
                canonicalTitleId = request.canonicalTitleId,
                provider = MANGADEX_PROVIDER,
                externalId = request.mangaDexWorkId,
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
            MangaDexWorkAssociationResult.ASSOCIATED
        }
    } catch (_: AssociationRefusedException) {
        MangaDexWorkAssociationResult.REFUSED
    } catch (cancelled: CancellationException) {
        throw cancelled
    }

    private suspend fun ensureCurrentBeforeCommit(
        request: MangaDexWorkAssociationRequest,
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
            !sameMangaDexLinks(currentIdentities, expectedCrosswalks) ||
            ownerOf(request.mangaDexWorkId) != request.canonicalTitleId
        ) {
            throw AssociationRefusedException()
        }
        currentCoroutineContext().ensureActive()
        if (!isAuthorized()) throw AssociationRefusedException()
    }

    private suspend fun hasExpectedPermission(request: MangaDexWorkAssociationRequest): Boolean {
        val expected = request.expectedIntegrationSettings
        if (!expected.hasMangaDexInventoryPermission()) return false
        val actual = database.tsuzuki_integration_settingsQueries
            .getTsuzukiIntegrationSetting(MANGADEX_PROVIDER) { integrationId, enabled, configJson, updatedAt ->
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
        request: MangaDexWorkAssociationRequest,
        isAuthorized: () -> Boolean,
    ) {
        currentCoroutineContext().ensureActive()
        val currentIdentities = identitiesFor(request.canonicalTitleId)
        if (
            !isAuthorized() ||
            !hasExpectedPermission(request) ||
            !titleExists(request.canonicalTitleId) ||
            !sameVerifiedCrosswalks(currentIdentities, request.expectedVerifiedCrosswalks) ||
            currentIdentities.any { it.provider == MANGADEX_PROVIDER } ||
            ownerOf(request.mangaDexWorkId) != null
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

    private suspend fun ownerOf(workId: String): String? = database.tsuzuki_external_identitiesQueries
        .getTsuzukiTitleIdByExternalIdentity(MANGADEX_PROVIDER, workId)
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

    private fun sameMangaDexLinks(
        actual: List<ExternalIdentity>,
        expected: List<ExternalIdentity>,
    ): Boolean = actual.filter { it.provider == MANGADEX_PROVIDER }
        .sortedWith(EXTERNAL_IDENTITY_ORDER) ==
        expected.filter { it.provider == MANGADEX_PROVIDER }.sortedWith(EXTERNAL_IDENTITY_ORDER)

    private fun MangaDexWorkAssociationRequest.isValid(): Boolean =
        canonicalTitleId.isNotBlank() &&
            MANGADEX_WORK_UUID_REGEX.matches(mangaDexWorkId) &&
            expectedIntegrationSettings.hasMangaDexInventoryPermission() &&
            expectedVerifiedCrosswalks.isNotEmpty() &&
            expectedVerifiedCrosswalks.all {
                it.canonicalTitleId == canonicalTitleId &&
                    it.provider.isNotBlank() &&
                    it.externalId.isNotBlank() &&
                    it.verified
            } &&
            expectedVerifiedCrosswalks.distinct().size == expectedVerifiedCrosswalks.size &&
            expectedVerifiedCrosswalks.any { it.provider != MANGADEX_PROVIDER }

    private fun IntegrationSettings.hasMangaDexInventoryPermission(): Boolean {
        if (integrationId.value != MANGADEX_PROVIDER || !enabled) return false
        val config = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull()
            ?: return false
        return (config[CHAPTER_INVENTORY_CAPABILITY] as? JsonPrimitive)?.booleanOrNull == true
    }

    private class AssociationRefusedException : Exception()

    private companion object {
        const val MANGADEX_PROVIDER = "mangadex"
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

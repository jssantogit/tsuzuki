package eu.kanade.tachiyomi.data.tsuzuki.inventory

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.tsuzuki.integration.IntegrationSettingsConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationRequest
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationResult
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

/**
 * An explicit, identity-verified MangaDex work association.
 * No title-string matching: MangaDex itself must cross-reference an already
 * verified external work ID for this precise canonical title.
 *
 * A verified *work* is not a verified *edition*, and cannot promote its releases.
 */
data class MangaDexVerifiedCandidate(
    val mangaDexId: String,
    val displayTitle: String,
    val matchedProvider: String,
    val matchedExternalId: String,
)

@Inject
class MangaDexVerifiedWorkLinker(
    private val titles: CanonicalTitleRepository,
    private val integrationSettings: IntegrationSettingsRepository,
    private val transport: InventoryReleaseTransport,
    private val titleNames: TitleNameObservationRepository,
) {
    /**
     * Discover a work by its existing canonical title without asking the user
     * to copy a MangaDex UUID. Title search only supplies candidates: a unique,
     * independently verified external ID crosswalk is mandatory even to return
     * one candidate. No database writes occur during discovery.
     */
    suspend fun discoverUnique(
        canonicalTitleId: String,
        isEnabled: () -> Boolean,
    ): Result<MangaDexVerifiedCandidate?> = capture {
        currentCoroutineContext().ensureActive()
        val settingsSnapshot = requireInventoryPermission(isEnabled)
        require(canonicalTitleId.isNotBlank())
        val canonical = titles.getById(canonicalTitleId) ?: return@capture null
        val name = canonical.displayTitle.trim()
        if (name.length !in 2..160) return@capture null

        val known = titles.getExternalIdentities(canonicalTitleId)
        requireSettingsUnchanged(settingsSnapshot, isEnabled)
        if (known.any { it.provider == "mangadex" }) return@capture null
        val verified = known.filter { it.verified && it.canonicalTitleId == canonicalTitleId }
        if (verified.none { identity -> CROSSWALK_KEYS.any { it.second == identity.provider } }) {
            return@capture null
        }

        // Observed aliases are discovery-only, never evidence of identity.
        // The crosswalk check below remains mandatory for every result.
        val queryNames = (
            listOf(name) + titleNames.getByTitle(canonicalTitleId)
                .sortedByDescending { it.updatedAt }
                .map { it.value.trim() }
            )
            .filter { it.length in 2..160 }
            .distinctBy { it.lowercase() }
            .take(MAX_TITLE_SEARCHES)
        requireSettingsUnchanged(settingsSnapshot, isEnabled)
        val matching = queryNames.flatMap { query ->
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("api.mangadex.org")
                .addPathSegment("manga")
                .addQueryParameter("title", query)
                .addQueryParameter("limit", "10")
                .build().toString()
            val payload = transport.fetchAuthorized(url, null) {
                isSettingsSnapshotCurrent(settingsSnapshot, isEnabled)
            }
            currentCoroutineContext().ensureActive()
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            val root = Json.parseToJsonElement(payload.content) as? JsonObject
                ?: error("MangaDex search response is not an object")
            require(root.string("result") == "ok")
            val entries = root["data"] as? JsonArray ?: error("Missing MangaDex search data")
            require(entries.size <= 10) { "Unbounded MangaDex search results" }
            entries.mapNotNull { entry ->
                val manga = entry as? JsonObject ?: error("Invalid MangaDex search result")
                if (manga.string("type") != "manga") return@mapNotNull null
                val mangaId = manga.string("id")?.takeIf { it.matches(MANGADEX_WORK_UUID_REGEX) }
                    ?: return@mapNotNull null
                val attributes = manga["attributes"] as? JsonObject ?: return@mapNotNull null
                val links = attributes["links"] as? JsonObject ?: return@mapNotNull null
                // A candidate contradicted by a previously verified identity is
                // not eligible. A matching display title alone proves nothing.
                val contradicts = CROSSWALK_KEYS.any { (key, provider) ->
                    val claimed = links.string(key) ?: return@any false
                    verified.any { it.provider == provider && !sameCrosswalk(provider, claimed, it.externalId) }
                }
                if (contradicts) return@mapNotNull null
                val matched = CROSSWALK_KEYS.firstNotNullOfOrNull { (key, provider) ->
                    val externalId = links.string(key)
                    verified.firstOrNull {
                        it.provider == provider && sameCrosswalk(provider, externalId, it.externalId)
                    }
                } ?: return@mapNotNull null
                val titlesField = attributes["title"] as? JsonObject ?: return@mapNotNull null
                val label = titlesField.string("en") ?: titlesField.string("ja-ro")
                    ?: titlesField.values.firstNotNullOfOrNull {
                        (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    } ?: return@mapNotNull null
                MangaDexVerifiedCandidate(mangaId, label, matched.provider, matched.externalId)
            }
        }.distinctBy { it.mangaDexId }
        requireSettingsUnchanged(settingsSnapshot, isEnabled)
        require(matching.size <= 1) {
            "Ambiguous MangaDex works share this cross-reference; no automatic association"
        }
        matching.singleOrNull()
    }

    suspend fun preview(
        canonicalTitleId: String,
        mangaDexId: String,
        isEnabled: () -> Boolean,
    ): Result<MangaDexVerifiedCandidate> = previewWithCachePolicy(
        canonicalTitleId = canonicalTitleId,
        mangaDexId = mangaDexId,
        forceNetwork = false,
        isEnabled = isEnabled,
    )

    private suspend fun previewWithCachePolicy(
        canonicalTitleId: String,
        mangaDexId: String,
        forceNetwork: Boolean,
        isEnabled: () -> Boolean,
        expectedSettings: IntegrationSettings? = null,
    ): Result<MangaDexVerifiedCandidate> =
        capture {
            currentCoroutineContext().ensureActive()
            val settingsSnapshot = requireInventoryPermission(isEnabled)
            check(expectedSettings == null || settingsSnapshot == expectedSettings) {
                "Inventory integration settings changed before confirmation"
            }
            require(canonicalTitleId.isNotBlank())
            require(mangaDexId.matches(MANGADEX_WORK_UUID_REGEX)) { "Invalid MangaDex manga UUID" }
            val url = "https://api.mangadex.org/manga/$mangaDexId"
            val transportAuthorization: suspend () -> Boolean = {
                isSettingsSnapshotCurrent(settingsSnapshot, isEnabled)
            }
            val payload = if (forceNetwork) {
                transport.fetchFreshAuthorized(url, null, transportAuthorization)
            } else {
                transport.fetchAuthorized(url, null, transportAuthorization)
            }
            currentCoroutineContext().ensureActive()
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            val response = Json.parseToJsonElement(payload.content) as? JsonObject
                ?: error("MangaDex work response is not an object")
            require(response.string("result") == "ok")
            val data = response.objectField("data")
            require(data.string("type") == "manga" && data.string("id") == mangaDexId) {
                "MangaDex returned a different manga"
            }
            val attributes = data.objectField("attributes")
            val names = attributes.objectField("title")
            val label = names.string("en") ?: names.string("ja-ro")
                ?: names.values.firstNotNullOfOrNull {
                    (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                } ?: error("MangaDex work has no display title")
            val links = attributes.objectField("links")
            val identities = titles.getExternalIdentities(canonicalTitleId)
            currentCoroutineContext().ensureActive()
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            val verified = identities.filter { it.verified && it.canonicalTitleId == canonicalTitleId }
            val matching = CROSSWALK_KEYS.mapNotNull { (mangaDexKey, tsuzukiProducer) ->
                val claimedExternal = links.string(mangaDexKey) ?: return@mapNotNull null
                val existing = verified.filter { it.provider == tsuzukiProducer }
                require(
                    existing.isEmpty() || existing.all {
                        sameCrosswalk(tsuzukiProducer, claimedExternal, it.externalId)
                    },
                ) {
                    "MangaDex cross-references another $tsuzukiProducer work"
                }
                existing.firstOrNull { sameCrosswalk(tsuzukiProducer, claimedExternal, it.externalId) }
            }.distinctBy { it.provider to it.externalId }
            require(matching.isNotEmpty()) {
                "No independently verified MangaDex external-work cross-reference"
            }
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            val selected = matching.first()
            MangaDexVerifiedCandidate(
                mangaDexId = mangaDexId,
                displayTitle = label,
                matchedProvider = selected.provider,
                matchedExternalId = selected.externalId,
            )
        }

    /**
     * Preview is not a bearer token. Revalidate the exact remote work, verified
     * crosswalk and local ownership after the user explicitly confirms linking.
     */
    suspend fun confirm(
        canonicalTitleId: String,
        candidate: MangaDexVerifiedCandidate,
        isEnabled: () -> Boolean,
    ): Result<Unit> =
        capture {
            currentCoroutineContext().ensureActive()
            val settingsSnapshot = requireInventoryPermission(isEnabled)
            require(canonicalTitleId.isNotBlank())
            require(
                previewWithCachePolicy(
                    canonicalTitleId = canonicalTitleId,
                    mangaDexId = candidate.mangaDexId,
                    forceNetwork = true,
                    isEnabled = isEnabled,
                    expectedSettings = settingsSnapshot,
                ).getOrThrow() == candidate,
            ) { "MangaDex work crosswalk changed before confirmation" }
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            val identities = titles.getExternalIdentities(canonicalTitleId)
            currentCoroutineContext().ensureActive()
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            val linked = identities.filter { it.provider == "mangadex" }
            require(
                linked.isEmpty() || linked.all {
                    it.externalId == candidate.mangaDexId && it.verified
                },
            ) { "An incompatible MangaDex work is already linked to this canonical title" }

            val owner = titles.getByExternalIdentity("mangadex", candidate.mangaDexId)
            currentCoroutineContext().ensureActive()
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            require(owner == null || owner.id == canonicalTitleId) {
                "MangaDex work already belongs to another canonical title"
            }
            val verifiedCrosswalks = titles.getExternalIdentities(canonicalTitleId)
            currentCoroutineContext().ensureActive()
            requireSettingsUnchanged(settingsSnapshot, isEnabled)
            require(
                verifiedCrosswalks.any {
                    it.canonicalTitleId == canonicalTitleId &&
                        it.provider == candidate.matchedProvider &&
                        it.externalId == candidate.matchedExternalId &&
                        it.verified
                },
            ) { "MangaDex crosswalk changed before confirmation" }
            val association = titles.associateMangaDexWorkIfCurrent(
                request = MangaDexWorkAssociationRequest(
                    canonicalTitleId = canonicalTitleId,
                    mangaDexWorkId = candidate.mangaDexId,
                    expectedIntegrationSettings = settingsSnapshot,
                    expectedVerifiedCrosswalks = verifiedCrosswalks.filter { it.verified },
                ),
                isAuthorized = isEnabled,
            )
            check(
                association == MangaDexWorkAssociationResult.ASSOCIATED ||
                    association == MangaDexWorkAssociationResult.ALREADY_ASSOCIATED,
            ) { "MangaDex association was refused because identity or permission changed" }
        }

    private suspend fun requireInventoryPermission(isEnabled: () -> Boolean): IntegrationSettings {
        currentCoroutineContext().ensureActive()
        check(isEnabled()) { "Inventory integration is disabled" }
        val settings = integrationSettings.get(MANGADEX_INTEGRATION_ID)
        currentCoroutineContext().ensureActive()
        check(isEnabled()) { "Inventory integration is disabled" }
        val persistedSettings = settings ?: error("MangaDex integration settings are missing")
        check(persistedSettings.integrationId == MANGADEX_INTEGRATION_ID) {
            "MangaDex integration settings do not match the requested integration"
        }
        check(persistedSettings.enabled) { "MangaDex integration is not enabled" }
        check(
            IntegrationSettingsConfig.decode(persistedSettings.configJson)
                .capabilityEnabled(IntegrationCapability.CHAPTER_INVENTORY),
        ) { "MangaDex chapter inventory is not enabled" }
        return persistedSettings
    }

    private suspend fun requireSettingsUnchanged(
        expected: IntegrationSettings,
        isEnabled: () -> Boolean,
    ) {
        currentCoroutineContext().ensureActive()
        check(isSettingsSnapshotCurrent(expected, isEnabled)) {
            "MangaDex integration settings changed during the operation"
        }
    }

    private suspend fun isSettingsSnapshotCurrent(
        expected: IntegrationSettings,
        isEnabled: () -> Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!isEnabled()) return false
        val actual = integrationSettings.get(MANGADEX_INTEGRATION_ID)
        currentCoroutineContext().ensureActive()
        if (actual == null) return false
        return isEnabled() &&
            actual == expected &&
            actual.enabled &&
            IntegrationSettingsConfig.decode(actual.configJson)
                .capabilityEnabled(IntegrationCapability.CHAPTER_INVENTORY)
    }

    private fun sameCrosswalk(producer: String, claimed: String?, verifiedId: String): Boolean {
        if (claimed.isNullOrBlank()) return false
        if (producer != "mangaupdates") return claimed == verifiedId
        val normalizedClaim = normalizeMangaUpdatesWorkId(claimed) ?: return false
        return normalizedClaim == normalizeMangaUpdatesWorkId(verifiedId)
    }

    private suspend inline fun <T> capture(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    private fun JsonObject.objectField(field: String): JsonObject =
        this[field] as? JsonObject ?: error("Missing MangaDex $field")

    private fun JsonObject.string(field: String): String? =
        (this[field] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private companion object {
        val MANGADEX_INTEGRATION_ID = IntegrationId("mangadex")
        const val MAX_TITLE_SEARCHES = 3
        val CROSSWALK_KEYS = listOf(
            "mu" to "mangaupdates",
            "mal" to "myanimelist",
            "kt" to "kitsu",
        )
    }
}

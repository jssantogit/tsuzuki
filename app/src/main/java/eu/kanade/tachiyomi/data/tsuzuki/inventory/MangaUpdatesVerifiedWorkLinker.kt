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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.model.VerifiedInventoryWorkAssociationRequest
import tachiyomi.domain.tsuzuki.model.VerifiedInventoryWorkAssociationResult
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

/** A work-only match. Neither title search nor a release provides edition authority. */
data class MangaUpdatesVerifiedCandidate(
    val mangaUpdatesId: String,
    val displayTitle: String,
    val mangaDexId: String,
)

/**
 * MangaUpdates search is useful for discovery, but it cannot assert verified
 * identity from a similar title. A preexisting verified MangaDex work and its
 * external MU link are necessary for automatic association.
 */
@Inject
class MangaUpdatesVerifiedWorkLinker(
    private val titles: CanonicalTitleRepository,
    private val settings: IntegrationSettingsRepository,
    private val transport: InventoryReleaseTransport,
) {
    private data class Permissions(val mu: IntegrationSettings, val md: IntegrationSettings)

    suspend fun discoverUnique(
        canonicalTitleId: String,
        isEnabled: () -> Boolean,
    ): Result<MangaUpdatesVerifiedCandidate?> = capture {
        val permission = permission(isEnabled)
        require(canonicalTitleId.isNotBlank())
        val canonical = titles.getById(canonicalTitleId) ?: return@capture null
        val name = canonical.displayTitle.trim()
        if (name.length !in 2..160) return@capture null
        val identities = titles.getExternalIdentities(canonicalTitleId)
        checkCurrent(permission, isEnabled)
        if (identities.any { it.provider == "mangaupdates" }) return@capture null
        val anchor = identities.singleOrNull {
            it.provider == "mangadex" && it.verified && it.canonicalTitleId == canonicalTitleId
        } ?: return@capture null

        val muId = readMangaDexMuLink(anchor.externalId, permission, isEnabled, fresh = false)
            ?: return@capture null
        val body = buildJsonObject {
            put("search", name)
            put("stype", "title")
            put("page", 1)
            put("perpage", 10)
        }.toString()
        val response = transport.fetchAuthorized(SERIES_SEARCH_URL, body) {
            isCurrent(permission, isEnabled)
        }
        checkCurrent(permission, isEnabled)
        val result = parseObject(response.content)
        val page = (result["page"] as? JsonPrimitive)?.intOrNull
        val perPage = (result["per_page"] as? JsonPrimitive)?.intOrNull
        require(page == 1 && perPage != null && perPage in 1..10) {
            "Unexpected MangaUpdates search cursor"
        }
        val rows = result["results"] as? JsonArray ?: error("Missing MangaUpdates search results")
        require(rows.size <= 10) { "Unbounded MangaUpdates search results" }
        val found = rows.mapNotNull { item ->
            val record = (item as? JsonObject)?.get("record") as? JsonObject ?: return@mapNotNull null
            val id = record.string("series_id")
            if (id != muId) return@mapNotNull null
            record.string("title")?.let { MangaUpdatesVerifiedCandidate(muId, it, anchor.externalId) }
        }.distinctBy { it.mangaUpdatesId }
        checkCurrent(permission, isEnabled)
        if (found.size == 1) return@capture found.single()
        // Title search may return homonyms while omitting the actual work
        // (alias, translation, pagination). Never associate any of those rows.
        // Instead, use the independently verified MangaDex → MU crosswalk
        // to request that precise series ID, even if its title is different.
        val detail = readSeries(muId, permission, isEnabled, fresh = false)
        val label = detail.string("title") ?: return@capture null
        MangaUpdatesVerifiedCandidate(muId, label, anchor.externalId)
    }

    suspend fun confirm(
        canonicalTitleId: String,
        candidate: MangaUpdatesVerifiedCandidate,
        isEnabled: () -> Boolean,
    ): Result<Unit> = capture {
        val permission = permission(isEnabled)
        require(canonicalTitleId.isNotBlank() && candidate.mangaUpdatesId.isPositiveMuId())
        require(candidate.mangaDexId.matches(MANGADEX_WORK_UUID_REGEX))
        val verified = titles.getExternalIdentities(canonicalTitleId).filter { it.verified }
        checkCurrent(permission, isEnabled)
        require(
            verified.any {
                it.provider == "mangadex" && it.externalId == candidate.mangaDexId
            },
        ) { "MangaDex work proof was revoked" }
        val linkedId = readMangaDexMuLink(candidate.mangaDexId, permission, isEnabled, fresh = true)
        require(linkedId == candidate.mangaUpdatesId) { "MangaUpdates crosswalk changed" }
        val detail = readSeries(candidate.mangaUpdatesId, permission, isEnabled, fresh = true)
        require(detail.string("title") == candidate.displayTitle) {
            "MangaUpdates series changed before confirmation"
        }
        checkCurrent(permission, isEnabled)
        val latest = titles.getExternalIdentities(canonicalTitleId).filter { it.verified }
        require(latest == verified) { "Verified external identities changed during confirmation" }
        val association = titles.associateVerifiedInventoryWorkIfCurrent(
            request = VerifiedInventoryWorkAssociationRequest(
                canonicalTitleId = canonicalTitleId,
                producerId = "mangaupdates",
                externalWorkId = candidate.mangaUpdatesId,
                expectedIntegrationSettings = permission.mu,
                expectedVerifiedCrosswalks = verified,
            ),
            isAuthorized = isEnabled,
        )
        check(
            association == VerifiedInventoryWorkAssociationResult.ASSOCIATED ||
                association == VerifiedInventoryWorkAssociationResult.ALREADY_ASSOCIATED,
        ) { "MangaUpdates association was refused by the atomic identity gate" }
    }

    private suspend fun readMangaDexMuLink(
        mangaDexId: String,
        permissions: Permissions,
        isEnabled: () -> Boolean,
        fresh: Boolean,
    ): String? {
        require(mangaDexId.matches(MANGADEX_WORK_UUID_REGEX))
        val url = "https://api.mangadex.org/manga/$mangaDexId"
        val authorized: suspend () -> Boolean = { isCurrent(permissions, isEnabled) }
        val payload = if (fresh) {
            transport.fetchFreshAuthorized(url, null, authorized)
        } else {
            transport.fetchAuthorized(url, null, authorized)
        }
        checkCurrent(permissions, isEnabled)
        val root = parseObject(payload.content)
        require(root.string("result") == "ok")
        val data = root["data"] as? JsonObject ?: error("Missing MangaDex work")
        require(data.string("id") == mangaDexId && data.string("type") == "manga")
        val attributes = data["attributes"] as? JsonObject ?: error("Missing MangaDex attributes")
        val links = attributes["links"] as? JsonObject ?: return null
        return normalizeMangaUpdatesWorkId(links.string("mu"))
    }

    private suspend fun readSeries(
        seriesId: String,
        permissions: Permissions,
        isEnabled: () -> Boolean,
        fresh: Boolean,
    ): JsonObject {
        require(seriesId.isPositiveMuId())
        val url = "https://api.mangaupdates.com/v1/series/$seriesId"
        val authorized: suspend () -> Boolean = { isCurrent(permissions, isEnabled) }
        val payload = if (fresh) {
            transport.fetchFreshAuthorized(url, null, authorized)
        } else {
            transport.fetchAuthorized(url, null, authorized)
        }
        checkCurrent(permissions, isEnabled)
        return parseObject(payload.content).also {
            require(it.string("series_id") == seriesId) { "Wrong MangaUpdates series returned" }
        }
    }

    private suspend fun permission(isEnabled: () -> Boolean): Permissions {
        currentCoroutineContext().ensureActive()
        check(isEnabled()) { "Inventory integration is disabled" }
        val mu = requireNotNull(settings.get(IntegrationId("mangaupdates")))
        val md = requireNotNull(settings.get(IntegrationId("mangadex")))
        currentCoroutineContext().ensureActive()
        check(
            mu.hasInventoryPermission(IntegrationId("mangaupdates")) &&
                md.hasInventoryPermission(IntegrationId("mangadex")) && isEnabled(),
        ) {
            "MangaUpdates discovery requires consent for both metadata sources"
        }
        return Permissions(mu, md)
    }

    private suspend fun isCurrent(expected: Permissions, isEnabled: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!isEnabled()) return false
        val mu = settings.get(IntegrationId("mangaupdates"))
        val md = settings.get(IntegrationId("mangadex"))
        currentCoroutineContext().ensureActive()
        if (mu == null || md == null) return false
        return isEnabled() && mu == expected.mu && md == expected.md &&
            mu.hasInventoryPermission(IntegrationId("mangaupdates")) &&
            md.hasInventoryPermission(IntegrationId("mangadex"))
    }

    private suspend fun checkCurrent(expected: Permissions, isEnabled: () -> Boolean) {
        check(isCurrent(expected, isEnabled)) { "Inventory permission changed during MangaUpdates discovery" }
    }

    private fun IntegrationSettings.hasInventoryPermission(id: IntegrationId): Boolean =
        integrationId == id && enabled &&
            IntegrationSettingsConfig.decode(configJson).capabilityEnabled(IntegrationCapability.CHAPTER_INVENTORY)

    private fun String.isPositiveMuId(): Boolean =
        matches(Regex("[0-9]{1,18}")) && (toLongOrNull()?.let { it > 0L } == true)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun parseObject(value: String): JsonObject =
        Json.parseToJsonElement(value) as? JsonObject ?: error("Expected metadata JSON object")

    private suspend inline fun <T> capture(crossinline action: suspend () -> T): Result<T> =
        try {
            Result.success(action())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    private companion object {
        const val SERIES_SEARCH_URL = "https://api.mangaupdates.com/v1/series/search"
    }
}

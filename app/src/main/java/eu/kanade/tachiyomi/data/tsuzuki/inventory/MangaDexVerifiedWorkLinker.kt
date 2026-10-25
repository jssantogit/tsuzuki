package eu.kanade.tachiyomi.data.tsuzuki.inventory

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import kotlin.time.Clock

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
    private val transport: InventoryReleaseTransport,
) {
    suspend fun preview(canonicalTitleId: String, mangaDexId: String): Result<MangaDexVerifiedCandidate> =
        previewWithCachePolicy(canonicalTitleId, mangaDexId, forceNetwork = false)

    private suspend fun previewWithCachePolicy(
        canonicalTitleId: String,
        mangaDexId: String,
        forceNetwork: Boolean,
    ): Result<MangaDexVerifiedCandidate> =
        capture {
            require(canonicalTitleId.isNotBlank())
            require(mangaDexId.matches(MANGADEX_UUID)) { "Invalid MangaDex manga UUID" }
            val url = "https://api.mangadex.org/manga/$mangaDexId"
            val payload = if (forceNetwork) transport.fetchFresh(url, null) else transport.fetch(url, null)
            val response = Json.parseToJsonElement(payload.content) as? JsonObject
                ?: error("MangaDex work response is not an object")
            require(response.string("result") == "ok")
            val data = response.objectField("data")
            require(data.string("type") == "manga" && data.string("id") == mangaDexId) {
                "MangaDex returned a different manga"
            }
            val attributes = data.objectField("attributes")
            val names = attributes.objectField("title")
            val label = names.string("en") ?: names.string("ja-ro") ?:
                names.values.firstNotNullOfOrNull {
                    (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                } ?: error("MangaDex work has no display title")
            val links = attributes.objectField("links")
            val verified = titles.getExternalIdentities(canonicalTitleId)
                .filter { it.verified && it.canonicalTitleId == canonicalTitleId }
            val matching = CROSSWALK_KEYS.mapNotNull { (mangaDexKey, tsuzukiProducer) ->
                val claimedExternal = links.string(mangaDexKey) ?: return@mapNotNull null
                val existing = verified.filter { it.provider == tsuzukiProducer }
                require(existing.isEmpty() || existing.all { it.externalId == claimedExternal }) {
                    "MangaDex cross-references another $tsuzukiProducer work"
                }
                existing.firstOrNull { it.externalId == claimedExternal }
            }.distinctBy { it.provider to it.externalId }
            require(matching.isNotEmpty()) {
                "No independently verified MangaDex external-work cross-reference"
            }
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
    suspend fun confirm(canonicalTitleId: String, candidate: MangaDexVerifiedCandidate): Result<Unit> =
        capture {
            require(canonicalTitleId.isNotBlank())
            require(
                previewWithCachePolicy(
                    canonicalTitleId,
                    candidate.mangaDexId,
                    forceNetwork = true,
                ).getOrThrow() == candidate,
            ) { "MangaDex work crosswalk changed before confirmation" }
            val linked = titles.getExternalIdentities(canonicalTitleId)
                .filter { it.provider == "mangadex" }
            require(linked.isEmpty() || linked.all {
                it.externalId == candidate.mangaDexId && it.verified
            }) { "An incompatible MangaDex work is already linked to this canonical title" }

            val owner = titles.getByExternalIdentity("mangadex", candidate.mangaDexId)
            require(owner == null || owner.id == canonicalTitleId) {
                "MangaDex work already belongs to another canonical title"
            }
            if (linked.isEmpty() && owner == null) {
                titles.addExternalIdentity(
                    ExternalIdentity(
                        canonicalTitleId = canonicalTitleId,
                        provider = "mangadex",
                        externalId = candidate.mangaDexId,
                        verified = true,
                        createdAt = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
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
        val MANGADEX_UUID = Regex(
            """^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$""",
        )
        val CROSSWALK_KEYS = listOf(
            "mu" to "mangaupdates",
            "mal" to "myanimelist",
            "kt" to "kitsu",
        )
    }
}

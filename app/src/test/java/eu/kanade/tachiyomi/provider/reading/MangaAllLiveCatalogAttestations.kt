package eu.kanade.tachiyomi.provider.reading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityResult
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.catalog.interactor.toCanonicalReadingMetadata
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Pure validation of the independent Android catalog's real-data artifact.
 * No source candidate/reading HID is ever manufactured from a catalog title.
 * Ambiguous, unavailable or unsupported catalog entries have no creator proof.
 */
internal object MangaAllLiveCatalogAttestations {
    internal data class Case(
        val id: String,
        val query: String,
        val outcome: MangaAllCatalogIdentityStatus,
        val identity: CatalogItem?,
        val canonicalMetadata: ResolvedMetadata?,
    )

    private val json = Json { ignoreUnknownKeys = false }
    private val digest = Regex("[a-f0-9]{64}")
    private val opaqueId = Regex("[A-Za-z0-9._:-]{1,256}")
    private val allowedFields = setOf(
        "schemaVersion", "mode", "caseId", "query", "status", "catalogSource",
        "provider", "providerId", "title", "authors", "artists", "externalIds",
        "creatorsAttested", "catalogsQueried", "catalogsFailed", "canonical",
    )

    internal fun parse(text: String): List<Case> {
        require(text.toByteArray().size <= 200_000) { "Catalog artifact too large" }
        val root = json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schemaVersion", "origin", "canonicalStage", "cases", "sha256"))
        require(root.int("schemaVersion") == 1)
        require(root.str("origin") == "DIRECT_LIVE_ANDROID_CATALOG_ONLY")
        require(root.str("canonicalStage") == "NOT_EXECUTED_HOST")
        val sha = root.str("sha256")
        require(digest.matches(sha))
        val unsigned = JsonObject(root.filterKeys { it != "sha256" }).toString()
        require(sha256(unsigned) == sha) { "Independent catalog artifact digest mismatch" }

        val rows = root["cases"]!!.jsonArray
        require(rows.size <= 30)
        val seen = mutableSetOf<String>()
        return rows.map { element ->
            val row = element.jsonObject
            require(row.keys == allowedFields)
            require(row.int("schemaVersion") == 1)
            require(row.str("mode") == "DIRECT_LIVE_ANDROID_CATALOG_ONLY")
            require(row.str("canonical") == "NOT_EXECUTED_HOST")
            require(row.str("catalogSource") == "mangaupdates")
            val query = row.str("query")
            require(query.isNotBlank() && query.length <= 180 && query.none { it.isISOControl() })
            val id = row.str("caseId")
            require(id == caseId(query) && seen.add(id)) { "Work hash mismatched or duplicated" }
            val outcome = MangaAllCatalogIdentityStatus.valueOf(row.str("status"))
            val owner = row.str("provider")
            val ownerId = row.str("providerId")
            val title = row.str("title")
            val authors = row["authors"].asNames()
            val artists = row["artists"].asNames()
            val crosswalk = row["externalIds"]!!.jsonObject.mapValues { (_, value) ->
                value.jsonPrimitive.content
            }
            require(crosswalk.size <= 16 && crosswalk.all { (provider, externalId) ->
                opaqueId.matches(provider) && !provider.startsWith("app.tsuzuki.") &&
                    opaqueId.matches(externalId)
            })
            val credits = row.bool("creatorsAttested")
            val queried = row.int("catalogsQueried")
            val failed = row.int("catalogsFailed")
            require(queried in 0..8 && failed in 0..queried)
            val identity = if (outcome == MangaAllCatalogIdentityStatus.RESOLVED) {
                require(owner == "mangaupdates" && opaqueId.matches(ownerId))
                require(title.isNotBlank() && title.length <= 1024)
                require(queried > 0 && failed == 0)
                require(crosswalk[owner] == null || crosswalk[owner] == ownerId)
                require(credits == (authors.isNotEmpty() || artists.isNotEmpty()))
                CatalogItem(
                    provider = owner,
                    providerId = ownerId,
                    title = title,
                    authors = authors,
                    artists = artists,
                    externalIds = crosswalk,
                )
            } else {
                require(owner.isEmpty() && ownerId.isEmpty() && title.isEmpty())
                require(authors.isEmpty() && artists.isEmpty() && crosswalk.isEmpty() && !credits)
                null
            }
            val result = MangaAllCatalogIdentityResult(
                status = outcome,
                identity = identity,
                candidateCount = if (identity != null) 1 else 0,
                catalogsQueried = queried,
                catalogsFailed = failed,
                creatorsAttested = credits,
            )
            val metadata = result.toCanonicalReadingMetadata()
            require(outcome != MangaAllCatalogIdentityStatus.RESOLVED || metadata != null)
            Case(id, query, outcome, identity, metadata)
        }
    }

    private fun JsonObject.str(name: String): String {
        val field = get(name) as? JsonPrimitive
        require(field?.isString == true) { "Expected catalog string $name" }
        return field.content
    }

    private fun JsonObject.int(name: String): Int {
        val primitive = get(name) as? JsonPrimitive
        require(primitive != null && !primitive.isString)
        return primitive.content.toInt()
    }

    private fun JsonObject.bool(name: String): Boolean {
        val primitive = get(name) as? JsonPrimitive
        require(primitive != null && !primitive.isString && primitive.content in setOf("true", "false"))
        return primitive.content == "true"
    }

    private fun kotlinx.serialization.json.JsonElement?.asNames(): List<String> {
        val array = this as? JsonArray ?: error("Missing real creator list")
        require(array.size <= 16)
        return array.map { element ->
            val primitive = element as? JsonPrimitive
            require(primitive?.isString == true)
            primitive.content.also { name ->
                require(name.isNotBlank() && name.length <= 128 && name.none { it.isISOControl() })
            }
        }
    }

    private fun caseId(title: String): String {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase()
        return "user-" + sha256(normalized).take(12)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

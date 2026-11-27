package eu.kanade.tachiyomi.data.track.shikimori.dto

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.tsuzuki.integration.CatalogCreatorAliasEvidence
import tachiyomi.domain.tsuzuki.integration.CreatorCreditRole

@Serializable
data class SMSearchResult(
    val data: SMMangaResults,
    val errors: List<JsonObject> = emptyList(),
) {
    fun completeMangas(): List<SMManga> {
        check(errors.isEmpty()) { "SHIKI_GRAPHQL_PARTIAL_RESPONSE" }
        return data.mangas
    }

    fun exactManga(id: Long): SMManga? {
        require(id > 0)
        val rows = completeMangas()
        if (rows.isEmpty()) return null
        check(rows.size == 1 && rows.single().id == id) { "SHIKI_DETAIL_IDENTITY_MISMATCH" }
        return rows.single()
    }
}

@Serializable
data class SMMangaResults(
    val mangas: List<SMManga>,
)

@Serializable
data class SMManga(
    val id: Long,
    val name: String,
    val chapters: Long,
    val score: Double?,
    val url: String,
    val status: String?,
    val poster: SMPoster?,
    val airedOn: SMAiredDate?,
    val description: String?,
    val kind: String?,
    val personRoles: List<SMPersonRole>?,
    val english: String? = null,
    val japanese: String? = null,
    val synonyms: List<String>? = null,
) {
    fun toTrack(trackerId: Long): TrackSearch {
        return TrackSearch.create(trackerId).apply {
            remote_id = this@SMManga.id
            title = name
            alternate_titles = (listOfNotNull(english, japanese) + synonyms.orEmpty())
                .map(String::trim)
                .filter(String::isNotBlank)
                .filterNot { it == name }
                .distinct()
            total_chapters = chapters
            cover_url = poster?.mainUrl.orEmpty()
            summary = description.orEmpty()
            score = this@SMManga.score?.takeIf { it > 0.0 } ?: -1.0
            tracking_url = url
            publishing_status = this@SMManga.status.orEmpty()
            publishing_type = kind?.replace("one_shot", "oneshot").orEmpty()
            start_date = airedOn?.date.orEmpty()
            personRoles?.forEach { personRole ->
                personRole.roles.forEach { role ->
                    if ("Story" in role) authors += personRole.person.name
                    if ("Art" in role) artists += personRole.person.name
                }
            }
        }
    }
}

@Serializable
data class SMPoster(
    val mainUrl: String,
)

@Serializable
data class SMAiredDate(
    val date: String?,
)

@Serializable
data class SMPersonRole(
    val person: SMPerson,
    @SerialName("rolesEn")
    val roles: List<String>,
)

@Serializable
data class SMPerson(
    val name: String,
    val id: String? = null,
    val japanese: String? = null,
    val russian: String? = null,
)

@Serializable
data class SMCreatorResponse(
    val data: SMCreatorResults,
    val errors: List<JsonObject> = emptyList(),
) {
    fun verifiedAliases(workId: Long): List<CatalogCreatorAliasEvidence> {
        if (errors.isNotEmpty()) return emptyList()
        val work = data.mangas.singleOrNull()?.takeIf { it.id == workId.toString() } ?: return emptyList()
        val credits = work.personRoles.orEmpty()
        if (credits.size > 32) return emptyList()
        return credits.flatMap { credit ->
            val person = credit.person
            val id = person.id?.takeIf { it.toLongOrNull()?.let { value -> value > 0 } == true }
                ?: return@flatMap emptyList()
            val name = person.name.trim()
            if (name.isBlank() || name.length > 128) return@flatMap emptyList()
            val aliases = listOfNotNull(person.japanese, person.russian)
                .map(String::trim)
                .filter { it.isNotBlank() && it.length <= 128 && it != name }
                .distinct()
            val roles = buildList {
                if (credit.roles.any { "Story" in it }) add(CreatorCreditRole.AUTHOR)
                if (credit.roles.any { "Art" in it }) add(CreatorCreditRole.ARTIST)
            }
            roles.map { role ->
                CatalogCreatorAliasEvidence(
                    workExternalId = workId.toString(),
                    creatorExternalId = id,
                    role = role,
                    creditedName = name,
                    primaryName = name,
                    aliases = aliases,
                )
            }
        }.takeIf { it.size <= 32 }.orEmpty()
    }
}

@Serializable
data class SMCreatorResults(val mangas: List<SMCreatorWork>)

@Serializable
data class SMCreatorWork(val id: String, val personRoles: List<SMPersonRole>?)

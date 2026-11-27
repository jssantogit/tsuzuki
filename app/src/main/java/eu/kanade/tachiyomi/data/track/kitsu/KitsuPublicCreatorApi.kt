package eu.kanade.tachiyomi.data.track.kitsu

import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuMangaStaff
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Public catalog credits, independent of account and tracking state. */
fun interface KitsuPublicCreatorApi {
    suspend fun getPublicCreatorCredits(id: String): KitsuPublicMangaCredits?
}

@Serializable
data class KitsuPublicCreatorResponse(
    val data: KitsuPublicCreatorRoot? = null,
    val errors: List<JsonObject> = emptyList(),
)

@Serializable
data class KitsuPublicCreatorRoot(
    val findMangaById: KitsuPublicMangaCredits?,
)

@Serializable
data class KitsuPublicMangaCredits(
    val id: String,
    val subtype: String,
    val staff: KitsuPublicStaffConnection,
)

@Serializable
data class KitsuPublicStaffConnection(
    val nodes: List<KitsuMangaStaff>,
    val pageInfo: KitsuPublicStaffPageInfo,
)

@Serializable
data class KitsuPublicStaffPageInfo(
    val hasNextPage: Boolean,
)

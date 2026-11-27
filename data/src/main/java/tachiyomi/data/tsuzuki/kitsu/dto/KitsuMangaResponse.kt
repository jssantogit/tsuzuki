package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaResponse(
    @Required val data: List<KitsuMangaResource> = emptyList(),
    val meta: KitsuMeta? = null,
    val links: KitsuLinks? = null,
    val included: List<KitsuMappingResource> = emptyList(),
)

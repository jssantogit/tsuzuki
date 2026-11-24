package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MUAuthor(
    val name: String,
    val type: String,
    @SerialName("author_id")
    val authorId: Long? = null,
)

@Serializable
data class MUAuthorDetail(
    @SerialName("author_id")
    val authorId: Long? = null,
    val name: String,
    val associated: List<MUAuthorName> = emptyList(),
    val id: Long? = null,
) {
    fun confirmsId(expected: Long): Boolean = expected > 0 &&
        (authorId == expected || id == expected) &&
        (authorId == null || authorId == expected) && (id == null || id == expected)
}

@Serializable
data class MUAuthorName(
    val name: String,
)

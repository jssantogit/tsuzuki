package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MUCurrentUser(
    val username: String,
    @SerialName("user_id") val userId: Long? = null,
)

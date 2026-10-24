package tachiyomi.domain.tsuzuki.integration.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Only non-secret integration preferences are portable between devices.
 * Shared by cloud sync and the canonical backup writer to avoid divergent
 * credential handling.
 */
object PortableIntegrationConfig {
    fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .filterNot { (key, _) -> isSensitiveKey(key) }
                .associate { (key, value) -> key to sanitize(value) },
        )
        is JsonArray -> JsonArray(element.map(::sanitize))
        else -> element
    }

    fun redact(raw: String, json: Json): String =
        sanitize(json.parseToJsonElement(raw).jsonObject).toString()

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        return SENSITIVE_KEY_MARKERS.any(normalized::contains)
    }

    private val SENSITIVE_KEY_MARKERS = listOf(
        "token",
        "secret",
        "password",
        "credential",
        "clientid",
        "clientreference",
        "apikey",
        "authorization",
        "cookie",
        "session",
    )
}

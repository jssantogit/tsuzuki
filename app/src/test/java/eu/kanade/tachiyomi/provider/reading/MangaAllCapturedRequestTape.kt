package eu.kanade.tachiyomi.provider.reading

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contract-only, case-scoped tape for already validated real-source captures.
 *
 * A Host request MUST match an unused captured request. The tape never guesses
 * aliases, repeats a response for another facet, invents chapters or renders
 * redacted Reader URLs. Only the real Host gateway may decode the returned DTO.
 */
internal class MangaAllCapturedRequestTape(records: List<JsonObject>) {
    private data class Entry(val operation: String, val record: JsonObject)

    private val entries = records.mapNotNull { record ->
        val operation = record.string("operation")
        if (operation == "lookup" || operation == "chapters") {
            Entry(operation, record)
        } else {
            null // pages metadata is intentionally not replayable as CDN delivery
        }
    }
    private val consumed = mutableSetOf<Int>()

    internal var replayGapCount: Int = 0
        private set
    internal var upstreamErrorCount: Int = 0
        private set
    internal val consumedCount: Int
        get() = consumed.size

    internal fun replay(operation: String, hostRequest: JsonObject): JsonObject {
        val index = entries.indices.firstOrNull { position ->
            val entry = entries[position]
            position !in consumed &&
                entry.operation == operation &&
                matches(operation, entry.record["request"] as? JsonObject, hostRequest)
        } ?: -1
        if (index < 0) {
            replayGapCount++
            throw MangaAllReplayGapException()
        }
        consumed += index
        val record = entries[index].record
        if (record.string("outcome") == "SOURCE_ERROR") {
            upstreamErrorCount++
            throw MangaAllCapturedSourceErrorException()
        }
        return record["response"] as? JsonObject
            ?: error("Validated real-source record is missing its result")
    }

    private fun matches(operation: String, recorded: JsonObject?, host: JsonObject): Boolean {
        if (recorded == null) return false
        return when (operation) {
            "lookup" ->
                recorded["titles"] == host["titles"] &&
                    recorded.string("requestedLanguage") == host.string("requestedLanguage") &&
                    recorded.string("cursor") == host.string("cursor") &&
                    recorded.string("editorialEvidenceVersion") == host.string("editorialEvidenceVersion") &&
                    recorded.string("coverageEvidenceVersion") == host.string("coverageEvidenceVersion")
            "chapters" -> {
                val left = recorded["binding"] as? JsonObject
                val right = host["binding"] as? JsonObject
                left != null && right != null &&
                    left.string("providerId") == right.string("providerId") &&
                    left.string("facetId") == right.string("facetId") &&
                    left.string("externalWorkId") == right.string("externalWorkId") &&
                    recorded.string("cursor") == host.string("cursor")
            }
            else -> false
        }
    }

    private fun JsonObject.string(field: String): String? =
        (get(field) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

/** No captured response matches the exact Host request; never substitute another result. */
internal class MangaAllReplayGapException : IllegalStateException("REPLAY_GAP")

/** A source request was captured but its upstream call failed: never report an empty inventory. */
internal class MangaAllCapturedSourceErrorException : IllegalStateException("INCONCLUSIVE_UPSTREAM")

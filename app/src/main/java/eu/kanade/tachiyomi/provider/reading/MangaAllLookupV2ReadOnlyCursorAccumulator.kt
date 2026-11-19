package eu.kanade.tachiyomi.provider.reading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Inactive, offline-only V2 evidence accumulator. Nothing in this type can
 * create a ProviderBindingRef, chapter observation, preferred edition or
 * Reader page. It is NOT connected to the production V1 gateway.
 *
 * One instance belongs to one immutable title/language lookup. The caller
 * passes back the exact cursor from the previous page; source cursors never
 * cross a source boundary. The signed code8 source remains untouched.
 */
internal class MangaAllLookupV2ReadOnlyCursorAccumulator(
    language: String = "en",
) {
    init {
        require(language == "en") { "Three-source V2 audit requires the common English facet" }
    }

    enum class Verdict {
        IN_PROGRESS,
        COMPLETE_OBSERVED,
        INCONCLUSIVE,
    }

    data class SourceHistory(
        val source: String,
        val completed: Boolean,
        val observedCandidates: Int,
        val pagesQueried: Int,
        val hadFailure: Boolean,
        val lastFailureCode: String?,
    )

    data class Snapshot(
        val verdict: Verdict,
        val pages: Int,
        val sourceHistory: List<SourceHistory>,
        val observedWorks: List<MangaAllLookupV2ReadOnlyDiagnostics.ObservedWork>,
        val nextCursor: String?,
    )

    private data class CursorPart(
        val retry: Boolean,
        val partialOffset: Boolean,
    )

    private data class MutableHistory(
        var completed: Boolean = false,
        var pagesQueried: Int = 0,
        var hadFailure: Boolean = false,
        var tainted: Boolean = false,
        var lastFailureCode: String? = null,
    )

    private val sourceOrder = listOf("mangadex", "mangadot", "mangafire")
    private val histories = sourceOrder.associateWith { MutableHistory() }
    private val works = linkedMapOf<String, MangaAllLookupV2ReadOnlyDiagnostics.ObservedWork>()
    private val seenCursors = hashSetOf<String>()
    private var pages = 0
    private var expectedCursor: String? = null
    private var closed = false

    /** Return null, and permanently invalidate this audit, on any contradiction. */
    private fun reject(): Snapshot? {
        closed = true
        return null
    }

    private fun sourceCursor(value: kotlinx.serialization.json.JsonElement?): Boolean {
        if (value == JsonNull) return true
        val primitive = value as? JsonPrimitive ?: return false
        if (!primitive.isString) return false
        val raw = primitive.contentOrNull ?: return false
        return raw.length in 1..64 &&
            raw.none { it.isISOControl() } &&
            !raw.contains("://") &&
            !raw.contains("cookie", ignoreCase = true)
    }

    /** Parse the actual signed router's bounded, source-scoped cursor syntax. */
    private fun decodeCursor(value: String?): Map<String, CursorPart>? {
        if (value == null) return emptyMap()
        if (value.length !in 1..512) return null
        val root = try {
            Json.parseToJsonElement(value) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if (root.keys != setOf("v", "remaining") ||
            (root["v"] as? JsonPrimitive)?.intOrNull != 1
        ) {
            return null
        }
        val remaining = root["remaining"] as? JsonObject ?: return null
        if (remaining.isEmpty() || remaining.keys.any { it !in sourceOrder }) return null
        val result = linkedMapOf<String, CursorPart>()
        for ((source, raw) in remaining) {
            val cursor = when (raw) {
                is JsonPrimitive -> {
                    if (raw == JsonNull || !sourceCursor(raw)) return null
                    CursorPart(retry = false, partialOffset = false)
                }
                is JsonObject -> {
                    if (!sourceCursor(raw["c"])) return null
                    when {
                        raw.keys == setOf("c", "r") &&
                            (raw["r"] as? JsonPrimitive)?.intOrNull == 1 ->
                            CursorPart(retry = true, partialOffset = false)
                        raw.keys == setOf("c", "o") &&
                            ((raw["o"] as? JsonPrimitive)?.intOrNull?.let { it in 0..200 } == true) ->
                            CursorPart(retry = false, partialOffset = true)
                        else -> return null
                    }
                }
                else -> return null
            }
            result[source] = cursor
        }
        return result
    }

    /**
     * An audit never emits canonical "PASS". COMPLETE_OBSERVED merely means
     * the available V2 metadata pages ended consistently; manual editorial
     * identity and signed Host/Reader authorization are separate gates.
     */
    fun accept(payload: String, requestCursor: String? = null): Snapshot? {
        val audit = MangaAllLookupV2ReadOnlyDiagnostics.decodeReadOnly(payload) ?: return reject()
        return acceptValidated(audit, requestCursor)
    }

    /**
     * Accept only a read-only audit already decoded and validated at the
     * SCRIPT gateway boundary. This never creates a production work binding.
     */
    fun acceptValidated(
        audit: MangaAllLookupV2ReadOnlyDiagnostics.ReadOnlyAudit,
        requestCursor: String? = null,
    ): Snapshot? {
        if (closed || pages >= 8 || requestCursor != expectedCursor) return reject()
        val expected = if (pages == 0) {
            sourceOrder.toSet()
        } else {
            decodeCursor(expectedCursor)?.keys ?: return reject()
        }
        val called = audit.sources.filter { it.status != "NOT_QUERIED" }.map { it.source }.toSet()
        if (called != expected) return reject()
        val next = decodeCursor(audit.continuationCursor) ?: return reject()
        if (audit.continuationCursor != null) {
            if (!seenCursors.add(audit.continuationCursor)) return reject()
            if (next.keys.any { it !in expected }) return reject()
        }
        val current = audit.sources.associateBy { it.source }
        for (source in sourceOrder) {
            val observation = current[source] ?: return reject()
            val resumed = if (pages == 0) null else decodeCursor(expectedCursor)?.get(source)
            val continuation = next[source]
            if (observation.status == "NOT_QUERIED") {
                if (continuation != null) return reject()
                continue
            }
            if (histories.getValue(source).completed) return reject()
            if (audit.canContinue &&
                observation.status == "SUCCESS_PAGE" &&
                observation.pageComplete == false &&
                continuation == null
            ) {
                return reject()
            }
            if (continuation != null) {
                if (observation.status == "FAILED") {
                    if (!continuation.retry || resumed?.retry == true) return reject()
                } else if (observation.status == "SUCCESS_PAGE") {
                    if (continuation.retry ||
                        (observation.pageComplete == true && !continuation.partialOffset)
                    ) {
                        return reject()
                    }
                }
            }
        }
        // Verify every candidate before mutating persistent page history.
        for (work in audit.observedWorks) {
            val prior = works[work.sourceWorkId]
            if (prior != null && prior != work) return reject()
        }
        val newWorks = audit.observedWorks.count { it.sourceWorkId !in works }
        if (works.size + newWorks > 240) return reject()
        audit.observedWorks.forEach { works.putIfAbsent(it.sourceWorkId, it) }
        for (source in sourceOrder) {
            val item = current.getValue(source)
            if (item.status == "NOT_QUERIED") continue
            val history = histories.getValue(source)
            history.pagesQueried++
            if (item.status == "FAILED") {
                history.hadFailure = true
                history.lastFailureCode = item.failureCode
            }
            if (item.status == "SUCCESS_PAGE" &&
                item.pageComplete == true &&
                !item.unsafeCandidate &&
                !item.observationsTruncated &&
                !next.containsKey(source)
            ) {
                history.completed = true
            }
            if (item.unsafeCandidate || item.observationsTruncated) {
                history.tainted = true
                history.hadFailure = true
                history.lastFailureCode = "INCOMPLETE_SOURCE_OBSERVATION"
            }
        }
        pages++
        val pageBudgetExhausted = pages == 8 && audit.continuationCursor != null
        expectedCursor = if (pageBudgetExhausted) null else audit.continuationCursor
        val allComplete = histories.values.all { it.completed && !it.tainted }
        val verdict = when {
            pageBudgetExhausted -> Verdict.INCONCLUSIVE
            expectedCursor != null -> Verdict.IN_PROGRESS
            allComplete -> Verdict.COMPLETE_OBSERVED
            else -> Verdict.INCONCLUSIVE
        }
        if (expectedCursor == null) closed = true
        return Snapshot(
            verdict = verdict,
            pages = pages,
            sourceHistory = sourceOrder.map { source ->
                val h = histories.getValue(source)
                SourceHistory(
                    source = source,
                    completed = h.completed,
                    observedCandidates = works.values.count { it.source == source },
                    pagesQueried = h.pagesQueried,
                    hadFailure = h.hadFailure,
                    lastFailureCode = h.lastFailureCode,
                )
            },
            observedWorks = works.values.toList(),
            nextCursor = expectedCursor,
        )
    }
}

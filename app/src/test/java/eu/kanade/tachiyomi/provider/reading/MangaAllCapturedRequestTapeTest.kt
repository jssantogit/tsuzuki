package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Contract-only requests (no fabricated candidate, catalog author or chapter).
 * These tests prove replay isolation. Live mode still requires signed capture.
 */
class MangaAllCapturedRequestTapeTest {
    private fun record(
        operation: String,
        request: String,
        outcome: String = "RESULT",
        response: String = """{"items":[],"nextCursor":null}""",
    ): JsonObject = Json.parseToJsonElement(
        """{"operation":"$operation","request":$request,"outcome":"$outcome","response":$response}""",
    ).jsonObject

    private fun request(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `missing lookup request is REPLAY_GAP and does not consume a different capture`() {
        val original = """{"titles":["CONTRACT_ONLY"],"requestedLanguage":"en","cursor":null}"""
        val tape = MangaAllCapturedRequestTape(listOf(record("lookup", original)))
        assertThrows<MangaAllReplayGapException> {
            tape.replay("lookup", request(original.replace("CONTRACT_ONLY", "NOT_CAPTURED")))
        }
        tape.replayGapCount shouldBe 1
        tape.replay("lookup", request(original))["nextCursor"].toString() shouldBe "null"
        assertThrows<MangaAllReplayGapException> { tape.replay("lookup", request(original)) }
        tape.replayGapCount shouldBe 2
    }

    @Test
    fun `a different language or continuation cursor cannot steal a captured page`() {
        val first = """{"titles":["CONTRACT_ONLY"],"requestedLanguage":"en","cursor":null}"""
        val next = """{"titles":["CONTRACT_ONLY"],"requestedLanguage":"en","cursor":"page-2"}"""
        val tape = MangaAllCapturedRequestTape(
            listOf(
                record("lookup", first, response = """{"items":[],"nextCursor":"page-2"}"""),
                record("lookup", next),
            ),
        )
        assertThrows<MangaAllReplayGapException> {
            tape.replay("lookup", request(first.replace("\"en\"", "\"pt-br\"")))
        }
        assertThrows<MangaAllReplayGapException> {
            tape.replay("lookup", request(next.replace("page-2", "wrong-cursor")))
        }
        tape.replay("lookup", request(first))["nextCursor"].toString() shouldBe "\"page-2\""
        tape.replay("lookup", request(next))["nextCursor"].toString() shouldBe "null"
    }

    @Test
    fun `chapter binding and facet must match exactly even when work id is identical`() {
        val exact = """{"binding":{"providerId":"app.tsuzuki.mangaall","facetId":"en",""" +
            """"externalWorkId":"mangadex:contract"},"cursor":null}"""
        val tape = MangaAllCapturedRequestTape(listOf(record("chapters", exact)))
        assertThrows<MangaAllReplayGapException> {
            tape.replay("chapters", request(exact.replace("\"en\"", "\"pt-br\"")))
        }
        assertThrows<MangaAllReplayGapException> {
            tape.replay("chapters", request(exact.replace("mangadex:contract", "mangafire:contract")))
        }
        tape.replay("chapters", request(exact))["items"].toString() shouldBe "[]"
    }

    @Test
    fun `source errors are inconclusive while source failures preserve the typed payload`() {
        val query = """{"titles":["CONTRACT_ONLY"],"requestedLanguage":"en","cursor":null}"""
        val errorTape = MangaAllCapturedRequestTape(listOf(record("lookup", query, "SOURCE_ERROR")))
        assertThrows<MangaAllCapturedSourceErrorException> {
            errorTape.replay("lookup", request(query))
        }
        errorTape.replayGapCount shouldBe 0

        val failure = """{"items":[],"lookupFailureCode":"UPSTREAM_UNAVAILABLE","nextCursor":null}"""
        val failureTape = MangaAllCapturedRequestTape(
            listOf(record("lookup", query, "SOURCE_FAILURE", failure)),
        )
        failureTape.replay("lookup", request(query))["lookupFailureCode"].toString() shouldBe
            "\"UPSTREAM_UNAVAILABLE\""
    }

    @Test
    fun `redacted Reader pages are never replayed as actual image responses`() {
        val pageRequest = """{"binding":{"providerId":"app.tsuzuki.mangaall","facetId":"en","externalWorkId":"mangadex:contract"},"providerChapterId":"mangadex:chapter"}"""
        val tape = MangaAllCapturedRequestTape(
            listOf(record("pages", pageRequest, response = """{"type":"page_list","pageCount":1}""")),
        )
        assertThrows<MangaAllReplayGapException> {
            tape.replay("pages", request(pageRequest))
        }
        tape.replayGapCount shouldBe 1
    }
}

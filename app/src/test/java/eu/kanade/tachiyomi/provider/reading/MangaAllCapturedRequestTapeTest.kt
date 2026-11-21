package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
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
    fun `signed code9 English V1 tape replays the actual Host serialized defaults`() {
        // The public Domain request omits diagnostic/editorial versions, but
        // the production ScriptProviderReadingGateway internal V1 DTO has both
        // defaults and its Json serializer encodes them. A live capture must
        // reproduce this exact wire behavior instead of guessing Domain shape.
        val actualHostJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
        }.encodeToString(
            ScriptProviderReadingGateway.LookupRequestDto(
                titles = listOf("CONTRACT_ONLY"),
                requestedLanguage = "en",
                coverageEvidenceVersion = 1,
            ),
        )
        val actualHostRequest = request(actualHostJson)
        actualHostRequest["diagnosticsVersion"].toString() shouldBe "1"
        actualHostRequest["editorialEvidenceVersion"].toString() shouldBe "1"
        actualHostRequest["coverageEvidenceVersion"].toString() shouldBe "1"
        actualHostRequest["requestedLanguage"].toString() shouldBe "\"en\""

        val captured =
            """{"titles":["CONTRACT_ONLY"],"requestedLanguage":"en","diagnosticsVersion":1,"editorialEvidenceVersion":1,"coverageEvidenceVersion":1}"""
        val tape = MangaAllCapturedRequestTape(listOf(record("lookup", captured)))
        val wrongRequests = listOf(
            captured.replace("\"en\"", "\"pt-br\""),
            captured.replace("\"coverageEvidenceVersion\":1", "\"coverageEvidenceVersion\":2"),
            captured.replace(",\"coverageEvidenceVersion\":1", ""),
            captured.replace(",\"editorialEvidenceVersion\":1", ""),
            captured.replace("CONTRACT_ONLY", "UNCAPTURED_WORK"),
        )
        wrongRequests.forEach { wire ->
            assertThrows<MangaAllReplayGapException> { tape.replay("lookup", request(wire)) }
        }
        tape.replayGapCount shouldBe wrongRequests.size
        tape.replay("lookup", actualHostRequest)["items"].toString() shouldBe "[]"
        tape.consumedCount shouldBe 1
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
        val pageRequest = """{"binding":{"providerId":"app.tsuzuki.mangaall","facetId":"en",""" +
            """"externalWorkId":"mangadex:contract"},"providerChapterId":"mangadex:chapter"}"""
        val tape = MangaAllCapturedRequestTape(
            listOf(record("pages", pageRequest, response = """{"type":"page_list","pageCount":1}""")),
        )
        assertThrows<MangaAllReplayGapException> {
            tape.replay("pages", request(pageRequest))
        }
        tape.replayGapCount shouldBe 1
    }
}

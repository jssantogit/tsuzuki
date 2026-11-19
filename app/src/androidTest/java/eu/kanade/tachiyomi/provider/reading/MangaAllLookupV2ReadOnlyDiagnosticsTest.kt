package eu.kanade.tachiyomi.provider.reading

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This probe does NOT call source HTTP or the real Reader. It tests only
 * parser boundaries of the unsigned V2 candidate; code8 V1 stays published.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllLookupV2ReadOnlyDiagnosticsTest {

    private fun source(
        name: String,
        outcome: String = "SUCCESS_PAGE",
        count: Int = 1,
        pageComplete: Boolean? = true,
        failure: JSONObject? = null,
    ): JSONObject = JSONObject()
        .put("source", name)
        .put("attempts", if (outcome == "NOT_QUERIED") 0 else 1)
        .put("status", outcome)
        .put("pageComplete", pageComplete ?: JSONObject.NULL)
        .put("observedCandidates", count)
        .put("observationsTruncated", false)
        .put("unsafeCandidate", false)
        .put("failure", failure ?: JSONObject.NULL)

    private fun candidate(name: String, id: String): JSONObject = JSONObject()
        .put("source", name)
        .put("externalWorkId", id)
        .put("title", "Naruto")
        .put("aliases", JSONArray().put("Naruto"))
        .put("authors", JSONArray().put("Author Example"))
        .put("editorialIdentityVerified", false)
        .put("bindingEligible", false)

    private fun full(): JSONObject = JSONObject()
        .put("contractVersion", 2)
        .put("providerId", "app.tsuzuki.mangaall")
        .put("state", "COMPLETE_SINGLE_PAGE")
        .put("lookupComplete", true)
        .put("canContinue", false)
        .put("continuationCursor", JSONObject.NULL)
        .put(
            "sourceStatuses",
            JSONArray()
                .put(source("mangadex"))
                .put(source("mangadot"))
                .put(source("mangafire")),
        )
        .put(
            "observedCandidates",
            JSONArray()
                .put(candidate("mangadex", "mangadex:one"))
                .put(candidate("mangadot", "mangadot:two"))
                .put(candidate("mangafire", "mangafire:three")),
        )
        .put("eligibleForCanonicalBinding", false)
        .put("eligibleForReader", false)
        .put("provenance", "UNSIGNED_CANDIDATE_NOT_LIVE")

    private fun partial(): JSONObject {
        val blocked = JSONObject()
            .put("code", "CHALLENGE_BLOCKED")
            .put("httpStatus", 403)
            .put("retryable", true)
        return full()
            .put("state", "PARTIAL_EVIDENCE")
            .put("lookupComplete", false)
            .put("canContinue", true)
            .put("continuationCursor", """{"v":1,"remaining":{"mangadot":{"c":null,"r":1}}}""")
            .put(
                "sourceStatuses",
                JSONArray()
                    .put(source("mangadex"))
                    .put(source("mangadot", "FAILED", 0, null, blocked))
                    .put(source("mangafire", "SUCCESS_PAGE", 2)),
            )
            .put(
                "observedCandidates",
                JSONArray()
                    .put(candidate("mangadex", "mangadex:one"))
                    .put(candidate("mangafire", "mangafire:two"))
                    .put(candidate("mangafire", "mangafire:three")),
            )
    }

    /** Read-only Host validation is fail-closed, especially for partial pages. */
    @Test
    fun mangaAllUnsignedV2ReadOnlyContract_rejectsCanonicalPromotionAndForeignSource() {
        val valid = MangaAllLookupV2ReadOnlyDiagnostics.decodeReadOnly(full().toString())
        assertNotNull(valid)
        assertEquals("COMPLETE_SINGLE_PAGE", valid?.state)
        assertEquals(3, valid?.observedWorks?.size)
        assertEquals(listOf("mangadex", "mangadot", "mangafire"), valid?.sources?.map { it.source })

        val mixed = MangaAllLookupV2ReadOnlyDiagnostics.decodeReadOnly(partial().toString())
        assertNotNull(mixed)
        assertFalse(requireNotNull(mixed).lookupComplete)
        assertEquals(3, mixed.observedWorks.size)
        assertEquals("FAILED", mixed.sources[1].status)
        assertEquals("CHALLENGE_BLOCKED", mixed.sources[1].failureCode)
        assertEquals(403, mixed.sources[1].httpStatus)

        // Each mutation must fail closed; no V1 response or candidate V2
        // may silently become a production work/edition/chapter binding.
        val violations = listOf(
            full().put("eligibleForReader", true),
            full().put("eligibleForCanonicalBinding", true),
            full().put("provenance", "SIGNED_PROVIDER_DIRECT_LIVE"),
            full().put("headers", JSONObject().put("Authorization", "Bearer secret")),
            full().put("contractVersion", 1),
            full().put(
                "sourceStatuses",
                JSONArray().put(source("mangafire"))
                    .put(source("mangadot")).put(source("mangadex")),
            ),
            JSONObject(full().toString()).also {
                it.getJSONArray("observedCandidates").getJSONObject(0)
                    .put("bindingEligible", true)
            },
            JSONObject(full().toString()).also {
                it.getJSONArray("observedCandidates").getJSONObject(0)
                    .put("externalWorkId", "mangafire:foreign")
            },
            JSONObject(full().toString()).also {
                it.getJSONArray("observedCandidates").getJSONObject(0)
                    .put("title", "Bearer: private-token")
            },
            JSONObject(full().toString()).also {
                it.getJSONArray("sourceStatuses").getJSONObject(0)
                    .put("observedCandidates", 2)
            },
            partial().put("lookupComplete", true),
            partial().put("state", "COMPLETE_SINGLE_PAGE"),
            JSONObject(partial().toString()).also {
                it.getJSONArray("sourceStatuses").getJSONObject(1)
                    .getJSONObject("failure").put("httpStatus", 200)
            },
        )
        violations.forEachIndexed { index, malformed ->
            assertNull(
                "unsafe V2 variation $index must not decode",
                MangaAllLookupV2ReadOnlyDiagnostics.decodeReadOnly(malformed.toString()),
            )
        }
        assertNull(
            MangaAllLookupV2ReadOnlyDiagnostics.decodeReadOnly(
                """{"items":[],"nextCursor":null}""",
            ),
        )
    }
}

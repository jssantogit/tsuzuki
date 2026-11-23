package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaAllMangaFireBrowserApiEvidenceTest {

    @Test
    fun `real search and chapter items responses pass shallow browser validation`() {
        MangaAllMangaFireBrowserApiEvidence.isApiJson("""{"items":[{"id":"abc"}],"meta":{"page":1}}""") shouldBe true
        MangaAllMangaFireBrowserApiEvidence.isApiJson("""{"items":[]}""") shouldBe true
    }

    @Test
    fun `real title detail and chapter pages data wrappers pass`() {
        MangaAllMangaFireBrowserApiEvidence.isApiJson("""{"data":{"id":"abc"}}""") shouldBe true
    }

    @Test
    fun `WAF HTML and JSON errors never become accepted API evidence`() {
        for (body in listOf(
            "<!doctype html><html><body>checking</body></html>",
            """{"error":"captcha_required","challenge":"/@waf/challenge"}""",
            """{"error":"access_denied","items":[]}""",
            """{"message":"verification"}""",
            """{"data":null}""",
            """[{"id":"abc"}]""",
            "not json",
            "",
        )) {
            MangaAllMangaFireBrowserApiEvidence.isApiJson(body) shouldBe false
        }
    }
}

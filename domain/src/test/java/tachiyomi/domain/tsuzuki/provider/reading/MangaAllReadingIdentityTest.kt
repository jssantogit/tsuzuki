package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class MangaAllReadingIdentityTest {

    private val mangaAllId = ProviderId("app.tsuzuki.mangaall")
    private val legacyId = ProviderId("app.tsuzuki.mangafire")

    @Test
    fun `source and chapter IDs must remain paired inside one MangaAll provider`() {
        val fire = ProviderBindingRef(mangaAllId, "en", "mangafire:729pj")
        MangaAllReadingIdentity.accepts(fire, "mangafire:1234", "en") shouldBe true
        MangaAllReadingIdentity.sourceId(fire) shouldBe "mangafire"
        MangaAllReadingIdentity.sourceId(
            ProviderBindingRef(mangaAllId, "en", "mangadex:uuid-1"),
        ) shouldBe "mangadex"
        MangaAllReadingIdentity.accepts(fire, "mangadex:1234", "en") shouldBe false
        MangaAllReadingIdentity.accepts(fire, "1234", "en") shouldBe false
        MangaAllReadingIdentity.accepts(
            ProviderBindingRef(mangaAllId, "en", "mangadex:uuid-1"),
            "mangadot:1234",
            "en",
        ) shouldBe false
        MangaAllReadingIdentity.accepts(
            ProviderBindingRef(mangaAllId, "en", "mangaball:416"),
            "mangaball:12",
            "en",
        ) shouldBe false
    }

    @Test
    fun `Reader must respect source-supported and actually reported chapter language`() {
        val dexPt = ProviderBindingRef(mangaAllId, "pt-br", "mangadex:uuid-1")
        val dotPt = ProviderBindingRef(mangaAllId, "pt-br", "mangadot:317")
        MangaAllReadingIdentity.accepts(dexPt, "mangadex:chapter-1", "pt-br") shouldBe true
        MangaAllReadingIdentity.accepts(dotPt, "mangadot:uploads:123", "pt-br") shouldBe true
        MangaAllReadingIdentity.accepts(dexPt, "mangadex:chapter-1", "en") shouldBe false
        MangaAllReadingIdentity.accepts(dexPt, "mangadex:chapter-1", null) shouldBe false
        MangaAllReadingIdentity.accepts(
            ProviderBindingRef(mangaAllId, "pt-br", "mangafire:729pj"),
            "mangafire:1234",
            "pt-br",
        ) shouldBe false
        MangaAllReadingIdentity.accepts(
            ProviderBindingRef(mangaAllId, null, "mangadex:uuid-1"),
            "mangadex:chapter-1",
            "en",
        ) shouldBe false
        MangaAllReadingIdentity.accepts(
            ProviderBindingRef(mangaAllId, "fr", "mangadex:uuid-1"),
            "mangadex:chapter-1",
            "fr",
        ) shouldBe false
    }

    @Test
    fun `legacy providers are not retroactively restricted by MangaAll source rules`() {
        val standalone = ProviderBindingRef(legacyId, "en", "729pj")
        MangaAllReadingIdentity.sourceId(standalone) shouldBe null
        MangaAllReadingIdentity.accepts(standalone, "1234", null) shouldBe true
        MangaAllReadingIdentity.accepts(standalone, "1234", "en") shouldBe true
    }
}

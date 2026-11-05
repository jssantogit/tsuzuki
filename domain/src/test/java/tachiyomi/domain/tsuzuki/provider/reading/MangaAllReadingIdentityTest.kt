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
        // All three internal sources can return pages in development, but only
        // structurally valid IDs and attested chapter languages are readable.
        MangaAllReadingIdentity.accepts(dexPt, "mangadex:chapter-1", "pt-br") shouldBe false
        val dexWorkId = "11111111-2222-4333-8444-555555555555"
        val dexChapterId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val verifiedDex = ProviderBindingRef(mangaAllId, "pt-br", "mangadex:$dexWorkId")
        MangaAllReadingIdentity.accepts(verifiedDex, "mangadex:$dexChapterId", "pt-br") shouldBe true
        MangaAllReadingIdentity.accepts(verifiedDex, "mangadex:$dexChapterId", "en") shouldBe false
        MangaAllReadingIdentity.accepts(verifiedDex, "mangadex:$dexChapterId", null) shouldBe false
        MangaAllReadingIdentity.accepts(verifiedDex, "mangadot:$dexChapterId", "pt-br") shouldBe false
        MangaAllReadingIdentity.accepts(verifiedDex, "mangadex:invalid", "pt-br") shouldBe false
        MangaAllReadingIdentity.accepts(
            ProviderBindingRef(mangaAllId, "en", "mangadex:$dexWorkId"),
            "mangadex:$dexChapterId",
            "en",
        ) shouldBe true

        MangaAllReadingIdentity.accepts(dotPt, "mangadot:uploads:123", "pt-br") shouldBe true
        MangaAllReadingIdentity.accepts(dotPt, "mangadot:chapters:123", "pt-br") shouldBe true
        MangaAllReadingIdentity.accepts(
            ProviderBindingRef(mangaAllId, "en", "mangadot:317"),
            "mangadot:uploads:123",
            "en",
        ) shouldBe true
        MangaAllReadingIdentity.accepts(dotPt, "mangadot:uploads:123", "en") shouldBe false
        MangaAllReadingIdentity.accepts(dotPt, "mangadot:uploads:123", null) shouldBe false
        MangaAllReadingIdentity.accepts(dotPt, "mangadot:uploads:0", "pt-br") shouldBe false
        MangaAllReadingIdentity.accepts(dotPt, "mangadot:wrong:123", "pt-br") shouldBe false
        MangaAllReadingIdentity.accepts(dotPt, "mangadot:123", "pt-br") shouldBe false
        MangaAllReadingIdentity.accepts(dotPt, "mangafire:123", "pt-br") shouldBe false
        MangaAllReadingIdentity.sourceId(dexPt) shouldBe "mangadex"
        MangaAllReadingIdentity.sourceId(dotPt) shouldBe "mangadot"
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

// CI mirror: MangaDex, MangaDot and MangaFire Reader entrypoints in one package.

package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class ResolveMangaAllSelectedReadingOptionTest {
    private val provider = ProviderId("app.tsuzuki.mangaall")
    private val title = "title-1"
    private val refEn = ProviderBindingRef(provider, "en", "mangafire:work-one")
    private val refPt = ProviderBindingRef(provider, "pt-br", "mangadex:work-two")

    @Test
    fun `selected work resolves only to the exact already validated chapter option`() = runBlocking {
        val selections = fakeSelection(MangaAllSourceSelection(title, refEn, 10))
        val resolver = ResolveMangaAllSelectedReadingOption(selections)
        val fireOption = option(refEn, "mangafire:chapter-one")
        val other = option(refEn.copy(externalWorkId = "mangafire:other-work"), "mangafire:other-chapter")
        val pt = option(refPt, "mangadex:chapter-two")

        resolver.select(title, "chapter-1", "en", listOf(other, pt, fireOption)) shouldBe fireOption
        resolver.select(title, "chapter-other", "en", listOf(fireOption)) shouldBe null
        resolver.select(title, "chapter-1", "pt-br", listOf(pt)) shouldBe null
        resolver.select(title, "chapter-1", "en", listOf(other, pt)) shouldBe null
        resolver.select(title, "chapter-1", "en", listOf(pt)) shouldBe null
        resolver.select(title, "chapter-1", "fr", listOf(fireOption)) shouldBe null
    }

    @Test
    fun `same language MangaDex and MangaDot alternatives stay independently selectable`() = runBlocking {
        val dex = ProviderBindingRef(provider, "en", "mangadex:12345678-1234-1234-1234-123456789abc")
        val dot = ProviderBindingRef(provider, "en", "mangadot:123")
        val dexOption = option(dex, "mangadex:12345678-1234-1234-1234-123456789def")
        val dotOption = option(dot, "mangadot:chapters:456")
        val dexResolver = ResolveMangaAllSelectedReadingOption(
            fakeSelection(MangaAllSourceSelection(title, dex, 10)),
        )
        val dotResolver = ResolveMangaAllSelectedReadingOption(
            fakeSelection(MangaAllSourceSelection(title, dot, 10)),
        )
        dexResolver.select(title, "chapter-1", "en", listOf(dotOption, dexOption)) shouldBe dexOption
        dotResolver.select(title, "chapter-1", "en", listOf(dotOption, dexOption)) shouldBe dotOption
        dexResolver.select(title, "chapter-1", "en", listOf(dotOption)) shouldBe null
        dotResolver.select(title, "chapter-1", "en", listOf(dexOption)) shouldBe null

        val secondDot = option(dot, "mangadot:uploads:789")
        // Two releases on the same selected HID are still ambiguous; a
        // different internal source never rescues the explicit selection.
        dotResolver.select(title, "chapter-1", "en", listOf(dexOption, dotOption, secondDot)) shouldBe null
        dexResolver.select(title, "chapter-1", "en", listOf(dexOption, dotOption, secondDot)) shouldBe dexOption
        val rivalWork = option(dot.copy(externalWorkId = "mangadot:999"), "mangadot:chapters:999")
        val otherChapter = dotOption.copy(canonicalChapterId = "chapter-2")
        val wrongLanguage = option(dot.copy(facetId = "pt-br"), "mangadot:chapters:321")
        val wrongSource = option(dot, "mangafire:chapter")
        val all = listOf(dexOption, dotOption, secondDot, rivalWork, otherChapter, wrongLanguage, wrongSource)
        dotResolver.options(title, "chapter-1", "en", all) shouldBe listOf(dotOption, secondDot)
        dotResolver.options(title, "chapter-1", "pt-br", all) shouldBe emptyList()
        dotResolver.options("other-title", "chapter-1", "en", all) shouldBe emptyList()
        dexResolver.options(title, "chapter-1", "en", all) shouldBe listOf(dexOption)
        dotResolver.select(title, "chapter-1", "en", all) shouldBe null
    }

    @Test
    fun `duplicate chapters and forged source IDs fail closed`() = runBlocking {
        val resolver = ResolveMangaAllSelectedReadingOption(
            fakeSelection(MangaAllSourceSelection(title, refEn, 10)),
        )
        val first = option(refEn, "mangafire:first")
        val second = option(refEn, "mangafire:second")
        resolver.select(title, "chapter-1", "en", listOf(first, second)) shouldBe null
        resolver.select(
            title,
            "chapter-1",
            "en",
            listOf(option(refEn, "mangadot:wrong-source")),
        ) shouldBe null
        resolver.select(
            title,
            "chapter-1",
            "en",
            listOf(option(refEn.copy(facetId = "pt-br"), "mangafire:first")),
        ) shouldBe null
    }

    @Test
    fun `selection from another canonical title never applies to current title`() = runBlocking {
        val resolver = ResolveMangaAllSelectedReadingOption(
            fakeSelection(MangaAllSourceSelection(title, refEn, 10)),
        )
        resolver.select("other-title", "chapter-1", "en", listOf(option(refEn, "mangafire:first"))) shouldBe null
        resolver.select("", "chapter-1", "en", listOf(option(refEn, "mangafire:first"))) shouldBe null
    }

    @Test
    fun `explicit chapter release survives resolver reconstruction but cannot silently fall back`() = runBlocking {
        val saved = MangaAllSourceSelection(title, refEn, 10)
        val storage = object : MangaAllSourceSelectionRepository {
            private val choices = mutableMapOf<Pair<String, ProviderBindingRef>, String>()
            override suspend fun get(canonicalTitleId: String, language: String) =
                saved.takeIf { it.canonicalTitleId == canonicalTitleId && it.ref.facetId == language }
            override suspend fun select(choice: MangaAllSourceSelection) = error("must not change work")
            override suspend fun getRelease(
                canonicalTitleId: String,
                canonicalChapterId: String,
                ref: ProviderBindingRef,
            ): String? = if (canonicalTitleId == title) choices[canonicalChapterId to ref] else null
            override suspend fun selectRelease(
                canonicalTitleId: String,
                option: ProviderChapterReadingOption,
                at: Long,
            ): Boolean {
                choices[option.canonicalChapterId to option.bindingRef()] = option.providerChapterId
                return true
            }
        }
        val first = option(refEn, "mangafire:first")
        val second = option(refEn, "mangafire:second")
        val rival = option(refEn.copy(externalWorkId = "mangafire:other-work"), "mangafire:rival")
        val all = listOf(first, second, rival)
        val resolver = ResolveMangaAllSelectedReadingOption(storage)
        resolver.select(title, "chapter-1", "en", all) shouldBe null
        resolver.remember(title, rival, all, 20) shouldBe false
        resolver.remember(title, second, listOf(first), 20) shouldBe false
        resolver.remember(title, second, all, 20) shouldBe true

        val reopened = ResolveMangaAllSelectedReadingOption(storage)
        reopened.select(title, "chapter-1", "en", all) shouldBe second
        reopened.select(title, "chapter-1", "en", listOf(first, rival)) shouldBe null
        reopened.select(title, "chapter-2", "en", all) shouldBe null
        reopened.select("other-title", "chapter-1", "en", all) shouldBe null
        reopened.remember(title, first, all, 30) shouldBe true
        reopened.select(title, "chapter-1", "en", all) shouldBe first
    }

    private fun option(ref: ProviderBindingRef, chapterId: String) = ProviderChapterReadingOption(
        canonicalChapterId = "chapter-1",
        bindingId = "verified-source",
        providerId = ref.providerId,
        facetId = ref.facetId,
        externalWorkId = ref.externalWorkId,
        providerChapterId = chapterId,
    )

    private fun fakeSelection(choice: MangaAllSourceSelection) = object : MangaAllSourceSelectionRepository {
        override suspend fun get(canonicalTitleId: String, language: String) =
            choice.takeIf {
                it.canonicalTitleId == canonicalTitleId &&
                    it.ref.facetId == language
            }
        override suspend fun select(choice: MangaAllSourceSelection) = error("must not write")
    }
}

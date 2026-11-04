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

        resolver.select(title, "en", listOf(other, pt, fireOption)) shouldBe fireOption
        resolver.select(title, "pt-br", listOf(pt)) shouldBe null
        resolver.select(title, "en", listOf(other, pt)) shouldBe null
        resolver.select(title, "en", listOf(pt)) shouldBe null
        resolver.select(title, "fr", listOf(fireOption)) shouldBe null
    }

    @Test
    fun `duplicate chapters and forged source IDs fail closed`() = runBlocking {
        val resolver = ResolveMangaAllSelectedReadingOption(
            fakeSelection(MangaAllSourceSelection(title, refEn, 10)),
        )
        val first = option(refEn, "mangafire:first")
        val second = option(refEn, "mangafire:second")
        resolver.select(title, "en", listOf(first, second)) shouldBe null
        resolver.select(
            title,
            "en",
            listOf(option(refEn, "mangadot:wrong-source")),
        ) shouldBe null
        resolver.select(
            title,
            "en",
            listOf(option(refEn.copy(facetId = "pt-br"), "mangafire:first")),
        ) shouldBe null
    }

    @Test
    fun `selection from another canonical title never applies to current title`() = runBlocking {
        val resolver = ResolveMangaAllSelectedReadingOption(
            fakeSelection(MangaAllSourceSelection(title, refEn, 10)),
        )
        resolver.select("other-title", "en", listOf(option(refEn, "mangafire:first"))) shouldBe null
        resolver.select("", "en", listOf(option(refEn, "mangafire:first"))) shouldBe null
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

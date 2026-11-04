package eu.kanade.presentation.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate

class ProviderEditionCandidatePresentationTest {
    private fun candidate(
        language: String? = "en",
        chapterCount: Int? = null,
        observedChapterCount: Int? = null,
        hasMore: Boolean? = null,
        authors: List<String> = emptyList(),
    ) = ProviderEditionCandidate(
        ref = ProviderBindingRef(ProviderId("app.tsuzuki.mangafire"), "en", "jjrxn"),
        providerName = "MangaFire",
        title = "Bleach",
        url = "https://mangafire.to/title/jjrxn-bleach",
        query = "Bleach",
        providerVersionCode = 13,
        configurationFingerprint = "fingerprint",
        language = language,
        chapterCount = chapterCount,
        observedChapterCount = observedChapterCount,
        coverageHasMore = hasMore,
        authors = authors,
    )

    @Test
    fun `reported count and author are displayed without claiming completeness`() {
        editionCandidateMetadata(candidate(chapterCount = 742, authors = listOf("Tite Kubo"))) shouldBe listOf(
            "Capítulos: 742 informados pela fonte",
            "Idioma: Inglês",
            "Autor: Tite Kubo",
        )
    }

    @Test
    fun `partial first page is identified as a lower bound rather than a total`() {
        editionCandidateMetadata(
            candidate(observedChapterCount = 200, hasMore = true),
        ) shouldBe listOf(
            "Capítulos: ao menos 200 observados (parcial)",
            "Idioma: Inglês",
            "Autor: não informado",
        )
    }

    @Test
    fun `source contradictions do not display an unreliable total`() {
        editionCandidateMetadata(
            candidate(chapterCount = 90, observedChapterCount = 200, hasMore = true),
        ).first() shouldBe "Capítulos: 200 observados (contagem informada divergente)"
    }

    @Test
    fun `MangaAll picker distinguishes source and does not invent language`() {
        val mangaAllCandidate = candidate(language = null).copy(
            ref = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "pt-br", "mangadex:uuid-123"),
            providerName = "MangaAll",
        )
        editionCandidateMetadata(mangaAllCandidate) shouldBe listOf(
            "Fonte: MangaDex",
            "Capítulos: não informado",
            "Idioma: não informado",
            "Autor: não informado",
        )
        editionCandidateMetadata(
            mangaAllCandidate.copy(
                ref = mangaAllCandidate.ref.copy(externalWorkId = "mangadot:317"),
                language = "pt-br",
            ),
        ) shouldBe listOf(
            "Fonte: MangaDot",
            "Capítulos: não informado",
            "Idioma: Português (Brasil)",
            "Autor: não informado",
        )
        editionCandidateMetadata(
            mangaAllCandidate.copy(
                ref = mangaAllCandidate.ref.copy(externalWorkId = "mangafire:729pj"),
                language = "en",
            ),
        ).first() shouldBe "Fonte: MangaFire"
    }

    @Test
    fun `missing metadata stays visibly unknown and locales use readable names`() {
        editionCandidateMetadata(candidate(language = null)) shouldBe listOf(
            "Capítulos: não informado",
            "Idioma: não informado",
            "Autor: não informado",
        )
        editionCandidateMetadata(
            candidate(language = "pt-BR", authors = listOf("Autor A", "Autor B", "Autor C")),
        ) shouldBe listOf(
            "Capítulos: não informado",
            "Idioma: Português (Brasil)",
            "Autores: Autor A, Autor B e mais 1",
        )
    }
}

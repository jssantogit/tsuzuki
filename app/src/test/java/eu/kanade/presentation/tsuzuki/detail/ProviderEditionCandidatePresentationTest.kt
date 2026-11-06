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
        rangeStart: Int? = null,
        rangeEnd: Int? = null,
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
        coverageMinMilli = rangeStart,
        coverageMaxMilli = rangeEnd,
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
            "Capítulos: ao menos 200 observados (amostra parcial)",
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
            "Capítulos: não verificados; selecione para consultar",
            "Idioma solicitado: Português (Brasil)",
        )
        editionCandidateMetadata(
            mangaAllCandidate.copy(
                ref = mangaAllCandidate.ref.copy(externalWorkId = "mangadot:317"),
                language = "pt-br",
            ),
        ) shouldBe listOf(
            "Fonte: MangaDot",
            "Capítulos: não verificados; selecione para consultar",
            "Idioma solicitado: Português (Brasil)",
            "Idioma: Português (Brasil)",
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
            "Capítulos: não verificados; selecione para consultar",
            "Idioma: não informado",
            "Autor: não informado",
        )
        editionCandidateMetadata(
            candidate(language = "pt-BR", authors = listOf("Autor A", "Autor B", "Autor C")),
        ) shouldBe listOf(
            "Capítulos: não verificados; selecione para consultar",
            "Idioma: Português (Brasil)",
            "Autores: Autor A, Autor B e mais 1",
        )
    }

    @Test
    fun `complete and partial chapter inspection are clearly distinct and never canonical`() {
        editionChapterInspectionMetadata(
            tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionChapterInspection(
                observedRecords = 319,
                complete = true,
                failed = false,
                minChapterMilli = 1000,
                maxChapterMilli = 319500,
            ),
        ) shouldBe listOf(
            "Registros de capítulos: 319 (consulta completa da fonte)",
            "Intervalo observado: caps. 1–319.5",
        )
        editionChapterInspectionMetadata(
            tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionChapterInspection(
                observedRecords = 2,
                complete = false,
                failed = true,
                minChapterMilli = 67000,
                maxChapterMilli = 67100,
            ),
        ) shouldBe listOf(
            "Registros de capítulos: ao menos 2 (consulta parcial)",
            "Intervalo observado: caps. 67–67.1",
        )
    }

    @Test
    fun `MangaAll edition preview distinguishes bounded coverage and chapter range`() {
        val partial = candidate(
            observedChapterCount = 2,
            hasMore = true,
            rangeStart = 1000,
            rangeEnd = 2000,
        ).copy(
            ref = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "pt-br", "mangadex:uuid-123"),
            providerName = "MangaAll",
        )
        editionCandidateMetadata(partial) shouldBe listOf(
            "Fonte: MangaDex",
            "Capítulos: ao menos 2 observados (amostra parcial)",
            "Intervalo observado: caps. 1–2",
            "Idioma solicitado: Português (Brasil)",
        )
        editionCandidateMetadata(partial.copy(
            observedChapterCount = null,
            coverageHasMore = null,
            coverageMinMilli = null,
            coverageMaxMilli = null,
        )).any { it.startsWith("Capítulos:") } shouldBe true
    }
}

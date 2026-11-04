package eu.kanade.tachiyomi.ui.tsuzuki.detail

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelection
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionRepository
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionSearchResult
import tachiyomi.domain.tsuzuki.provider.reading.SelectMangaAllSource
import tachiyomi.domain.tsuzuki.provider.reading.SelectProviderReadingEdition

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderEditionPickerScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val candidate = ProviderEditionCandidate(
        ref = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "en", "mangafire:work-1"),
        providerName = "MangaAll",
        title = "Chainsaw Man",
        url = null,
        query = "Chainsaw Man",
        providerVersionCode = 1,
        configurationFingerprint = "signed",
        language = "en",
    )

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun model(
        edition: SelectProviderReadingEdition,
        preference: SelectMangaAllSource,
        restored: MangaAllSourceSelection? = null,
    ): ProviderEditionPickerScreenModel {
        val repository = mockk<MangaAllSourceSelectionRepository>()
        coEvery { repository.getLatest(any()) } returns restored
        return ProviderEditionPickerScreenModel(edition, preference, repository)
    }

    @Test
    fun `a saved MangaAll translation restores its selected language after reentering the title`() =
        runTest(dispatcher) {
            val selected = MangaAllSourceSelection("title-1", candidate.ref, 500)
            val viewModel = model(mockk(), mockk(), restored = selected)
            viewModel.start("title-1")
            advanceUntilIdle()
            viewModel.state.value.activeRequestedLanguage shouldBe "en"
            viewModel.dismiss()
            viewModel.state.value.activeRequestedLanguage shouldBe "en"
            viewModel.start("title-2")
            advanceUntilIdle()
            viewModel.state.value.activeRequestedLanguage shouldBe null
        }

    @Test
    fun `opening Reader during restoration awaits saved explicit language rather than falling back`() =
        runTest(dispatcher) {
            val repository = mockk<MangaAllSourceSelectionRepository>()
            val pending = CompletableDeferred<MangaAllSourceSelection?>()
            coEvery { repository.getLatest("title-1") } coAnswers { pending.await() }
            val model = ProviderEditionPickerScreenModel(mockk(), mockk(), repository)
            model.start("title-1")
            val launch = async { model.requestedLanguageForReader("title-1") }
            runCurrent()
            launch.isCompleted shouldBe false
            pending.complete(MangaAllSourceSelection("title-1", candidate.ref, 50))
            advanceUntilIdle()
            launch.await() shouldBe "en"
        }

    @Test
    fun `explicit MangaAll source choice updates state without confirming or replacing an edition`() =
        runTest(dispatcher) {
            val edition = mockk<SelectProviderReadingEdition>()
            val preference = mockk<SelectMangaAllSource>()
            coEvery { edition.search("title-1", "Chainsaw Man") } returns ProviderEditionSearchResult(
                candidates = listOf(candidate),
            )
            coEvery {
                preference.select("title-1", candidate.ref, any())
            } returns MangaAllSourceSelectionResult.SELECTED

            val model = model(edition, preference)
            model.start("title-1")
            model.open("title-1", "Chainsaw Man")
            advanceUntilIdle()
            model.selectMangaAllSource(candidate)
            advanceUntilIdle()

            model.state.value.sourceSelection shouldBe MangaAllSourceSelectionResult.SELECTED
            model.state.value.activeRequestedLanguage shouldBe "en"
            model.dismiss()
            model.state.value.activeRequestedLanguage shouldBe "en"
            model.state.value.open shouldBe true
            model.state.value.confirmation shouldBe null
            model.state.value.revision shouldBe 1
            coVerify(exactly = 1) { preference.select("title-1", candidate.ref, any()) }
            coVerify(exactly = 0) { edition.confirm(any(), any()) }
        }

    @Test
    fun `source choice cannot be forged from an old or non MangaAll candidate`() = runTest(dispatcher) {
        val edition = mockk<SelectProviderReadingEdition>()
        val preference = mockk<SelectMangaAllSource>()
        coEvery { edition.search("title-1", "Chainsaw Man") } returns ProviderEditionSearchResult(
            candidates = listOf(candidate),
        )
        val model = model(edition, preference)
        model.start("title-1")
        model.open("title-1", "Chainsaw Man")
        advanceUntilIdle()
        model.selectMangaAllSource(candidate.copy(ref = candidate.ref.copy(externalWorkId = "mangafire:other")))
        model.selectMangaAllSource(
            candidate.copy(ref = candidate.ref.copy(providerId = ProviderId("app.tsuzuki.mangafire"))),
        )
        model.start("title-2")
        model.selectMangaAllSource(candidate)
        coVerify(exactly = 0) { preference.select(any(), any(), any()) }
    }

    @Test
    fun `failed switch clears previously active Reader language instead of reopening wrong translation`() =
        runTest(dispatcher) {
            val pt = candidate.copy(ref = candidate.ref.copy(facetId = "pt-br"))
            val edition = mockk<SelectProviderReadingEdition>()
            val preference = mockk<SelectMangaAllSource>()
            coEvery { edition.search("title-1", "Chainsaw Man") } returns ProviderEditionSearchResult(
                candidates = listOf(candidate, pt),
            )
            coEvery { preference.select("title-1", candidate.ref, any()) } returns
                MangaAllSourceSelectionResult.SELECTED
            coEvery { preference.select("title-1", pt.ref, any()) } returns
                MangaAllSourceSelectionResult.NOT_ASSOCIATED

            val model = model(edition, preference)
            model.start("title-1")
            model.open("title-1", "Chainsaw Man")
            advanceUntilIdle()
            model.selectMangaAllSource(candidate)
            advanceUntilIdle()
            model.state.value.activeRequestedLanguage shouldBe "en"
            model.selectMangaAllSource(pt)
            advanceUntilIdle()
            model.state.value.sourceSelection shouldBe MangaAllSourceSelectionResult.NOT_ASSOCIATED
            model.state.value.activeRequestedLanguage shouldBe null
        }

    @Test
    fun `failed or unassociated preference cannot claim it was saved`() = runTest(dispatcher) {
        val edition = mockk<SelectProviderReadingEdition>()
        val preference = mockk<SelectMangaAllSource>()
        coEvery { edition.search("title-1", "Chainsaw Man") } returns ProviderEditionSearchResult(
            candidates = listOf(candidate),
        )
        coEvery {
            preference.select("title-1", candidate.ref, any())
        } returns MangaAllSourceSelectionResult.NOT_ASSOCIATED
        val model = model(edition, preference)
        model.start("title-1")
        model.open("title-1", "Chainsaw Man")
        advanceUntilIdle()
        model.selectMangaAllSource(candidate)
        advanceUntilIdle()
        model.state.value.sourceSelection shouldBe MangaAllSourceSelectionResult.NOT_ASSOCIATED
        model.state.value.activeRequestedLanguage shouldBe null
        model.state.value.revision shouldBe 0
        coVerify(exactly = 0) { edition.confirm(any(), any()) }
    }
}

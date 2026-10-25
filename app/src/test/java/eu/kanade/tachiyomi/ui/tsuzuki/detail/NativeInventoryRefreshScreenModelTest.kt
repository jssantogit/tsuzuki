package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexInventoryProducer
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedCandidate
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedWorkLinker
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaUpdatesInventoryProducer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshIndependentInventory
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class NativeInventoryRefreshScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val uuid = "12345678-1234-1234-1234-123456789abc"
    private val candidate = MangaDexVerifiedCandidate(uuid, "Exact Manga", "mangaupdates", "123")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a preview alone does not save an identity and explicit confirmation advances the read revision`() =
        runTest(dispatcher) {
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            coEvery { linker.preview("title", uuid) } returns Result.success(candidate)
            coEvery { linker.confirm("title", candidate) } returns Result.success(Unit)
            val model = model(linker)
            model.start("title")

            model.previewMangaDexLink("title", uuid)
            advanceUntilIdle()
            model.state.value.linkCandidate shouldBe candidate
            model.state.value.revision shouldBe 0
            coVerify(exactly = 0) { linker.confirm(any(), any()) }

            model.confirmMangaDexLink("title")
            advanceUntilIdle()
            model.state.value.linkCompleted shouldBe true
            model.state.value.linkCandidate shouldBe null
            model.state.value.revision shouldBe 1
            coVerify(exactly = 1) { linker.confirm("title", candidate) }
        }

    @Test
    fun `switching canonical title discards delayed MangaDex preview and prevents an old confirmation`() =
        runTest(dispatcher) {
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            val response = CompletableDeferred<Result<MangaDexVerifiedCandidate>>()
            coEvery { linker.preview("title", uuid) } coAnswers { response.await() }
            val model = model(linker)
            model.start("title")
            model.previewMangaDexLink("title", uuid)
            runCurrent()

            model.start("different-title")
            response.complete(Result.success(candidate))
            advanceUntilIdle()

            model.state.value.linkCandidate shouldBe null
            model.confirmMangaDexLink("title") shouldBe null
            coVerify(exactly = 0) { linker.confirm(any(), any()) }
        }

    @Test
    fun `failed identity confirmation preserves the candidate but does not fake a successful link`() =
        runTest(dispatcher) {
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            coEvery { linker.preview("title", uuid) } returns Result.success(candidate)
            coEvery { linker.confirm("title", candidate) } returns
                Result.failure(IllegalStateException("crosswalk revoked"))
            val model = model(linker)
            model.start("title")
            model.previewMangaDexLink("title", uuid)
            advanceUntilIdle()

            model.confirmMangaDexLink("title")
            advanceUntilIdle()

            model.state.value.linkFailed shouldBe true
            model.state.value.linkCompleted shouldBe false
            model.state.value.linkCandidate shouldBe candidate
            model.state.value.revision shouldBe 0
        }

    private fun model(linker: MangaDexVerifiedWorkLinker) = NativeInventoryRefreshScreenModel(
        refreshInventory = mockk<RefreshIndependentInventory>(),
        mangaUpdates = mockk<MangaUpdatesInventoryProducer>(),
        mangaDex = mockk<MangaDexInventoryProducer>(),
        mangaDexWorkLinker = linker,
        integrationSettings = mockk<IntegrationSettingsRepository>(),
    )
}

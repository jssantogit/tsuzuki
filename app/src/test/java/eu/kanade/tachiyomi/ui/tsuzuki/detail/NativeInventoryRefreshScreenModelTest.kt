package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedCandidate
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedWorkLinker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import tachiyomi.domain.tsuzuki.chapter.inventory.IndependentInventoryProducer
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshResult
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshStop
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshIndependentInventory
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

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


    @Test
    fun `manual inventory refresh is rejected when the integration has not opted in`() = runTest(dispatcher) {
        val refresh = mockk<RefreshIndependentInventory>()
        val registry = mockk<IntegrationRegistry>()
        every { registry.inventoryProducers() } returns emptyList()
        val model = model(mockk(), refresh, registry)
        model.start("title")

        model.refresh("title", "mangadex") shouldBe null
        model.state.value.lastStop shouldBe InventoryRefreshStop.DISABLED
        model.state.value.refreshing shouldBe false
        coVerify(exactly = 0) { refresh.execute(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `registry revocation invalidates an in flight inventory permission check`() = runTest(dispatcher) {
        val producer = mockk<IndependentInventoryProducer>()
        every { producer.producerId } returns "mangadex"
        val registry = mockk<IntegrationRegistry>()
        var enabled = true
        var fingerprint = "original"
        every { registry.inventoryProducers() } answers { if (enabled) listOf(producer) else emptyList() }
        every { registry.configurationFingerprint() } answers { fingerprint }
        val refresh = mockk<RefreshIndependentInventory>()
        var revalidate: (suspend () -> Boolean)? = null
        coEvery { refresh.execute(any(), any(), any(), any(), any()) } coAnswers {
            revalidate = thirdArg()
            InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 1, 0)
        }
        val model = model(mockk(), refresh, registry)
        model.start("title")
        model.refresh("title", "mangadex")
        advanceUntilIdle()

        revalidate?.invoke() shouldBe true
        enabled = false
        revalidate?.invoke() shouldBe false
        enabled = true
        fingerprint = "changed"
        revalidate?.invoke() shouldBe false
        model.state.value.lastStop shouldBe InventoryRefreshStop.EXHAUSTED
    }

    private fun model(
        linker: MangaDexVerifiedWorkLinker,
        refresh: RefreshIndependentInventory = mockk(),
        registry: IntegrationRegistry = mockk(),
    ) = NativeInventoryRefreshScreenModel(
        refreshInventory = refresh,
        mangaDexWorkLinker = linker,
        integrationRegistry = registry,
    )
}

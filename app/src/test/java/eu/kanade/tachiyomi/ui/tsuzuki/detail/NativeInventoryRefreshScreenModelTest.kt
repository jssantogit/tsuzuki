package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedCandidate
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedWorkLinker
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaUpdatesVerifiedCandidate
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaUpdatesVerifiedWorkLinker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchFailureReason
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshStop
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshIndependentInventory
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshProviderTorrentInventory
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
            coEvery { linker.preview("title", uuid, any()) } returns Result.success(candidate)
            coEvery { linker.confirm("title", candidate, any()) } returns Result.success(Unit)
            val model = model(linker)
            model.start("title")

            model.previewMangaDexLink("title", uuid)
            advanceUntilIdle()
            model.state.value.linkCandidate shouldBe candidate
            model.state.value.revision shouldBe 0
            coVerify(exactly = 0) { linker.confirm(any(), any(), any()) }

            model.confirmMangaDexLink("title")
            advanceUntilIdle()
            model.state.value.linkCompleted shouldBe true
            model.state.value.linkCandidate shouldBe null
            model.state.value.revision shouldBe 1
            coVerify(exactly = 1) { linker.confirm("title", candidate, any()) }
        }

    @Test
    fun `switching canonical title discards delayed MangaDex preview and prevents an old confirmation`() =
        runTest(dispatcher) {
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            val response = CompletableDeferred<Result<MangaDexVerifiedCandidate>>()
            coEvery { linker.preview("title", uuid, any()) } coAnswers { response.await() }
            val model = model(linker)
            model.start("title")
            model.previewMangaDexLink("title", uuid)
            runCurrent()

            model.start("different-title")
            response.complete(Result.success(candidate))
            advanceUntilIdle()

            model.state.value.linkCandidate shouldBe null
            model.confirmMangaDexLink("title") shouldBe null
            coVerify(exactly = 0) { linker.confirm(any(), any(), any()) }
        }

    @Test
    fun `failed identity confirmation preserves the candidate but does not fake a successful link`() =
        runTest(dispatcher) {
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            coEvery { linker.preview("title", uuid, any()) } returns Result.success(candidate)
            coEvery { linker.confirm("title", candidate, any()) } returns
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

    @Test
    fun `MangaDex work preview requires the inventory integration to be enabled`() = runTest(dispatcher) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        val registry = mockk<IntegrationRegistry>()
        every { registry.inventoryProducers() } returns emptyList()
        every { registry.configurationFingerprint() } returns "disabled"
        val model = model(linker, registry = registry)
        model.start("title")

        model.previewMangaDexLink("title", uuid) shouldBe null
        model.state.value.linkFailed shouldBe true
        coVerify(exactly = 0) { linker.preview(any(), any(), any()) }
    }

    @Test
    fun `revoking the inventory integration blocks MangaDex work confirmation`() = runTest(dispatcher) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.preview("title", uuid, any()) } returns Result.success(candidate)
        val registry = activeInventoryRegistry()
        val producer = allowedMangaDexProducer()
        var enabled = true
        every { registry.inventoryProducers() } answers {
            if (enabled) listOf(producer) else emptyList()
        }
        val model = model(linker, registry = registry)
        model.start("title")

        model.previewMangaDexLink("title", uuid)
        advanceUntilIdle()
        model.state.value.linkCandidate shouldBe candidate

        enabled = false
        model.confirmMangaDexLink("title") shouldBe null
        coVerify(exactly = 0) { linker.confirm(any(), any(), any()) }
    }

    @Test
    fun `revoking the inventory integration while MangaDex preview is in flight discards the response`() = runTest(
        dispatcher,
    ) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        val response = CompletableDeferred<Result<MangaDexVerifiedCandidate>>()
        coEvery { linker.preview("title", uuid, any()) } coAnswers { response.await() }
        val registry = activeInventoryRegistry()
        val producer = allowedMangaDexProducer()
        var enabled = true
        every { registry.inventoryProducers() } answers {
            if (enabled) listOf(producer) else emptyList()
        }
        val model = model(linker, registry = registry)
        model.start("title")

        model.previewMangaDexLink("title", uuid)
        runCurrent()
        enabled = false
        response.complete(Result.success(candidate))
        advanceUntilIdle()

        model.state.value.linkCandidate shouldBe null
        model.state.value.linkFailed shouldBe true
        model.state.value.linkCompleted shouldBe false
    }

    @Test
    fun `changing inventory fingerprint while MangaDex preview is in flight discards the response`() = runTest(
        dispatcher,
    ) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        val response = CompletableDeferred<Result<MangaDexVerifiedCandidate>>()
        coEvery { linker.preview("title", uuid, any()) } coAnswers { response.await() }
        val registry = activeInventoryRegistry()
        var fingerprint = "original"
        every { registry.configurationFingerprint() } answers { fingerprint }
        val model = model(linker, registry = registry)
        model.start("title")

        model.previewMangaDexLink("title", uuid)
        runCurrent()
        fingerprint = "changed"
        response.complete(Result.success(candidate))
        advanceUntilIdle()

        model.state.value.linkCandidate shouldBe null
        model.state.value.linkFailed shouldBe true
    }

    @Test
    fun `revoking the inventory integration while MangaDex confirmation is in flight discards the success`() = runTest(
        dispatcher,
    ) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.preview("title", uuid, any()) } returns Result.success(candidate)
        val response = CompletableDeferred<Result<Unit>>()
        coEvery { linker.confirm("title", candidate, any()) } coAnswers { response.await() }
        val registry = activeInventoryRegistry()
        val producer = allowedMangaDexProducer()
        var enabled = true
        every { registry.inventoryProducers() } answers {
            if (enabled) listOf(producer) else emptyList()
        }
        val model = model(linker, registry = registry)
        model.start("title")
        model.previewMangaDexLink("title", uuid)
        advanceUntilIdle()

        model.confirmMangaDexLink("title")
        runCurrent()
        enabled = false
        response.complete(Result.success(Unit))
        advanceUntilIdle()

        model.state.value.linkCandidate shouldBe candidate
        model.state.value.linkCompleted shouldBe false
        model.state.value.revision shouldBe 0
    }

    @Test
    fun `changing inventory fingerprint after preview prevents MangaDex confirmation`() = runTest(
        dispatcher,
    ) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.preview("title", uuid, any()) } returns Result.success(candidate)
        val registry = activeInventoryRegistry()
        var fingerprint = "original"
        every { registry.configurationFingerprint() } answers { fingerprint }
        val model = model(linker, registry = registry)
        model.start("title")
        model.previewMangaDexLink("title", uuid)
        advanceUntilIdle()

        fingerprint = "changed"
        model.confirmMangaDexLink("title") shouldBe null

        model.state.value.linkCandidate shouldBe null
        model.state.value.linkFailed shouldBe true
        coVerify(exactly = 0) { linker.confirm(any(), any(), any()) }
    }

    @Test
    fun `cancelling an in flight MangaDex preview discards its late response`() = runTest(
        dispatcher,
    ) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        val response = CompletableDeferred<Result<MangaDexVerifiedCandidate>>()
        coEvery { linker.preview("title", uuid, any()) } coAnswers { response.await() }
        val model = model(linker)
        model.start("title")

        model.previewMangaDexLink("title", uuid)
        runCurrent()
        model.cancel()
        response.complete(Result.success(candidate))
        advanceUntilIdle()

        model.state.value.linkCandidate shouldBe null
        model.state.value.linkCompleted shouldBe false
        model.state.value.linking shouldBe false
    }

    @Test
    fun `cancelling after a completed MangaDex preview blocks confirmation`() = runTest(dispatcher) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.preview("title", uuid, any()) } returns Result.success(candidate)
        coEvery { linker.confirm("title", candidate, any()) } returns Result.success(Unit)
        val model = model(linker)
        model.start("title")
        model.previewMangaDexLink("title", uuid)
        advanceUntilIdle()

        model.cancel()

        model.state.value.linkCandidate shouldBe null
        model.confirmMangaDexLink("title") shouldBe null
        coVerify(exactly = 0) { linker.confirm(any(), any(), any()) }
    }

    @Test
    fun `cancellation invalidates an in flight MangaDex confirmation permission`() = runTest(
        dispatcher,
    ) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.preview("title", uuid, any()) } returns Result.success(candidate)
        val response = CompletableDeferred<Result<Unit>>()
        var isEnabled: (() -> Boolean)? = null
        coEvery { linker.confirm("title", candidate, any()) } coAnswers {
            isEnabled = thirdArg()
            response.await()
        }
        val model = model(linker)
        model.start("title")
        model.previewMangaDexLink("title", uuid)
        advanceUntilIdle()

        model.confirmMangaDexLink("title")
        runCurrent()
        isEnabled?.invoke() shouldBe true

        model.cancel()
        isEnabled?.invoke() shouldBe false
        response.complete(Result.success(Unit))
        advanceUntilIdle()

        model.state.value.linkCandidate shouldBe null
        model.state.value.linkCompleted shouldBe false
        model.state.value.revision shouldBe 0
    }

    @Test
    fun `switching away and back invalidates an older MangaDex permission callback`() = runTest(
        dispatcher,
    ) {
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        val response = CompletableDeferred<Result<MangaDexVerifiedCandidate>>()
        var isEnabled: (() -> Boolean)? = null
        coEvery { linker.preview("title", uuid, any()) } coAnswers {
            isEnabled = thirdArg()
            response.await()
        }
        val model = model(linker)
        model.start("title")
        model.previewMangaDexLink("title", uuid)
        runCurrent()
        isEnabled?.invoke() shouldBe true

        model.start("different-title")
        model.start("title")

        isEnabled?.invoke() shouldBe false
        response.complete(Result.success(candidate))
        advanceUntilIdle()
        model.state.value.linkCandidate shouldBe null
    }

    @Test
    fun `automatic source failure carries a safe failure reason to the detail screen`() = runTest(dispatcher) {
        val registry = activeInventoryRegistry()
        coEvery { registry.awaitReady() } returns Unit
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.discoverUnique("title", any()) } returns Result.success(null)
        val refresh = mockk<RefreshIndependentInventory>()
        coEvery { refresh.execute(any(), any(), any(), any(), any()) } returns
            InventoryRefreshResult(
                InventoryRefreshStop.FAILED,
                1,
                0,
                InventoryFetchFailureReason.RATE_LIMITED,
            )
        val model = model(linker, refresh, registry)
        model.start("title")

        model.discoverAndRefreshAutomatically("title")
        advanceUntilIdle()

        model.state.value.refreshStops["mangadex"] shouldBe InventoryRefreshStop.FAILED
        model.state.value.refreshFailureReasons["mangadex"] shouldBe InventoryFetchFailureReason.RATE_LIMITED
        model.state.value.revision shouldBe 1
        model.start("another-title")
        model.state.value.refreshFailureReasons shouldBe emptyMap()
    }

    @Test
    fun `automatic opt in flow discovers and confirms exact MangaDex identity before bounded release refresh`() =
        runTest(dispatcher) {
            val registry = activeInventoryRegistry()
            coEvery { registry.awaitReady() } returns Unit
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            coEvery { linker.discoverUnique("title", any()) } returns Result.success(candidate)
            coEvery { linker.confirm("title", candidate, any()) } returns Result.success(Unit)
            val refresh = mockk<RefreshIndependentInventory>()
            coEvery { refresh.execute(any(), any(), any(), any(), any()) } returns
                InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 1, 15)
            val model = model(linker, refresh, registry)
            model.start("title")

            model.discoverAndRefreshAutomatically("title")
            advanceUntilIdle()

            model.state.value.revision shouldBe 1
            model.state.value.lastStop shouldBe InventoryRefreshStop.EXHAUSTED
            coVerify(exactly = 1) { linker.discoverUnique("title", any()) }
            coVerify(exactly = 1) { linker.confirm("title", candidate, any()) }
            coVerify(exactly = 1) { refresh.execute("title", any(), any(), any(), any()) }
        }

    @Test
    fun `automatic opt in flow does not issue metadata requests when inventory integrations are disabled`() =
        runTest(dispatcher) {
            val registry = mockk<IntegrationRegistry>()
            coEvery { registry.awaitReady() } returns Unit
            every { registry.inventoryProducers() } returns emptyList()
            every { registry.configurationFingerprint() } returns "empty"
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            val refresh = mockk<RefreshIndependentInventory>()
            val model = model(linker, refresh, registry)
            model.start("title")

            model.discoverAndRefreshAutomatically("title")
            advanceUntilIdle()

            model.state.value.revision shouldBe 0
            coVerify(exactly = 0) { linker.discoverUnique(any(), any()) }
            coVerify(exactly = 0) { refresh.execute(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `automatic opt-in resolves MangaUpdates work via verified MangaDex and then fetches release evidence`() =
        runTest(dispatcher) {
            val md = allowedMangaDexProducer()
            val mu = mockk<IndependentInventoryProducer>()
            every { mu.producerId } returns "mangaupdates"
            val registry = mockk<IntegrationRegistry>()
            coEvery { registry.awaitReady() } returns Unit
            every { registry.inventoryProducers() } returns listOf(md, mu)
            every { registry.configurationFingerprint() } returns "current"
            val mdLink = mockk<MangaDexVerifiedWorkLinker>()
            coEvery { mdLink.discoverUnique("title", any()) } returns Result.success(null)
            val muLink = mockk<MangaUpdatesVerifiedWorkLinker>()
            val muCandidate = MangaUpdatesVerifiedCandidate("8675309", "Exact Manga", uuid)
            coEvery { muLink.discoverUnique("title", any()) } returns Result.success(muCandidate)
            coEvery { muLink.confirm("title", muCandidate, any()) } returns Result.success(Unit)
            val refresh = mockk<RefreshIndependentInventory>()
            coEvery { refresh.execute(any(), any(), any(), any(), any()) } returns
                InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 1, 5)
            val model = NativeInventoryRefreshScreenModel(refresh, mdLink, muLink, registry, disabledTorrentRefresh())
            model.start("title")

            model.discoverAndRefreshAutomatically("title")
            advanceUntilIdle()

            model.state.value.revision shouldBe 1
            coVerify(exactly = 1) { muLink.discoverUnique("title", any()) }
            coVerify(exactly = 1) { muLink.confirm("title", muCandidate, any()) }
            coVerify(exactly = 1) { refresh.execute("title", mu, any(), any(), any()) }
        }

    @Test
    fun `revoking an automatic inventory producer clears loading without publishing stale results`() =
        runTest(dispatcher) {
            val md = allowedMangaDexProducer()
            var enabled = true
            val registry = mockk<IntegrationRegistry>()
            coEvery { registry.awaitReady() } returns Unit
            every { registry.configurationFingerprint() } returns "stable"
            every { registry.inventoryProducers() } answers {
                if (enabled) listOf(md) else emptyList()
            }
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            coEvery { linker.discoverUnique("title", any()) } returns Result.success(null)
            val refresh = mockk<RefreshIndependentInventory>()
            val response = CompletableDeferred<InventoryRefreshResult>()
            coEvery { refresh.execute(any(), any(), any(), any(), any()) } coAnswers { response.await() }
            val model = model(linker, refresh, registry)
            model.start("title")
            model.discoverAndRefreshAutomatically("title")
            runCurrent()

            model.state.value.refreshing shouldBe true
            enabled = false
            response.complete(InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 1, 2))
            advanceUntilIdle()

            model.state.value.refreshing shouldBe false
            model.state.value.revision shouldBe 0
        }

    @Test
    fun `automatic discovery exposes per-source progress before metadata lookup finishes`() = runTest(dispatcher) {
        val registry = activeInventoryRegistry()
        coEvery { registry.awaitReady() } returns Unit
        val candidateResponse = CompletableDeferred<Result<MangaDexVerifiedCandidate?>>()
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.discoverUnique("title", any()) } coAnswers { candidateResponse.await() }
        val refresh = mockk<RefreshIndependentInventory>()
        coEvery { refresh.execute(any(), any(), any(), any(), any()) } returns
            InventoryRefreshResult(InventoryRefreshStop.NO_VERIFIED_WORK, 0, 0)
        val model = model(linker, refresh, registry)
        model.start("title")

        model.discoverAndRefreshAutomatically("title")
        runCurrent()

        model.state.value.refreshing shouldBe true
        model.state.value.discoveryStatus["mangadex"] shouldBe InventoryWorkDiscoveryStatus.SEARCHING
        candidateResponse.complete(Result.success(null))
        advanceUntilIdle()
        model.state.value.discoveryStatus["mangadex"] shouldBe InventoryWorkDiscoveryStatus.NO_VERIFIED_MATCH
        model.state.value.refreshStops["mangadex"] shouldBe InventoryRefreshStop.NO_VERIFIED_WORK
        model.state.value.refreshing shouldBe false
    }

    @Test
    fun `automatic discovery failure remains visible independently from release refresh outcomes`() = runTest(
        dispatcher,
    ) {
        val registry = activeInventoryRegistry()
        coEvery { registry.awaitReady() } returns Unit
        val linker = mockk<MangaDexVerifiedWorkLinker>()
        coEvery { linker.discoverUnique("title", any()) } returns
            Result.failure(IllegalStateException("invalid upstream response"))
        val refresh = mockk<RefreshIndependentInventory>()
        coEvery { refresh.execute(any(), any(), any(), any(), any()) } returns
            InventoryRefreshResult(InventoryRefreshStop.NO_VERIFIED_WORK, 0, 0)
        val model = model(linker, refresh, registry)
        model.start("title")

        model.discoverAndRefreshAutomatically("title")
        advanceUntilIdle()

        model.state.value.discoveryStatus["mangadex"] shouldBe InventoryWorkDiscoveryStatus.FAILED
        model.state.value.refreshStops["mangadex"] shouldBe InventoryRefreshStop.NO_VERIFIED_WORK
        model.state.value.revision shouldBe 1
    }

    @Test
    fun `enabling chapter inventory while title is open automatically starts discovery without a button`() =
        runTest(dispatcher) {
            val md = allowedMangaDexProducer()
            val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var enabled = false
            val registry = mockk<IntegrationRegistry>()
            coEvery { registry.awaitReady() } returns Unit
            every { registry.observeChanges() } returns updates
            every { registry.configurationFingerprint() } answers { if (enabled) "on" else "off" }
            every { registry.inventoryProducers() } answers { if (enabled) listOf(md) else emptyList() }
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            coEvery { linker.discoverUnique("title", any()) } returns Result.success(null)
            val refresh = mockk<RefreshIndependentInventory>()
            coEvery { refresh.execute(any(), any(), any(), any(), any()) } returns
                InventoryRefreshResult(InventoryRefreshStop.NO_VERIFIED_WORK, 0, 0)
            val model = model(linker, refresh, registry)
            model.start("title")
            model.observeIntegrationChanges("title")
            runCurrent()

            coVerify(exactly = 0) { linker.discoverUnique(any(), any()) }
            enabled = true
            updates.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { linker.discoverUnique("title", any()) }
            model.state.value.discoveryStatus["mangadex"] shouldBe
                InventoryWorkDiscoveryStatus.NO_VERIFIED_MATCH
        }

    @Test
    fun `disabling an integration while discovery is pending invalidates the in-flight result`() =
        runTest(dispatcher) {
            val md = allowedMangaDexProducer()
            val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var enabled = true
            val registry = mockk<IntegrationRegistry>()
            coEvery { registry.awaitReady() } returns Unit
            every { registry.observeChanges() } returns updates
            every { registry.configurationFingerprint() } answers { if (enabled) "on" else "off" }
            every { registry.inventoryProducers() } answers { if (enabled) listOf(md) else emptyList() }
            val linker = mockk<MangaDexVerifiedWorkLinker>()
            val response = CompletableDeferred<Result<MangaDexVerifiedCandidate?>>()
            coEvery { linker.discoverUnique("title", any()) } coAnswers { response.await() }
            val refresh = mockk<RefreshIndependentInventory>()
            val model = model(linker, refresh, registry)
            model.start("title")
            model.observeIntegrationChanges("title")
            model.discoverAndRefreshAutomatically("title")
            runCurrent()

            model.state.value.refreshing shouldBe true
            enabled = false
            updates.emit(Unit)
            response.complete(Result.success(candidate))
            advanceUntilIdle()

            model.state.value.refreshing shouldBe false
            model.state.value.revision shouldBe 0
            coVerify(exactly = 0) { linker.confirm(any(), any(), any()) }
            coVerify(exactly = 0) { refresh.execute(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `enabled torrent evidence refresh runs without a separate inventory integration`() = runTest(dispatcher) {
        val registry = mockk<IntegrationRegistry>()
        coEvery { registry.awaitReady() } returns Unit
        every { registry.configurationFingerprint() } returns "steady"
        every { registry.inventoryProducers() } returns emptyList()
        val torrent = mockk<RefreshProviderTorrentInventory>()
        every { torrent.hasEnabledSources() } returns true
        coEvery { torrent.awaitReady() } returns Unit
        coEvery { torrent.execute("title", any(), any(), any()) } returns
            InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 1, 2)
        val model = model(mockk(), registry = registry, torrent = torrent)
        model.start("title")

        model.discoverAndRefreshAutomatically("title")
        advanceUntilIdle()

        coVerify(exactly = 1) { torrent.execute("title", any(), any(), any()) }
        model.state.value.refreshStops["provider:torrent"] shouldBe InventoryRefreshStop.EXHAUSTED
        model.state.value.revision shouldBe 1
    }

    @Test
    fun `enabling a torrent provider rechecks the open title automatically`() = runTest(dispatcher) {
        val changes = MutableSharedFlow<Unit>()
        val registry = mockk<IntegrationRegistry>()
        coEvery { registry.awaitReady() } returns Unit
        every { registry.configurationFingerprint() } returns "steady"
        every { registry.inventoryProducers() } returns emptyList()
        every { registry.observeChanges() } returns emptyFlow()
        var enabled = false
        val torrent = mockk<RefreshProviderTorrentInventory>()
        coEvery { torrent.awaitReady() } returns Unit
        every { torrent.hasEnabledSources() } answers { enabled }
        every { torrent.observeSourceChanges() } returns changes
        every { torrent.sourceFingerprint() } answers { if (enabled) "on" else "off" }
        coEvery { torrent.execute("title", any(), any(), any()) } returns
            InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 1, 1)

        val model = model(mockk(), registry = registry, torrent = torrent)
        model.start("title")
        model.observeIntegrationChanges("title")
        runCurrent()

        enabled = true
        changes.emit(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { torrent.execute("title", any(), any(), any()) }
        model.state.value.refreshStops["provider:torrent"] shouldBe InventoryRefreshStop.EXHAUSTED
    }

    private fun allowedMangaDexProducer(): IndependentInventoryProducer =
        mockk<IndependentInventoryProducer>().also { producer ->
            every { producer.producerId } returns "mangadex"
        }

    private fun activeInventoryRegistry(): IntegrationRegistry {
        val producer = allowedMangaDexProducer()
        return mockk<IntegrationRegistry>().also { registry ->
            every { registry.inventoryProducers() } answers { listOf(producer) }
            every { registry.configurationFingerprint() } returns "steady"
        }
    }

    private fun disabledTorrentRefresh(): RefreshProviderTorrentInventory =
        mockk<RefreshProviderTorrentInventory>().also { refresh ->
            coEvery { refresh.awaitReady() } returns Unit
            every { refresh.hasEnabledSources() } returns false
            every { refresh.sourceFingerprint() } returns "off"
            every { refresh.observeSourceChanges() } returns emptyFlow()
        }

    private fun model(
        linker: MangaDexVerifiedWorkLinker,
        refresh: RefreshIndependentInventory = mockk(),
        registry: IntegrationRegistry = activeInventoryRegistry(),
        torrent: RefreshProviderTorrentInventory = disabledTorrentRefresh(),
    ) = NativeInventoryRefreshScreenModel(
        refreshInventory = refresh,
        mangaDexWorkLinker = linker,
        mangaUpdatesWorkLinker = mockk(),
        integrationRegistry = registry,
        refreshTorrentInventory = torrent,
    )
}

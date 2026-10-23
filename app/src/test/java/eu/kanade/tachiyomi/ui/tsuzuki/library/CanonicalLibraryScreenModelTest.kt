package eu.kanade.tachiyomi.ui.tsuzuki.library

import eu.kanade.domain.tsuzuki.library.interactor.RemoveUnifiedLibraryTitle
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.interactor.ResolveCanonicalMetadata
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.interactor.ObserveUnifiedLibrary
import tachiyomi.domain.tsuzuki.library.interactor.RefreshUserLibraries
import tachiyomi.domain.tsuzuki.library.interactor.SetCanonicalLibraryStatus
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.migration.model.CanonicalMigrationReport
import tachiyomi.domain.tsuzuki.migration.model.MihonLibrarySnapshot
import tachiyomi.domain.tsuzuki.migration.service.MihonLibraryGateway
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReadingStartResolver
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalLibraryScreenModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `library first paint never performs source network for cover fallback`() = runTest(testDispatcher) {
        val title = CanonicalTitle(
            id = "title-network",
            displayTitle = "Dandadan",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = title.id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1L,
            updatedAt = 1L,
        )
        val observeUnifiedLibrary = mockk<ObserveUnifiedLibrary>()
        every { observeUnifiedLibrary.subscribe() } returns MutableStateFlow(
            listOf(
                UnifiedLibraryTitle(
                    title = title,
                    localEntry = entry,
                    externalMemberships = emptyList(),
                ),
            ),
        )
        val mangaRepository = mockk<MangaRepository>()
        every { mangaRepository.getLibraryMangaAsFlow() } returns MutableStateFlow(emptyList())
        val sourceResolver = mockk<ResolveCanonicalSourceManga>()
        coEvery { sourceResolver.execute(title.id, allowNetwork = false) } returns null
        val artworkRepository = mockk<TitleArtworkRepository>()
        every { artworkRepository.observeAll() } returns MutableStateFlow(emptyList())
        val refreshUserLibraries = mockk<RefreshUserLibraries>()
        coEvery { refreshUserLibraries.refreshConnected() } returns emptyMap()
        val metadataResolver = mockk<ResolveCanonicalMetadata>()
        every { metadataResolver.observeCached(any()) } returns flowOf(emptyMap())

        val screenModel = CanonicalLibraryScreenModel(
            observeUnifiedLibrary = observeUnifiedLibrary,
            setCanonicalLibraryStatus = mockk(relaxed = true),
            removeUnifiedLibraryTitle = mockk(relaxed = true),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
            refreshUserLibraries = refreshUserLibraries,
            resolveCanonicalMetadata = metadataResolver,
            mangaRepository = mangaRepository,
            resolveCanonicalSourceManga = sourceResolver,
            titleArtworkRepository = artworkRepository,
        )

        advanceUntilIdle()

        screenModel.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        coVerify(exactly = 1) { sourceResolver.execute(title.id, allowNetwork = false) }
        coVerify(exactly = 0) { sourceResolver.execute(title.id, allowNetwork = true) }
    }

    @Test
    fun `canonical entries are observable without any source state`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val fakeMigration = FakeMigrateMihonLibraryToCanonical()
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = fakeMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )

        val canonicalItem = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-1",
                displayTitle = "Frieren",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 1000L,
                updatedAt = 1000L,
            ),
            entry = CanonicalLibraryEntry(
                canonicalTitleId = "title-1",
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 1000L,
                updatedAt = 1000L,
            ),
        )

        fakeRepo.emitItems(listOf(canonicalItem))
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.items.map { it.canonicalTitleId } shouldBe listOf("title-1")
        state.items.first().title shouldBe "Frieren"
        state.items.first().status shouldBe LibraryStatus.READING
    }

    @Test
    fun `cached start years load by library identity and survive filter and progress updates`() = runTest(
        testDispatcher,
    ) {
        val repo = FakeCanonicalLibraryRepository()
        val readingRepository = FakeCanonicalReadingRepository()
        val artworkUpdates = MutableStateFlow(emptyList<TitleArtworkObservation>())
        val artworkRepository = mockk<TitleArtworkRepository>()
        every { artworkRepository.observeAll() } returns artworkUpdates
        val metadataResolver = mockk<ResolveCanonicalMetadata>()
        val metadataUpdates = MutableStateFlow(
            mapOf(
                "one" to ResolvedMetadata(
                    startDate = ProvenancedMetadata("2020-06-12", IntegrationId("mal"), "m1", "MAL"),
                ),
            ),
        )
        val requestedBatches = mutableListOf<List<String>>()
        every { metadataResolver.observeCached(any()) } answers {
            val ids = firstArg<Collection<String>>().toList()
            requestedBatches += ids
            metadataUpdates.map { snapshots -> snapshots.filterKeys(ids::contains) }
        }
        val model = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(repo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(repo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = repo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = readingRepository,
            resolveCanonicalMetadata = metadataResolver,
            titleArtworkRepository = artworkRepository,
        )
        repo.emitItems(listOf(canonicalItem("one", "Frieren")))
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>().items.single().startYear shouldBe
            "2020"
        model.search("Frieren")
        readingRepository.emitProgress(
            "one",
            listOf(CanonicalChapterProgress(canonicalChapterId = "chapter-one", lastPageRead = 1L)),
        )
        artworkUpdates.value = listOf(
            TitleArtworkObservation(
                canonicalTitleId = "one",
                provider = "kitsu",
                coverUrl = "cover",
                updatedAt = 1L,
            ),
        )
        advanceUntilIdle()

        val updated = model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>().items.single()
        updated.startYear shouldBe "2020"
        updated.readingState shouldBe
            CanonicalLibraryReadingState.IN_PROGRESS
        updated.coverUrl shouldBe "cover"
        requestedBatches shouldBe listOf(listOf("one"))

        // Details updates the local snapshot while the canonical inventory IDs stay unchanged.
        metadataUpdates.value = mapOf(
            "one" to ResolvedMetadata(
                startDate = ProvenancedMetadata("2021-04-01", IntegrationId("mal"), "m1", "MAL"),
            ),
        )
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>().items.single().startYear shouldBe
            "2021"

        repo.emitItems(listOf(canonicalItem("one", "Frieren"), canonicalItem("two", "Dungeon Meshi")))
        advanceUntilIdle()

        // Clear the active search before inspecting the expanded library.
        model.search(null)
        advanceUntilIdle()

        val expanded = model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>().items
        expanded.single { it.canonicalTitleId == "two" }.startYear shouldBe
            null
        requestedBatches shouldBe
            listOf(listOf("one"), listOf("one", "two"))
        listOf<ProvenancedMetadata<String>?>(
            null,
            ProvenancedMetadata("invalid", IntegrationId("mal"), "m1", "MAL"),
            ProvenancedMetadata("2021-02-30", IntegrationId("mal"), "m1", "MAL"),
        ).forEach { date ->
            metadataUpdates.value = mapOf("one" to ResolvedMetadata(startDate = date))
            advanceUntilIdle()
            model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
                .items.single { it.canonicalTitleId == "one" }.startYear shouldBe null
        }
        io.mockk.verify(exactly = 2) { metadataResolver.observeCached(any()) }
        coVerify(exactly = 0) { metadataResolver.execute("one", any()) }
        coVerify(exactly = 0) { metadataResolver.cached(any<Collection<String>>()) }
    }

    @Test
    fun `library enriches absent years without opening Details and without repeated requests`() = runTest(
        testDispatcher,
    ) {
        val repo = FakeCanonicalLibraryRepository()
        val metadataUpdates = MutableStateFlow<Map<String, ResolvedMetadata>>(emptyMap())
        val metadataResolver = mockk<ResolveCanonicalMetadata>()
        every { metadataResolver.observeCached(any()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            metadataUpdates.map { values -> values.filterKeys(ids::contains) }
        }
        val calls = mutableListOf<String>()
        coEvery { metadataResolver.execute(any(), any()) } coAnswers {
            val id = firstArg<String>()
            calls += id
            val resolved = ResolvedMetadata(
                startDate = ProvenancedMetadata(
                    if (id == "one") "2020-06-12" else "2019-03-01",
                    IntegrationId("mal"),
                    "mal-$id",
                    "MAL",
                ),
            )
            metadataUpdates.value = metadataUpdates.value + (id to resolved)
            Result.success(resolved)
        }
        val model = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(repo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(repo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = repo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
            resolveCanonicalMetadata = metadataResolver,
        )
        repo.emitItems(listOf(canonicalItem("one", "Frieren"), canonicalItem("two", "Spy x Family")))
        advanceUntilIdle()

        val cards = model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>().items
        cards.associate { it.canonicalTitleId to it.startYear } shouldBe
            mapOf("one" to "2020", "two" to "2019")
        calls shouldBe listOf("one", "two")

        model.search("Spy")
        advanceUntilIdle()
        model.search(null)
        advanceUntilIdle()
        calls shouldBe listOf("one", "two")
    }

    @Test
    fun `search filters primary library by canonical display title`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )
        fakeRepo.emitItems(
            listOf(
                canonicalItem("title-1", "Frieren"),
                canonicalItem("title-2", "Dungeon Meshi"),
            ),
        )
        advanceUntilIdle()

        screenModel.search("frie")
        advanceUntilIdle()

        val filtered = screenModel.state.value
        filtered.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        filtered.searchQuery shouldBe "frie"
        filtered.items.map { it.title } shouldBe listOf("Frieren")

        screenModel.search(null)
        advanceUntilIdle()

        val restored = screenModel.state.value
        restored.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        restored.items.map { it.title } shouldBe listOf("Frieren", "Dungeon Meshi")
    }

    @Test
    fun `category filter uses canonical title categories and supports uncategorized`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )
        fakeRepo.emitItems(
            listOf(
                canonicalItem(
                    id = "title-1",
                    title = "Frieren",
                    categories = listOf(category(1L, "Reading")),
                ),
                canonicalItem(
                    id = "title-2",
                    title = "Dungeon Meshi",
                    categories = emptyList(),
                ),
            ),
        )
        advanceUntilIdle()

        screenModel.selectCategory(1L)
        advanceUntilIdle()

        val categorized = screenModel.state.value
        categorized.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        categorized.selectedCategoryId shouldBe 1L
        categorized.items.map { it.canonicalTitleId } shouldBe listOf("title-1")

        screenModel.selectCategory(Category.UNCATEGORIZED_ID)
        advanceUntilIdle()

        val uncategorized = screenModel.state.value
        uncategorized.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        uncategorized.items.map { it.canonicalTitleId } shouldBe listOf("title-2")

        screenModel.selectCategory(null)
        advanceUntilIdle()

        val all = screenModel.state.value
        all.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        all.items.map { it.canonicalTitleId } shouldBe listOf("title-1", "title-2")
    }

    @Test
    fun `status changes update the entry and list`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = "title-2",
            status = LibraryStatus.PLANNING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        )
        fakeRepo.entries["title-2"] = entry

        val item = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-2",
                displayTitle = "Dungeon Meshi",
                identityState = CanonicalIdentityState.PARTIALLY_RESOLVED,
                createdAt = 1000L,
                updatedAt = 1000L,
            ),
            entry = entry,
        )
        fakeRepo.emitItems(listOf(item))

        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )
        advanceUntilIdle()

        screenModel.setStatus("title-2", LibraryStatus.COMPLETED)
        advanceUntilIdle()

        val updatedEntry = fakeRepo.entries["title-2"]
        updatedEntry?.status shouldBe LibraryStatus.COMPLETED
        updatedEntry?.addedAt shouldBe 1000L
    }

    @Test
    fun `remove deletes membership only`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = "title-3",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        )
        fakeRepo.entries["title-3"] = entry

        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )
        advanceUntilIdle()

        screenModel.removeItem("title-3")
        advanceUntilIdle()

        fakeRepo.entries.containsKey("title-3") shouldBe false
        fakeRepo.removedIds shouldBe listOf("title-3")
    }

    @Test
    fun `migration runs on init`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val fakeMigration = FakeMigrateMihonLibraryToCanonical()
        CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = fakeMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )
        advanceUntilIdle()

        fakeMigration.callCount shouldBe 1
    }

    @Test
    fun `library stays loading and unobserved until initial migration finishes`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val migrationGate = CompletableDeferred<Unit>()
        val fakeMigration = FakeMigrateMihonLibraryToCanonical(
            beforeReturn = { migrationGate.await() },
        )
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = fakeMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )

        val item = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-after-migration",
                displayTitle = "Dandadan",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 700L,
                updatedAt = 700L,
            ),
            entry = CanonicalLibraryEntry(
                canonicalTitleId = "title-after-migration",
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 700L,
                updatedAt = 700L,
            ),
        )
        fakeRepo.emitItems(listOf(item))
        advanceUntilIdle()

        screenModel.state.value shouldBe CanonicalLibraryScreenState.Loading
        fakeRepo.observeCallCount shouldBe 0

        migrationGate.complete(Unit)
        advanceUntilIdle()

        fakeRepo.observeCallCount shouldBe 1
        val state = screenModel.state.value
        state.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.items.map { it.canonicalTitleId } shouldBe listOf("title-after-migration")
    }

    @Test
    fun `migration failure does not block observation and UI state`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val failingMigration = FakeMigrateMihonLibraryToCanonical(shouldFail = true)
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = failingMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )

        val item = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-fail",
                displayTitle = "Solo Leveling",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 500L,
                updatedAt = 500L,
            ),
            entry = CanonicalLibraryEntry(
                canonicalTitleId = "title-fail",
                status = LibraryStatus.PLANNING,
                favorite = true,
                addedAt = 500L,
                updatedAt = 500L,
            ),
        )
        fakeRepo.emitItems(listOf(item))
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.items.map { it.canonicalTitleId } shouldBe listOf("title-fail")
    }

    @Test
    fun `read or continue emits canonical reader event`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val resolver = FakeCanonicalReadingStartResolver(
            result = CanonicalReadingStart.Ready("chapter-42"),
        )
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = resolver,
            canonicalReadingRepository = FakeCanonicalReadingRepository(),
        )
        advanceUntilIdle()

        val event = async { screenModel.events.first() }
        screenModel.readOrContinue("title-42")
        advanceUntilIdle()

        event.await() shouldBe CanonicalLibraryEvent.OpenReader("chapter-42")
        resolver.lastCanonicalTitleId shouldBe "title-42"
    }

    private fun category(id: Long, name: String) = Category(
        id = id,
        name = name,
        order = id,
        flags = 0L,
    )

    @Test
    fun `empty inventory is distinct from query with no results`() = runTest(testDispatcher) {
        val repo = FakeCanonicalLibraryRepository()
        val model = libraryModel(repo)
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>().totalItemCount shouldBe 0
        repo.emitItems(listOf(canonicalItem("one", "Frieren")))
        model.search("missing")
        advanceUntilIdle()
        val filtered = model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        filtered.items shouldBe emptyList()
        filtered.totalItemCount shouldBe 1
        filtered.searchQuery shouldBe "missing"
    }

    @Test
    fun `advanced filters and search preserve original inventory count`() = runTest(testDispatcher) {
        val repo = FakeCanonicalLibraryRepository()
        repo.emitItems(listOf(canonicalItem("one", "Frieren"), canonicalItem("two", "Dungeon Meshi")))
        val model = libraryModel(repo)
        model.search("Frieren")
        model.selectCategory(99L)
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>().totalItemCount shouldBe 2
        model.selectCategory(null)
        model.search(null)
        advanceUntilIdle()
        val restored = model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        restored.items.map { it.canonicalTitleId } shouldBe listOf("one", "two")
        restored.totalItemCount shouldBe 2
    }

    @Test
    fun `clearing advanced filters preserves status and search`() = runTest(testDispatcher) {
        val repo = FakeCanonicalLibraryRepository()
        repo.emitItems(listOf(canonicalItem("one", "Frieren")))
        val model = libraryModel(repo)
        model.selectStatus(LibraryStatus.READING)
        model.search("Frieren")
        model.selectOrigin("kitsu")
        model.selectProviderList("custom")
        model.selectCategory(42L)
        model.toggleFormat(tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat.MANGA)
        model.clearAdvancedFilters()
        advanceUntilIdle()
        val state = model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.searchQuery shouldBe "Frieren"
        state.filters shouldBe CanonicalLibraryFilterState(status = LibraryStatus.READING)
        state.items.map { it.canonicalTitleId } shouldBe listOf("one")
    }

    @Test
    fun `exiting contextual search preserves status and advanced choices`() = runTest(testDispatcher) {
        val repo = FakeCanonicalLibraryRepository()
        val model = libraryModel(repo)
        model.selectStatus(LibraryStatus.PLANNING)
        model.selectOrigin("kitsu")
        model.selectCategory(42L)
        model.search("missing")
        model.search(null)
        advanceUntilIdle()
        val state = model.state.value.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.searchQuery shouldBe null
        state.filters shouldBe CanonicalLibraryFilterState(
            status = LibraryStatus.PLANNING,
            origin = "kitsu",
            categoryId = 42L,
        )
    }

    private fun libraryModel(repo: FakeCanonicalLibraryRepository) = CanonicalLibraryScreenModel(
        observeCanonicalLibrary = ObserveCanonicalLibrary(repo),
        setCanonicalLibraryStatus = SetCanonicalLibraryStatus(repo),
        removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
            getFavoriteMihonMangaIds = { emptyList() },
            setMihonFavorite = { _, _ -> true },
            removeCanonical = repo::remove,
        ),
        migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
        resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
        canonicalReadingRepository = FakeCanonicalReadingRepository(),
    )

    private fun canonicalItem(
        id: String,
        title: String,
        categories: List<Category> = emptyList(),
    ) = CanonicalLibraryItem(
        title = CanonicalTitle(
            id = id,
            displayTitle = title,
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1000L,
            updatedAt = 1000L,
        ),
        entry = CanonicalLibraryEntry(
            canonicalTitleId = id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        ),
        categories = categories,
    )

    private class FakeMigrateMihonLibraryToCanonical(
        private val shouldFail: Boolean = false,
        private val beforeReturn: suspend () -> Unit = {},
    ) : MigrateMihonLibraryToCanonical(
        gateway = object : MihonLibraryGateway {
            override suspend fun snapshot(): List<MihonLibrarySnapshot> = emptyList()
        },
        sourceTitleMappingRepository = FakeSourceTitleMappingRepository(),
        materializeCanonicalTitle = MaterializeCanonicalTitle(FakeCanonicalTitleRepository()),
        canonicalLibraryRepository = FakeCanonicalLibraryRepository(),
    ) {
        var callCount = 0

        override suspend fun execute(): CanonicalMigrationReport {
            callCount++
            if (shouldFail) {
                throw RuntimeException("Migration failed")
            }
            beforeReturn()
            return CanonicalMigrationReport(0, 0, 0)
        }
    }

    private class FakeCanonicalLibraryRepository : CanonicalLibraryRepository {
        val entries = mutableMapOf<String, CanonicalLibraryEntry>()
        val removedIds = mutableListOf<String>()
        var observeCallCount = 0
        private val itemsFlow = MutableStateFlow<List<CanonicalLibraryItem>>(emptyList())

        fun emitItems(items: List<CanonicalLibraryItem>) {
            itemsFlow.value = items
        }

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = entries[canonicalTitleId]

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(entries.values.toList())

        override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> {
            observeCallCount++
            return itemsFlow
        }

        override suspend fun upsert(entry: CanonicalLibraryEntry) {
            entries[entry.canonicalTitleId] = entry
        }

        override suspend fun remove(canonicalTitleId: String) {
            entries.remove(canonicalTitleId)
            removedIds += canonicalTitleId
        }
    }

    private class FakeCanonicalReadingRepository : CanonicalReadingRepository {
        private val progressByTitle = mutableMapOf<String, MutableStateFlow<List<CanonicalChapterProgress>>>()

        fun emitProgress(canonicalTitleId: String, progress: List<CanonicalChapterProgress>) {
            progressByTitle.getOrPut(canonicalTitleId) { MutableStateFlow(emptyList()) }.value = progress
        }

        override suspend fun getProgress(
            canonicalChapterId: String,
        ): CanonicalChapterProgress? = progressByTitle.values
            .flatMap { it.value }
            .firstOrNull { it.canonicalChapterId == canonicalChapterId }

        override fun observeProgress(
            canonicalChapterId: String,
        ): Flow<CanonicalChapterProgress?> = MutableStateFlow(null)

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> =
            progressByTitle[canonicalTitleId]?.value.orEmpty()

        override fun observeProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterProgress>> =
            progressByTitle.getOrPut(canonicalTitleId) { MutableStateFlow(emptyList()) }

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) = Unit

        override suspend fun getHistory(
            canonicalChapterId: String,
        ): CanonicalChapterHistory? = null

        override suspend fun recordHistory(
            update: CanonicalChapterHistoryUpdate,
        ) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = null
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(null)
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = title
        override suspend fun insert(title: CanonicalTitle) {}
        override suspend fun addExternalIdentity(identity: ExternalIdentity) {}
    }

    private class FakeCanonicalReadingStartResolver(
        private val result: CanonicalReadingStart = CanonicalReadingStart.Unavailable("unused"),
    ) : CanonicalReadingStartResolver {
        var lastCanonicalTitleId: String? = null

        override suspend fun execute(canonicalTitleId: String): CanonicalReadingStart {
            lastCanonicalTitleId = canonicalTitleId
            return when (result) {
                is CanonicalReadingStart.Ready -> result
                is CanonicalReadingStart.Unavailable -> result.copy(canonicalTitleId = canonicalTitleId)
            }
        }
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> = emptyList()
        override fun getByCanonicalTitleIdAsFlow(
            canonicalTitleId: String,
        ): Flow<List<SourceTitleMapping>> = MutableStateFlow(emptyList())
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null
        override suspend fun upsert(mapping: SourceTitleMapping) {}
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {}
    }
}

package eu.kanade.tachiyomi.ui.tsuzuki.collections

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListResult
import tachiyomi.domain.tsuzuki.collections.execution.LogicalCatalogPage
import tachiyomi.domain.tsuzuki.collections.execution.ResidualPageCursor
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
import tachiyomi.domain.tsuzuki.home.model.HomeCollectionBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderTile
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionBrowseScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `collection browse binds approved folder preview projection`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        val projection = HomeCollectionBrowse(
            collectionId = "collection-1",
            title = "Minha coleção",
            folders = listOf(
                HomeFolderTile(
                    folderId = "folder-1",
                    title = "Romance",
                ),
            ),
        )
        every { interactor.subscribeCollection("collection-1") } returns
            MutableStateFlow(projection)

        val model = CollectionBrowseScreenModel(interactor)
        model.bind("collection-1")
        advanceUntilIdle()

        model.state.value shouldBe CollectionBrowseScreenState.Ready(projection)
    }

    @Test
    fun `folder browse selects first enabled list and loads real catalog page`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        val disabled = list("disabled", enabled = false, sortOrder = 0)
        val first = list("first", enabled = true, sortOrder = 1)
        val second = list("second", enabled = true, sortOrder = 2)
        every { interactor.subscribeFolder("collection-1", "folder-1") } returns
            MutableStateFlow(
                HomeFolderBrowse(
                    collectionId = "collection-1",
                    folderId = "folder-1",
                    title = "Romance",
                    childFolders = emptyList(),
                    lists = listOf(disabled, first, second),
                ),
            )

        val executor = mockk<ExecuteCollectionList>()
        coEvery {
            executor.execute(match<ExecuteCollectionListRequest> { it.listId == "first" })
        } returns ExecuteCollectionListResult.Page(
            list = first,
            page = LogicalCatalogPage(
                items = listOf(item("work-1")),
                nextCursor = ResidualPageCursor(rawOffset = 20),
            ),
        )

        val model = FolderCatalogScreenModel(
            getConfiguredHomeSections = interactor,
            executeCollectionList = executor,
            materializeCanonicalTitleFromCatalog = mockk(relaxed = true),
        )
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()

        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "first"
        val content = ready.content.shouldBeInstanceOf<FolderCatalogContent.Content>()
        content.items.map(CatalogItem::providerId) shouldBe listOf("work-1")
        content.nextCursor shouldBe ResidualPageCursor(rawOffset = 20)
    }

    @Test
    fun `folder list selection switches the executable catalog without mixing pages`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        val first = list("first", enabled = true, sortOrder = 0)
        val second = list("second", enabled = true, sortOrder = 1)
        every { interactor.subscribeFolder("collection-1", "folder-1") } returns
            MutableStateFlow(
                HomeFolderBrowse(
                    collectionId = "collection-1",
                    folderId = "folder-1",
                    title = "Romance",
                    childFolders = emptyList(),
                    lists = listOf(first, second),
                ),
            )

        val executor = mockk<ExecuteCollectionList>()
        coEvery {
            executor.execute(match<ExecuteCollectionListRequest> { it.listId == "first" })
        } returns ExecuteCollectionListResult.Page(
            list = first,
            page = LogicalCatalogPage(listOf(item("first-work")), null),
        )
        coEvery {
            executor.execute(match<ExecuteCollectionListRequest> { it.listId == "second" })
        } returns ExecuteCollectionListResult.Page(
            list = second,
            page = LogicalCatalogPage(listOf(item("second-work")), null),
        )

        val model = FolderCatalogScreenModel(
            getConfiguredHomeSections = interactor,
            executeCollectionList = executor,
            materializeCanonicalTitleFromCatalog = mockk(relaxed = true),
        )
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()

        model.selectList("second")
        advanceUntilIdle()

        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "second"
        ready.content.shouldBeInstanceOf<FolderCatalogContent.Content>()
            .items
            .map(CatalogItem::providerId) shouldBe listOf("second-work")
    }

    @Test
    fun `folder catalog item materializes canonical title before navigation`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        every { interactor.subscribeFolder("collection-1", "folder-1") } returns
            MutableStateFlow(null)

        val catalogItem = item("work-1")
        val materializer = mockk<MaterializeCanonicalTitleFromCatalog>()
        coEvery { materializer.execute(catalogItem) } returns CanonicalTitle(
            id = "canonical-1",
            displayTitle = "Work",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1,
            updatedAt = 1,
        )

        val model = FolderCatalogScreenModel(
            getConfiguredHomeSections = interactor,
            executeCollectionList = mockk(relaxed = true),
            materializeCanonicalTitleFromCatalog = materializer,
        )
        model.openCatalogItem(catalogItem)
        advanceUntilIdle()

        model.events.first() shouldBe FolderCatalogEvent.OpenCanonicalTitle("canonical-1")
    }

    @Test
    fun `direct list executes requested enabled list rather than first`() = runTest(dispatcher) {
        val (model, executor) = folderModel(listOf(list("first", true, 0), list("requested", true, 1)))
        model.bind("collection-1", "folder-1", preferredListId = "requested")
        advanceUntilIdle()
        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "requested"
        ready.content.shouldBeInstanceOf<FolderCatalogContent.Content>().items.single().providerId shouldBe "requested"
        coVerify(exactly = 1) { executor.execute(match { it.listId == "requested" }) }
        coVerify(exactly = 0) { executor.execute(match { it.listId == "first" }) }
        model.selectList("first")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>().selectedListId shouldBe "requested"
    }

    @Test
    fun `direct list missing or disabled never falls back`() = runTest(dispatcher) {
        for (requested in listOf("missing", "disabled")) {
            val (model, executor) = folderModel(listOf(list("first", true, 0), list("disabled", false, 1)))
            model.bind("collection-1", "folder-1", preferredListId = requested)
            advanceUntilIdle()
            val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
            ready.selectedListId shouldBe null
            ready.content shouldBe FolderCatalogContent.Idle
            coVerify(exactly = 0) { executor.execute(any()) }
        }
    }

    @Test
    fun `zero enabled lists do not execute`() = runTest(dispatcher) {
        val (model, executor) = folderModel(listOf(list("disabled", false, 0)))
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()
        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe null
        ready.content shouldBe FolderCatalogContent.Idle
        ready.showListSelector shouldBe false
        coVerify(exactly = 0) { executor.execute(any()) }
    }

    @Test
    fun `one enabled list executes without selector`() = runTest(dispatcher) {
        val (model, executor) = folderModel(listOf(list("disabled", false, 0), list("only", true, 1)))
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()
        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "only"
        ready.showListSelector shouldBe false
        coVerify(exactly = 1) { executor.execute(match { it.listId == "only" }) }
    }

    @Test
    fun `multiple lists tie break by identity and execute only selected`() = runTest(dispatcher) {
        val (model, executor) = folderModel(listOf(list("z", true, 0), list("a", true, 0)))
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()
        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "a"
        ready.showListSelector shouldBe true
        coVerify(exactly = 1) { executor.execute(match { it.listId == "a" }) }
        coVerify(exactly = 0) { executor.execute(match { it.listId == "z" }) }
    }

    @Test
    fun `pagination retains existing items and deduplicates provider identity`() = runTest(dispatcher) {
        val only = list("only", true, 0)
        val (model, executor) = folderModel(listOf(only))
        coEvery { executor.execute(any()) } returnsMany listOf(
            ExecuteCollectionListResult.Page(
                only,
                LogicalCatalogPage(listOf(item("one")), ResidualPageCursor(rawOffset = 20)),
            ),
            ExecuteCollectionListResult.Page(only, LogicalCatalogPage(listOf(item("one"), item("two")), null)),
        )
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
            .content.shouldBeInstanceOf<FolderCatalogContent.Content>()
            .items.map(CatalogItem::providerId) shouldBe listOf("one", "two")
    }

    @Test
    fun `switch cancels in flight execution and keeps replacement page`() = runTest(dispatcher) {
        val first = list("first", true, 0)
        val second = list("second", true, 1)
        val (model, executor) = folderModel(listOf(first, second))
        val cancelled = CompletableDeferred<Unit>()
        coEvery { executor.execute(match { it.listId == "first" }) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()
        model.selectList("second")
        advanceUntilIdle()
        cancelled.isCompleted shouldBe true
        model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
            .content.shouldBeInstanceOf<FolderCatalogContent.Content>()
            .items.single().providerId shouldBe "second"
    }

    @Test
    fun `rebind to another folder cancels previous execution`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        val first = list("first", true, 0)
        val second = list("second", true, 1).copy(folderId = "folder-2")
        every { interactor.subscribeFolder("collection-1", "folder-1") } returns MutableStateFlow(
            HomeFolderBrowse("collection-1", "folder-1", "First", emptyList(), listOf(first)),
        )
        every { interactor.subscribeFolder("collection-1", "folder-2") } returns MutableStateFlow(
            HomeFolderBrowse("collection-1", "folder-2", "Second", emptyList(), listOf(second)),
        )
        val executor = mockk<ExecuteCollectionList>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { executor.execute(match { it.listId == "first" }) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        coEvery { executor.execute(match { it.listId == "second" }) } returns
            ExecuteCollectionListResult.Page(second, LogicalCatalogPage(listOf(item("second")), null))
        val model = FolderCatalogScreenModel(interactor, executor, mockk(relaxed = true))
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()
        model.bind("collection-1", "folder-2")
        advanceUntilIdle()
        cancelled.isCompleted shouldBe true
        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.folder.folderId shouldBe "folder-2"
        ready.content.shouldBeInstanceOf<FolderCatalogContent.Content>().items.single().providerId shouldBe "second"
    }

    @Test
    fun `materialization failure emits no navigation and preserves browse`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        every { interactor.subscribeFolder("collection-1", "folder-1") } returns MutableStateFlow(null)
        val materializer = mockk<MaterializeCanonicalTitleFromCatalog>()
        coEvery { materializer.execute(any()) } throws IllegalStateException("Unavailable")
        val model = FolderCatalogScreenModel(interactor, mockk(relaxed = true), materializer)
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()
        val events = mutableListOf<FolderCatalogEvent>()
        backgroundScope.launch { model.events.collect { events += it } }
        runCurrent()
        model.openCatalogItem(item("work"))
        advanceUntilIdle()
        runCurrent()
        events shouldBe emptyList()
        model.state.value shouldBe FolderCatalogScreenState.Missing
    }

    @Test
    fun `same folder rebind changes direct list without stale content`() = runTest(dispatcher) {
        val (model, _) = folderModel(listOf(list("first", true, 0), list("second", true, 1)))
        model.bind("collection-1", "folder-1", preferredListId = "first")
        advanceUntilIdle()
        model.bind("collection-1", "folder-1", preferredListId = "second")
        advanceUntilIdle()
        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "second"
        ready.content.shouldBeInstanceOf<FolderCatalogContent.Content>().items.single().providerId shouldBe "second"
        ready.showListSelector shouldBe false
    }

    private fun folderModel(lists: List<CollectionList>): Pair<FolderCatalogScreenModel, ExecuteCollectionList> {
        val interactor = mockk<GetConfiguredHomeSections>()
        every { interactor.subscribeFolder("collection-1", "folder-1") } returns MutableStateFlow(
            HomeFolderBrowse("collection-1", "folder-1", "Folder", emptyList(), lists),
        )
        val executor = mockk<ExecuteCollectionList>()
        coEvery { executor.execute(any()) } answers {
            val id = firstArg<ExecuteCollectionListRequest>().listId
            ExecuteCollectionListResult.Page(lists.first { it.id == id }, LogicalCatalogPage(listOf(item(id)), null))
        }
        return FolderCatalogScreenModel(interactor, executor, mockk(relaxed = true)) to executor
    }

    private fun item(id: String) = CatalogItem(
        provider = "kitsu",
        providerId = id,
        title = "Work",
        coverUrl = "https://example/$id.jpg",
    )

    private fun list(
        id: String,
        enabled: Boolean,
        sortOrder: Long,
    ) = CollectionList(
        id = id,
        collectionId = "collection-1",
        folderId = "folder-1",
        title = id,
        providerId = "kitsu",
        query = null,
        sort = CollectionSortSelection.DEFAULT,
        sortOrder = sortOrder,
        enabled = enabled,
        origin = CollectionOrigin.USER,
        createdAt = 1,
        updatedAt = 1,
    )
}

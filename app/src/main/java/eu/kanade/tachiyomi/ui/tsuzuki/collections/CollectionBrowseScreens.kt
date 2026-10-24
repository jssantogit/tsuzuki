package eu.kanade.tachiyomi.ui.tsuzuki.collections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreen
import eu.kanade.presentation.tsuzuki.collections.CollectionBrowseScreen as CollectionBrowseScreenContent
import eu.kanade.presentation.tsuzuki.collections.FolderCatalogScreen as FolderCatalogScreenContent

class CollectionBrowseScreen(
    private val collectionId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<CollectionBrowseScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(collectionId) {
            screenModel.bind(collectionId)
        }

        CollectionBrowseScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onFolder = { folderId ->
                navigator.push(
                    FolderCatalogScreen(
                        collectionId = collectionId,
                        folderId = folderId,
                    ),
                )
            },
        )
    }
}

class FolderCatalogScreen(
    private val collectionId: String,
    private val folderId: String,
) : Screen() {

    @Composable
    override fun Content() {
        FolderBrowseContent(collectionId, folderId)
    }
}

class DirectListScreen(
    private val collectionId: String,
    private val folderId: String,
    private val listId: String,
) : Screen() {
    @Composable
    override fun Content() {
        FolderBrowseContent(collectionId, folderId, listId)
    }
}

@Composable
private fun FolderBrowseContent(
    collectionId: String,
    folderId: String,
    preferredListId: String? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = metroViewModel<FolderCatalogScreenModel>()
    val state by screenModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(collectionId, folderId, preferredListId) {
        screenModel.bind(
            collectionId = collectionId,
            folderId = folderId,
            preferredListId = preferredListId,
        )
    }

    FolderCatalogScreenContent(
        state = state,
        navigateUp = navigator::pop,
        onChildFolder = { childFolderId ->
            navigator.push(
                FolderCatalogScreen(
                    collectionId = collectionId,
                    folderId = childFolderId,
                ),
            )
        },
        onSelectList = screenModel::selectList,
        onCatalogItem = screenModel::openCatalogItem,
        onLoadMore = screenModel::loadMore,
        onRetry = screenModel::retry,
        preferredListId = preferredListId,
    )

    LaunchedEffect(screenModel) {
        screenModel.events.collect { event ->
            when (event) {
                is FolderCatalogEvent.OpenCanonicalTitle -> {
                    navigator.push(
                        CanonicalTitleScreen(event.canonicalTitleId),
                    )
                }
            }
        }
    }
}

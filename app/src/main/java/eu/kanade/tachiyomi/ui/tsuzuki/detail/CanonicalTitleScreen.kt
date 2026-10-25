package eu.kanade.tachiyomi.ui.tsuzuki.detail

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiProvidersDefaultSkinScreen
import eu.kanade.presentation.tsuzuki.content.ContentBindingLinkSheet
import eu.kanade.presentation.tsuzuki.content.ContentOptionSelectorSheet
import eu.kanade.presentation.tsuzuki.detail.CanonicalTitleDetailScreen
import eu.kanade.presentation.tsuzuki.detail.canonicalDetailsProviderLabels
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentBindingLinkScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentBindingLinkState
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenModel
import kotlinx.coroutines.flow.collect
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data class CanonicalTitleScreen(
    val canonicalTitleId: String,
    val openSourceBindingFlow: Boolean = false,
    val finishOnNavigateUp: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val navigateUp: () -> Unit = {
            if (finishOnNavigateUp) {
                (context as? Activity)?.finish()
            } else {
                navigator.pop()
            }
        }
        BackHandler(
            enabled = finishOnNavigateUp,
            onBack = navigateUp,
        )

        val screenModel = metroViewModel<CanonicalTitleScreenModel>()
        val inventoryModel = metroViewModel<NativeInventoryRefreshScreenModel>()
        val inventoryState by inventoryModel.state.collectAsStateWithLifecycle()
        var requestedInventorySource by rememberSaveable(canonicalTitleId) { mutableStateOf<String?>(null) }
        val contentSelectorViewModel = metroViewModel<ContentSelectorScreenModel>()
        val linkViewModel = metroViewModel<ContentBindingLinkScreenModel>()
        val linkState by linkViewModel.state.collectAsStateWithLifecycle()
        var linkSheetOpen by rememberSaveable(canonicalTitleId) { mutableStateOf(false) }
        var initialLinkFlowOpened by rememberSaveable(canonicalTitleId) { mutableStateOf(false) }
        val state by screenModel.state.collectAsStateWithLifecycle()
        val contentSelectorState by contentSelectorViewModel.state.collectAsStateWithLifecycle()
        val loaded = (state as? CanonicalTitleScreenState.Loaded)
            ?.takeIf { it.title.id == canonicalTitleId }
        val downloadSelectionChapterId = loaded?.downloadSelectionChapterId
        val openReader: (String) -> Unit = { chapterId ->
            context.startActivity(
                ReaderActivity.newCanonicalIntent(context, chapterId),
            )
        }
        val openProviders: () -> Unit = { navigator.push(canonicalDetailsProvidersDestination()) }

        LaunchedEffect(canonicalTitleId, openSourceBindingFlow) {
            screenModel.start(canonicalTitleId)
            inventoryModel.start(canonicalTitleId)
            if (
                shouldAutoOpenSourceBindingFlow(
                    canonicalTitleId,
                    openSourceBindingFlow,
                    initialLinkFlowOpened,
                )
            ) {
                initialLinkFlowOpened = true
                linkViewModel.start(canonicalTitleId)
                linkSheetOpen = true
            }
        }

        DisposableEffect(canonicalTitleId) {
            onDispose { inventoryModel.cancel() }
        }
        LaunchedEffect(canonicalTitleId, inventoryState.revision) {
            if (inventoryState.revision > 0) screenModel.reloadReconciledChapters()?.join()
        }

        CanonicalTitleDetailScreen(
            state = state,
            navigateUp = navigateUp,
            onAtualizar = { screenModel.refresh() },
            onAddToLibrary = { screenModel.addToLibrary() },
            onRemoveFromLibrary = { screenModel.removeFromLibrary() },
            onOpenProvidersSettings = openProviders,
            onFetchInventory = { source -> requestedInventorySource = source },
            inventoryRefresh = inventoryState,
            onReadTitle = { screenModel.startReading(openReader) },
            onBaixarChapter = { canonicalChapterId ->
                screenModel.requestDownload(canonicalChapterId)
            },
            onOpenChapter = { canonicalChapterId ->
                screenModel.openChapter(canonicalChapterId, openReader)
            },
        )

        requestedInventorySource?.let { source ->
            AlertDialog(
                onDismissRequest = { requestedInventorySource = null },
                title = { Text(stringResource(MR.strings.tsuzuki_inventory_confirm_title)) },
                text = { Text(stringResource(MR.strings.tsuzuki_inventory_confirm_description)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            requestedInventorySource = null
                            inventoryModel.refresh(canonicalTitleId, source)
                        },
                    ) { Text(stringResource(MR.strings.tsuzuki_inventory_confirm_collect)) }
                },
                dismissButton = {
                    TextButton(onClick = { requestedInventorySource = null }) {
                        Text(stringResource(MR.strings.tsuzuki_inventory_confirm_cancel))
                    }
                },
            )
        }

        LaunchedEffect(linkViewModel, canonicalTitleId) {
            linkViewModel.bindingUpdates.collect { request ->
                if (request.canonicalTitleId != canonicalTitleId) return@collect
                // A manually linked edition should not re-fetch every existing
                // source inventory before its chapter can be selected.
                if (contentSelectorViewModel.refreshAfterBindings(request).isSuccess) {
                    screenModel.reloadReconciledChapters()?.join()
                }
            }
        }
        if (linkSheetOpen) {
            ContentBindingLinkSheet(
                state = linkState,
                providerLabels = canonicalDetailsProviderLabels(),
                onSelectAddon = linkViewModel::selectAddon,
                onConfirmCandidate = linkViewModel::confirm,
                onSearchMore = linkViewModel::searchMore,
                onBack = linkViewModel::backToAddons,
                onDismiss = {
                    linkSheetOpen = false
                    linkViewModel.close()
                },
            )
        }

        if (downloadSelectionChapterId != null && !linkSheetOpen) {
            LaunchedEffect(canonicalTitleId, downloadSelectionChapterId) {
                contentSelectorViewModel.start(
                    canonicalTitleId = canonicalTitleId,
                    canonicalChapterId = downloadSelectionChapterId,
                )
            }
            ContentOptionSelectorSheet(
                state = contentSelectorState,
                providerLabels = canonicalDetailsProviderLabels(),
                onSelect = { item ->
                    val selection = contentSelectorViewModel.select(item)
                    screenModel.downloadSelectedOption(selection.option)
                },
                onRetry = { contentSelectorViewModel.retry() },
                onFindOrAddSource = { sourceCanonicalTitleId ->
                    contentSelectorViewModel.cancelDiscovery()
                    linkViewModel.start(sourceCanonicalTitleId)
                    linkSheetOpen = true
                },
                onCancelDiscovery = contentSelectorViewModel::cancelDiscovery,
                onOpenAddonsSettings = openProviders,
                onDismissRequest = {
                    contentSelectorViewModel.cancelDiscovery()
                    screenModel.dismissDownloadSelector()
                },
            )
        }
    }
}

internal fun canonicalDetailsProvidersDestination(): Screen = SettingsTsuzukiProvidersDefaultSkinScreen()

internal fun shouldAutoOpenSourceBindingFlow(
    canonicalTitleId: String,
    requested: Boolean,
    alreadyOpened: Boolean,
): Boolean = canonicalTitleId.isNotBlank() && requested && !alreadyOpened

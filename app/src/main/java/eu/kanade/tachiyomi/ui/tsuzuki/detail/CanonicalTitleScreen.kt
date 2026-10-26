package eu.kanade.tachiyomi.ui.tsuzuki.detail

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import tachiyomi.domain.tsuzuki.chapter.inventory.IndependentInventorySourceSnapshot
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
        val adoptionModel = metroViewModel<UserAdoptionReviewScreenModel>()
        val adoptionState by adoptionModel.state.collectAsStateWithLifecycle()
        var reviewSource by remember(canonicalTitleId) { mutableStateOf<IndependentInventorySourceSnapshot?>(null) }
        var selectedCandidates by remember(canonicalTitleId) { mutableStateOf(setOf<String>()) }
        var requestedInventorySource by rememberSaveable(canonicalTitleId) { mutableStateOf<String?>(null) }
        var mangaDexLinkDialog by rememberSaveable(canonicalTitleId) { mutableStateOf(false) }
        var mangaDexIdInput by rememberSaveable(canonicalTitleId) { mutableStateOf("") }
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
            adoptionModel.start(canonicalTitleId)
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
            onDispose {
                inventoryModel.cancel()
                adoptionModel.dismiss()
            }
        }
        LaunchedEffect(canonicalTitleId, inventoryState.revision) {
            if (inventoryState.revision > 0) screenModel.reloadReconciledChapters()?.join()
        }

        LaunchedEffect(canonicalTitleId, adoptionState.revision) {
            if (adoptionState.revision > 0) screenModel.reloadReconciledChapters()?.join()
        }

        CanonicalTitleDetailScreen(
            state = state,
            navigateUp = navigateUp,
            onAtualizar = { screenModel.refresh() },
            onAddToLibrary = { screenModel.addToLibrary() },
            onRemoveFromLibrary = { screenModel.removeFromLibrary() },
            onOpenProvidersSettings = openProviders,
            onFetchInventory = { source -> requestedInventorySource = source },
            onLinkMangaDex = {
                inventoryModel.clearMangaDexLink()
                mangaDexIdInput = ""
                mangaDexLinkDialog = true
            },
            onReviewCandidates = { source ->
                adoptionModel.dismiss()
                reviewSource = source
                selectedCandidates = emptySet()
            },
            inventoryRefresh = inventoryState,
            onReadTitle = { screenModel.startReading(openReader) },
            onBaixarChapter = { canonicalChapterId ->
                screenModel.requestDownload(canonicalChapterId)
            },
            onOpenChapter = { canonicalChapterId ->
                screenModel.openChapter(canonicalChapterId, openReader)
            },
        )

        reviewSource?.takeIf { it.scope.canonicalTitleId == canonicalTitleId }?.let { source ->
            AlertDialog(
                onDismissRequest = { reviewSource = null },
                title = { Text("Selecionar capítulos da edição") },
                text = {
                    Column {
                        Text("Edição: ${source.scope.editionKey} · Idioma: ${source.scope.language}")
                        Text("Estes dados são candidatos, não capítulos oficiais atestados por uma editora.")
                        source.candidateExamples.forEach { example ->
                            Row {
                                Checkbox(
                                    checked = example.sourceRecordId in selectedCandidates,
                                    onCheckedChange = { checked ->
                                        selectedCandidates = if (checked) {
                                            selectedCandidates + example.sourceRecordId
                                        } else {
                                            selectedCandidates - example.sourceRecordId
                                        }
                                    },
                                )
                                Text(example.label)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val selected = source.candidateExamples
                                .map { it.sourceRecordId }
                                .filter { it in selectedCandidates }
                            reviewSource = null
                            adoptionModel.preview(source.scope, selected)
                        },
                        enabled = selectedCandidates.isNotEmpty(),
                    ) { Text("Ver prévia") }
                },
                dismissButton = {
                    TextButton(onClick = { reviewSource = null }) { Text("Cancelar") }
                },
            )
        }

        adoptionState.plan?.let { ready ->
            var acknowledged by remember(ready.fingerprint) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { adoptionModel.dismiss() },
                title = { Text("Confirmar adoção dos capítulos") },
                text = {
                    Column {
                        Text("Obra: $canonicalTitleId")
                        Text("Edição: ${ready.scope.editionKey} · Idioma: ${ready.scope.language}")
                        Text("Origem: ${ready.scope.producerId}")
                        ready.units.forEach { unit ->
                            Text(
                                "${unit.label} · ${unit.provenance.sourceKind.name} · " +
                                    "revisão ${unit.provenance.exactRevision}",
                            )
                        }
                        Text(
                            "Os capítulos serão considerados adotados por você, não oficiais. " +
                                "Isto não disponibiliza páginas para leitura.",
                        )
                        Row {
                            Checkbox(
                                checked = acknowledged,
                                onCheckedChange = { acknowledged = it },
                            )
                            Text("Revisei a edição e os capítulos selecionados.")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { adoptionModel.confirm(canonicalTitleId) },
                        enabled = acknowledged && !adoptionState.adopting,
                    ) { Text(if (adoptionState.adopting) "Registrando..." else "Adotar capítulos") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { adoptionModel.dismiss() },
                        enabled = !adoptionState.adopting,
                    ) { Text("Cancelar") }
                },
            )
        }

        if (adoptionState.failed || adoptionState.blockedReason != null || adoptionState.completed) {
            AlertDialog(
                onDismissRequest = { adoptionModel.dismiss() },
                title = {
                    Text(if (adoptionState.completed) "Adoção registrada" else "Adoção não realizada")
                },
                text = {
                    Text(
                        if (adoptionState.completed) {
                            "Os capítulos foram adicionados com recibo de origem e revisão. " +
                                "O conteúdo de leitura dependerá de uma fonte disponível."
                        } else {
                            "A seleção não passou na validação, pode conflitar com capítulos existentes " +
                                "ou a origem ter mudado desde a prévia. Nenhum capítulo foi gravado."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { adoptionModel.dismiss() }) { Text("Entendi") }
                },
            )
        }

        if (mangaDexLinkDialog) {
            val closeLinkDialog = {
                mangaDexLinkDialog = false
                inventoryModel.clearMangaDexLink()
            }
            AlertDialog(
                onDismissRequest = closeLinkDialog,
                title = { Text(stringResource(MR.strings.tsuzuki_inventory_link_title)) },
                text = {
                    Column {
                        Text(stringResource(MR.strings.tsuzuki_inventory_link_description))
                        OutlinedTextField(
                            value = mangaDexIdInput,
                            onValueChange = {
                                mangaDexIdInput = it
                                inventoryModel.clearMangaDexLink()
                            },
                            enabled = !inventoryState.linking && !inventoryState.linkCompleted,
                            label = { Text(stringResource(MR.strings.tsuzuki_inventory_link_input)) },
                            singleLine = true,
                        )
                        inventoryState.linkCandidate?.let { candidate ->
                            Text(
                                stringResource(
                                    MR.strings.tsuzuki_inventory_link_verified_candidate,
                                    candidate.displayTitle,
                                    candidate.matchedProvider,
                                    candidate.matchedExternalId,
                                ),
                            )
                        }
                        if (inventoryState.linkFailed) {
                            Text(stringResource(MR.strings.tsuzuki_inventory_link_failed))
                        }
                        if (inventoryState.linkCompleted) {
                            Text(stringResource(MR.strings.tsuzuki_inventory_link_success))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            when {
                                inventoryState.linkCompleted -> closeLinkDialog()
                                inventoryState.linkCandidate != null ->
                                    inventoryModel.confirmMangaDexLink(canonicalTitleId)
                                else -> inventoryModel.previewMangaDexLink(canonicalTitleId, mangaDexIdInput)
                            }
                        },
                        enabled = !inventoryState.linking &&
                            (
                                inventoryState.linkCompleted ||
                                    inventoryState.linkCandidate != null ||
                                    mangaDexIdInput.isNotBlank()
                                ),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    inventoryState.linkCompleted -> MR.strings.tsuzuki_inventory_link_done
                                    inventoryState.linkCandidate != null -> MR.strings.tsuzuki_inventory_link_confirm
                                    else -> MR.strings.tsuzuki_inventory_link_preview
                                },
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = closeLinkDialog) {
                        Text(stringResource(MR.strings.tsuzuki_inventory_confirm_cancel))
                    }
                },
            )
        }

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

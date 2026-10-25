package eu.kanade.tachiyomi.ui.tsuzuki.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexInventoryProducer
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedCandidate
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedWorkLinker
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaUpdatesInventoryProducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.chapter.inventory.IndependentInventoryProducer
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshStop
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshIndependentInventory
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

data class NativeInventoryRefreshState(
    val producerId: String? = null,
    val refreshing: Boolean = false,
    val lastStop: InventoryRefreshStop? = null,
    val revision: Int = 0,
    val linking: Boolean = false,
    val linkCandidate: MangaDexVerifiedCandidate? = null,
    val linkFailed: Boolean = false,
    val linkCompleted: Boolean = false,
)

/**
 * Manual opt-in only. No title open, ordinary refresh, or background task starts
 * inventory network traffic. The confirmation dialog lives at the UI boundary.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NativeInventoryRefreshScreenModel(
    private val refreshInventory: RefreshIndependentInventory,
    private val mangaUpdates: MangaUpdatesInventoryProducer,
    private val mangaDex: MangaDexInventoryProducer,
    private val mangaDexWorkLinker: MangaDexVerifiedWorkLinker,
    private val integrationSettings: IntegrationSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NativeInventoryRefreshState())
    val state: StateFlow<NativeInventoryRefreshState> = mutableState.asStateFlow()

    private var activeTitleId: String? = null
    private var operation: Job? = null
    private var linkOperation: Job? = null

    fun start(titleId: String) {
        if (activeTitleId == titleId) return
        operation?.cancel()
        linkOperation?.cancel()
        activeTitleId = titleId
        mutableState.value = NativeInventoryRefreshState()
    }

    fun refresh(titleId: String, producerId: String): Job? {
        if (titleId != activeTitleId || operation?.isActive == true) return null
        val producer: IndependentInventoryProducer = when (producerId) {
            mangaUpdates.producerId -> mangaUpdates
            mangaDex.producerId -> mangaDex
            else -> return null
        }
        operation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                producerId = producerId,
                refreshing = true,
                lastStop = null,
            )
            try {
                val result = refreshInventory.execute(
                    canonicalTitleId = titleId,
                    producer = producer,
                    maxPages = 3,
                    isEnabled = {
                        activeTitleId == titleId &&
                            (
                                producerId != mangaUpdates.producerId ||
                                    integrationSettings.get(IntegrationId("mangaupdates"))?.enabled == true
                                )
                    },
                )
                if (activeTitleId == titleId) {
                    mutableState.value = mutableState.value.copy(
                        refreshing = false,
                        lastStop = result.stop,
                        revision = mutableState.value.revision + 1,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (activeTitleId == titleId) {
                    mutableState.value = mutableState.value.copy(
                        refreshing = false,
                        lastStop = InventoryRefreshStop.FAILED,
                        revision = mutableState.value.revision + 1,
                    )
                }
            }
        }
        return operation
    }

    /** Preview is an explicit remote read and never persists an unverified title. */
    fun previewMangaDexLink(titleId: String, mangaDexId: String): Job? {
        if (titleId != activeTitleId || linkOperation?.isActive == true || mutableState.value.refreshing) return null
        linkOperation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                linking = true,
                linkCandidate = null,
                linkFailed = false,
                linkCompleted = false,
            )
            val result = try {
                mangaDexWorkLinker.preview(titleId, mangaDexId.trim())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.failure(IllegalStateException("MangaDex work verification failed"))
            }
            if (activeTitleId == titleId) {
                mutableState.value = mutableState.value.copy(
                    linking = false,
                    linkCandidate = result.getOrNull(),
                    linkFailed = result.isFailure,
                )
            }
        }
        return linkOperation
    }

    /** Confirmation performs a fresh remote revalidation before saving a verified work ID. */
    fun confirmMangaDexLink(titleId: String): Job? {
        if (titleId != activeTitleId || linkOperation?.isActive == true || mutableState.value.refreshing) return null
        val candidate = mutableState.value.linkCandidate ?: return null
        linkOperation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(linking = true, linkFailed = false)
            val result = try {
                mangaDexWorkLinker.confirm(titleId, candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.failure(IllegalStateException("MangaDex work confirmation failed"))
            }
            if (activeTitleId == titleId) {
                mutableState.value = mutableState.value.copy(
                    linking = false,
                    linkCandidate = if (result.isSuccess) null else candidate,
                    linkFailed = result.isFailure,
                    linkCompleted = result.isSuccess,
                    revision = mutableState.value.revision + if (result.isSuccess) 1 else 0,
                )
            }
        }
        return linkOperation
    }

    fun clearMangaDexLink() {
        linkOperation?.cancel()
        mutableState.value = mutableState.value.copy(
            linking = false,
            linkCandidate = null,
            linkFailed = false,
            linkCompleted = false,
        )
    }

    fun cancel() {
        operation?.cancel()
        linkOperation?.cancel()
        mutableState.value = mutableState.value.copy(refreshing = false, linking = false)
    }
}

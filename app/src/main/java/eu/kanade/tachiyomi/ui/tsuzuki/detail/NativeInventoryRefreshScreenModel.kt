package eu.kanade.tachiyomi.ui.tsuzuki.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexInventoryProducer
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
    private val integrationSettings: IntegrationSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NativeInventoryRefreshState())
    val state: StateFlow<NativeInventoryRefreshState> = mutableState.asStateFlow()

    private var activeTitleId: String? = null
    private var operation: Job? = null

    fun start(titleId: String) {
        if (activeTitleId == titleId) return
        operation?.cancel()
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

    fun cancel() {
        operation?.cancel()
        mutableState.value = mutableState.value.copy(refreshing = false)
    }
}

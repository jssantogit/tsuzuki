package eu.kanade.tachiyomi.ui.tsuzuki.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionConfirmation
import tachiyomi.domain.tsuzuki.provider.reading.SelectProviderReadingEdition

/** Foreground-only selection; candidate results are not persisted as chapter authority. */
data class ProviderEditionPickerState(
    val open: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val confirming: Boolean = false,
    val candidates: List<ProviderEditionCandidate> = emptyList(),
    val failed: Boolean = false,
    val confirmation: ProviderEditionConfirmation? = null,
    val revision: Int = 0,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ProviderEditionPickerScreenModel(
    private val selectEdition: SelectProviderReadingEdition,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderEditionPickerState())
    val state: StateFlow<ProviderEditionPickerState> = mutableState.asStateFlow()

    private var titleId: String? = null
    private var operation: Job? = null

    fun start(canonicalTitleId: String) {
        if (titleId == canonicalTitleId) return
        operation?.cancel()
        titleId = canonicalTitleId
        mutableState.value = ProviderEditionPickerState()
    }

    fun open(canonicalTitleId: String, displayTitle: String) {
        if (canonicalTitleId != titleId || displayTitle.isBlank()) return
        mutableState.value = mutableState.value.copy(open = true, confirmation = null)
        search(displayTitle)
    }

    fun search(query: String) {
        val id = titleId ?: return
        if (!mutableState.value.open || mutableState.value.confirming) return
        operation?.cancel()
        mutableState.value = mutableState.value.copy(
            query = query,
            searching = true,
            candidates = emptyList(),
            failed = false,
            confirmation = null,
        )
        operation = viewModelScope.launch {
            try {
                val result = selectEdition.search(id, query)
                if (titleId != id || !mutableState.value.open || mutableState.value.query != query) return@launch
                mutableState.value = mutableState.value.copy(
                    searching = false,
                    candidates = result.candidates,
                    failed = result.hadFailures,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (titleId == id && mutableState.value.open) {
                    mutableState.value = mutableState.value.copy(searching = false, failed = true)
                }
            }
        }
    }

    fun confirm(candidate: ProviderEditionCandidate) {
        val id = titleId ?: return
        val current = mutableState.value
        if (!current.open || current.searching || current.confirming || candidate !in current.candidates) return
        operation?.cancel()
        mutableState.value = current.copy(confirming = true, confirmation = null)
        operation = viewModelScope.launch {
            try {
                val result = selectEdition.confirm(id, candidate)
                if (id != titleId || !mutableState.value.open) return@launch
                mutableState.value = if (
                    result == ProviderEditionConfirmation.BOUND ||
                    result == ProviderEditionConfirmation.ALREADY_BOUND
                ) {
                    mutableState.value.copy(
                        open = false,
                        confirming = false,
                        confirmation = null,
                        revision = mutableState.value.revision + 1,
                    )
                } else {
                    mutableState.value.copy(confirming = false, confirmation = result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (id == titleId && mutableState.value.open) {
                    mutableState.value = mutableState.value.copy(
                        confirming = false,
                        confirmation = ProviderEditionConfirmation.UNAVAILABLE,
                    )
                }
            }
        }
    }

    fun dismiss() {
        operation?.cancel()
        mutableState.value = ProviderEditionPickerState(revision = mutableState.value.revision)
    }
}

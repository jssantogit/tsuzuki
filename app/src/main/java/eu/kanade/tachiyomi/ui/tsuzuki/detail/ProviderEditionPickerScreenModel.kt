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
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionRepository
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionChapterInspection
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionConfirmation
import tachiyomi.domain.tsuzuki.provider.reading.SelectMangaAllSource
import tachiyomi.domain.tsuzuki.provider.reading.SelectProviderReadingEdition

/** Foreground-only selection; candidate results are not persisted as chapter authority. */
data class ProviderEditionPickerState(
    val open: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val confirming: Boolean = false,
    val candidates: List<ProviderEditionCandidate> = emptyList(),
    val inspecting: Boolean = false,
    val inspectedRef: ProviderBindingRef? = null,
    val inspection: ProviderEditionChapterInspection? = null,
    val failed: Boolean = false,
    val confirmation: ProviderEditionConfirmation? = null,
    val sourceSelection: MangaAllSourceSelectionResult? = null,
    /** Set only by an explicit successful source+language user action. */
    val activeRequestedLanguage: String? = null,
    val revision: Int = 0,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ProviderEditionPickerScreenModel(
    private val selectEdition: SelectProviderReadingEdition,
    private val selectMangaAllSource: SelectMangaAllSource,
    private val sourceSelections: MangaAllSourceSelectionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderEditionPickerState())
    val state: StateFlow<ProviderEditionPickerState> = mutableState.asStateFlow()

    private var titleId: String? = null
    private var operation: Job? = null
    private var inspectionJob: Job? = null
    private var restoreJob: Job? = null

    fun start(canonicalTitleId: String) {
        if (titleId == canonicalTitleId) return
        operation?.cancel()
        inspectionJob?.cancel()
        restoreJob?.cancel()
        titleId = canonicalTitleId
        mutableState.value = ProviderEditionPickerState()
        restoreJob = viewModelScope.launch {
            val saved = try {
                sourceSelections.getLatest(canonicalTitleId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val current = mutableState.value
            if (
                titleId == canonicalTitleId &&
                current.activeRequestedLanguage == null &&
                current.sourceSelection == null &&
                current.confirmation == null
            ) {
                mutableState.value = current.copy(
                    activeRequestedLanguage = saved
                        ?.takeIf { it.canonicalTitleId == canonicalTitleId }
                        ?.ref?.facetId,
                )
            }
        }
    }

    /**
     * A Reader launch waits for persisted language restoration, so opening a
     * chapter during screen startup cannot quietly use the old default source.
     */
    suspend fun requestedLanguageForReader(canonicalTitleId: String): String? {
        restoreJob?.join()
        return if (titleId == canonicalTitleId) {
            mutableState.value.activeRequestedLanguage
        } else {
            null
        }
    }

    fun open(canonicalTitleId: String, displayTitle: String) {
        if (canonicalTitleId != titleId || displayTitle.isBlank()) return
        mutableState.value = mutableState.value.copy(open = true, confirmation = null, sourceSelection = null)
        search(displayTitle)
    }

    fun search(query: String) {
        val id = titleId ?: return
        if (!mutableState.value.open || mutableState.value.confirming) return
        operation?.cancel()
        inspectionJob?.cancel()
        mutableState.value = mutableState.value.copy(
            query = query,
            searching = true,
            candidates = emptyList(),
            inspecting = false,
            inspectedRef = null,
            inspection = null,
            failed = false,
            confirmation = null,
            sourceSelection = null,
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

    /**
     * Selecting a visible source starts a read-only, bounded chapter inventory
     * inspection. This is presentation metadata only: never a binding, canonical
     * chapter inventory or authorization to read a different edition.
     */
    fun inspect(candidate: ProviderEditionCandidate) {
        val current = mutableState.value
        if (!current.open || current.searching || current.confirming || candidate !in current.candidates) return
        if (current.inspectedRef == candidate.ref && (current.inspecting || current.inspection != null)) return
        inspectionJob?.cancel()
        mutableState.value = current.copy(inspecting = true, inspectedRef = candidate.ref, inspection = null)
        inspectionJob = viewModelScope.launch {
            val result = try {
                selectEdition.inspect(candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ProviderEditionChapterInspection(observedRecords = 0, complete = false, failed = true)
            }
            val latest = mutableState.value
            if (latest.open && latest.inspectedRef == candidate.ref && candidate in latest.candidates) {
                mutableState.value = latest.copy(inspecting = false, inspection = result)
            }
        }
    }

    fun confirm(candidate: ProviderEditionCandidate) {
        val id = titleId ?: return
        val current = mutableState.value
        if (!current.open || current.searching || current.confirming || candidate !in current.candidates) return
        restoreJob?.cancel()
        operation?.cancel()
        inspectionJob?.cancel()
        mutableState.value = current.copy(confirming = true, inspecting = false, confirmation = null)
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
                        activeRequestedLanguage = null,
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

    /**
     * An explicit source/translation preference, NOT edition confirmation.
     * The candidate must still be visible and its exact work associated with
     * this canonical title. No preferred binding, progress or chapter is written.
     */
    fun selectMangaAllSource(candidate: ProviderEditionCandidate) {
        val id = titleId ?: return
        val current = mutableState.value
        if (
            !current.open || current.searching || current.confirming ||
            candidate !in current.candidates ||
            candidate.ref.providerId.value != "app.tsuzuki.mangaall"
        ) {
            return
        }
        restoreJob?.cancel()
        operation?.cancel()
        inspectionJob?.cancel()
        // If changing a source fails, do not reopen the old language silently.
        mutableState.value = current.copy(
            confirming = true,
            sourceSelection = null,
            confirmation = null,
            activeRequestedLanguage = null,
        )
        operation = viewModelScope.launch {
            try {
                val result = selectMangaAllSource.select(
                    canonicalTitleId = id,
                    ref = candidate.ref,
                    at = System.currentTimeMillis().coerceAtLeast(0L),
                )
                if (id != titleId || !mutableState.value.open) return@launch
                mutableState.value = mutableState.value.copy(
                    confirming = false,
                    sourceSelection = result,
                    activeRequestedLanguage = if (
                        result == MangaAllSourceSelectionResult.SELECTED ||
                        result == MangaAllSourceSelectionResult.ALREADY_SELECTED
                    ) {
                        candidate.ref.facetId
                    } else {
                        null
                    },
                    revision = if (
                        result == MangaAllSourceSelectionResult.SELECTED ||
                        result == MangaAllSourceSelectionResult.ALREADY_SELECTED
                    ) {
                        mutableState.value.revision + 1
                    } else {
                        mutableState.value.revision
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (id == titleId && mutableState.value.open) {
                    mutableState.value = mutableState.value.copy(
                        confirming = false,
                        sourceSelection = MangaAllSourceSelectionResult.UNAVAILABLE,
                    )
                }
            }
        }
    }

    fun dismiss() {
        operation?.cancel()
        inspectionJob?.cancel()
        mutableState.value = ProviderEditionPickerState(
            revision = mutableState.value.revision,
            activeRequestedLanguage = mutableState.value.activeRequestedLanguage,
        )
    }
}

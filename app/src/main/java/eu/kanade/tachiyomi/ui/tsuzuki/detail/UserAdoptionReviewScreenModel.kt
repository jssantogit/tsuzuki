package eu.kanade.tachiyomi.ui.tsuzuki.detail

import androidx.lifecycle.ViewModel
import eu.kanade.tachiyomi.data.tsuzuki.inventory.SelectedEpub3ArchiveImport
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionBlockReason
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionCandidatePage
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionConfirmation
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionPreview
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionReviewRequest
import java.io.File
import java.io.InputStream

data class UserAdoptionReviewState(
    val importingEpub: Boolean = false,
    val epubImportFailed: Boolean = false,
    val epubImported: Boolean = false,
    val candidateScope: InventoryScope? = null,
    val candidatePage: UserAdoptionCandidatePage? = null,
    val loadingCandidates: Boolean = false,
    val candidateFailed: Boolean = false,
    val candidatePageIndex: Int = 0,
    val preparing: Boolean = false,
    val plan: UserAdoptionPreview.Ready? = null,
    val blockedReason: UserAdoptionBlockReason? = null,
    val adopting: Boolean = false,
    val failed: Boolean = false,
    val completed: Boolean = false,
    val revision: Int = 0,
)

/** No passive refresh: every selection and confirmation is a foreground user action. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class UserAdoptionReviewScreenModel(
    private val repository: UserAdoptionRepository,
    private val ledger: InventoryEvidenceRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UserAdoptionReviewState())
    val state: StateFlow<UserAdoptionReviewState> = mutableState.asStateFlow()
    private var titleId: String? = null
    private var activeReview: UserAdoptionReviewRequest? = null
    private var operation: Job? = null
    private var candidateCursors = mutableListOf<String?>(null)

    fun start(canonicalTitleId: String) {
        if (titleId == canonicalTitleId) return
        operation?.cancel()
        titleId = canonicalTitleId
        activeReview = null
        candidateCursors = mutableListOf(null)
        mutableState.value = UserAdoptionReviewState()
    }


    /**
     * A foreground SAF selection is the only entry point; no passive scan,
     * archive persistence, or automatic chapter materialization occurs.
     */
    fun importSelectedEpub(
        canonicalTitleId: String,
        cacheDirectory: File,
        openStream: () -> InputStream?,
    ) {
        if (canonicalTitleId != titleId || operation?.isActive == true) return
        mutableState.value = mutableState.value.copy(
            importingEpub = true,
            epubImportFailed = false,
            epubImported = false,
        )
        operation = viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) {
                    val selected = openStream() ?: error("Selected EPUB cannot be opened")
                    SelectedEpub3ArchiveImport.read(
                        stream = selected,
                        cacheDirectory = cacheDirectory,
                        canonicalTitleId = canonicalTitleId,
                        observedAt = System.currentTimeMillis(),
                    ).getOrThrow()
                }
                currentCoroutineContext().ensureActive()
                if (canonicalTitleId != titleId) return@launch
                ledger.saveBatch(batch)
                currentCoroutineContext().ensureActive()
                if (canonicalTitleId == titleId) {
                    mutableState.value = mutableState.value.copy(
                        importingEpub = false,
                        epubImported = true,
                        revision = mutableState.value.revision + 1,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (canonicalTitleId == titleId) {
                    mutableState.value = mutableState.value.copy(
                        importingEpub = false,
                        epubImportFailed = true,
                    )
                }
            }
        }
    }

    fun clearEpubImportResult() {
        if (mutableState.value.importingEpub) return
        mutableState.value = mutableState.value.copy(
            epubImported = false,
            epubImportFailed = false,
        )
    }

    /** Explicit user action. Pages never trigger network fetches or adoption writes. */
    fun browseCandidates(scope: InventoryScope) {
        if (
            scope.canonicalTitleId != titleId ||
            scope.editionKey.isNullOrBlank() ||
            scope.language.isNullOrBlank() ||
            mutableState.value.adopting
        ) {
            return
        }
        operation?.cancel()
        activeReview = null
        candidateCursors = mutableListOf(null)
        mutableState.value = UserAdoptionReviewState(
            candidateScope = scope,
            revision = mutableState.value.revision,
        )
        loadCandidatePage(scope, null, 0)
    }

    fun nextCandidatePage() {
        val current = mutableState.value
        val scope = current.candidateScope ?: return
        val cursor = current.candidatePage?.nextCursor ?: return
        if (current.loadingCandidates) return
        loadCandidatePage(scope, cursor, current.candidatePageIndex + 1)
    }

    fun previousCandidatePage() {
        val current = mutableState.value
        val scope = current.candidateScope ?: return
        if (current.loadingCandidates || current.candidatePageIndex < 1) return
        loadCandidatePage(scope, candidateCursors[current.candidatePageIndex - 1], current.candidatePageIndex - 1)
    }

    private fun loadCandidatePage(scope: InventoryScope, cursor: String?, pageIndex: Int) {
        if (titleId != scope.canonicalTitleId || mutableState.value.candidateScope != scope) return
        if (operation?.isActive == true) return
        mutableState.value = mutableState.value.copy(loadingCandidates = true, candidateFailed = false)
        operation = viewModelScope.launch {
            try {
                val page = repository.candidatePage(scope, cursor)
                if (titleId != scope.canonicalTitleId || mutableState.value.candidateScope != scope) return@launch
                if (pageIndex < candidateCursors.size) {
                    candidateCursors[pageIndex] = cursor
                } else {
                    candidateCursors.add(cursor)
                }
                mutableState.value = mutableState.value.copy(
                    candidatePage = page,
                    candidatePageIndex = pageIndex,
                    loadingCandidates = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (mutableState.value.candidateScope == scope) {
                    mutableState.value = mutableState.value.copy(
                        loadingCandidates = false,
                        candidateFailed = true,
                    )
                }
            }
        }
    }

    fun dismissCandidates() {
        if (mutableState.value.candidateScope == null) return
        operation?.cancel()
        candidateCursors = mutableListOf(null)
        mutableState.value = mutableState.value.copy(
            candidateScope = null,
            candidatePage = null,
            loadingCandidates = false,
            candidateFailed = false,
            candidatePageIndex = 0,
        )
    }

    fun preview(scope: InventoryScope, sourceIds: List<String>) {
        if (scope.canonicalTitleId != titleId || operation?.isActive == true) return
        activeReview = null
        mutableState.value = mutableState.value.copy(
            preparing = true,
            plan = null,
            blockedReason = null,
            failed = false,
            completed = false,
        )
        operation = viewModelScope.launch {
            try {
                val request = UserAdoptionReviewRequest(scope, sourceIds)
                when (val result = repository.preview(request)) {
                    is UserAdoptionPreview.Ready -> {
                        if (titleId != scope.canonicalTitleId) return@launch
                        activeReview = request
                        mutableState.value = mutableState.value.copy(preparing = false, plan = result)
                    }
                    is UserAdoptionPreview.Blocked -> {
                        mutableState.value = mutableState.value.copy(
                            preparing = false,
                            blockedReason = result.reason,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(preparing = false, failed = true)
            }
        }
    }

    fun confirm(canonicalTitleId: String) {
        val plan = mutableState.value.plan ?: return
        val request = activeReview ?: return
        if (canonicalTitleId != titleId || request.scope != plan.scope || operation?.isActive == true) return
        mutableState.value = mutableState.value.copy(adopting = true, failed = false)
        operation = viewModelScope.launch {
            try {
                val accepted = repository.adopt(
                    UserAdoptionConfirmation(
                        request = request,
                        reviewedFingerprint = plan.fingerprint,
                        acceptedAt = System.currentTimeMillis(),
                        explicitlyConfirmed = true,
                    ),
                )
                if (titleId != canonicalTitleId) return@launch
                activeReview = null
                mutableState.value = when (accepted) {
                    is UserAdoptionCommitResult.Adopted -> mutableState.value.copy(
                        plan = null,
                        adopting = false,
                        completed = true,
                        revision = mutableState.value.revision + 1,
                    )
                    UserAdoptionCommitResult.Refused -> mutableState.value.copy(
                        plan = null,
                        adopting = false,
                        failed = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    plan = null,
                    adopting = false,
                    failed = true,
                )
            }
        }
    }

    fun dismiss() {
        if (mutableState.value.adopting) return
        operation?.cancel()
        activeReview = null
        candidateCursors = mutableListOf(null)
        mutableState.value = UserAdoptionReviewState(revision = mutableState.value.revision)
    }
}

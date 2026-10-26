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
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionBlockReason
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionConfirmation
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionPreview
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionReviewRequest

data class UserAdoptionReviewState(
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(UserAdoptionReviewState())
    val state: StateFlow<UserAdoptionReviewState> = mutableState.asStateFlow()
    private var titleId: String? = null
    private var activeReview: UserAdoptionReviewRequest? = null
    private var operation: Job? = null

    fun start(canonicalTitleId: String) {
        if (titleId == canonicalTitleId) return
        operation?.cancel()
        titleId = canonicalTitleId
        activeReview = null
        mutableState.value = UserAdoptionReviewState()
    }

    fun preview(scope: InventoryScope, sourceIds: List<String>) {
        if (scope.canonicalTitleId != titleId || operation?.isActive == true) return
        activeReview = null
        mutableState.value = mutableState.value.copy(
            preparing = true, plan = null, blockedReason = null, failed = false, completed = false,
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
                            preparing = false, blockedReason = result.reason,
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
                        plan = null, adopting = false, completed = true,
                        revision = mutableState.value.revision + 1,
                    )
                    UserAdoptionCommitResult.Refused -> mutableState.value.copy(
                        plan = null, adopting = false, failed = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(plan = null, adopting = false, failed = true)
            }
        }
    }

    fun dismiss() {
        if (mutableState.value.adopting) return
        operation?.cancel()
        activeReview = null
        mutableState.value = UserAdoptionReviewState(revision = mutableState.value.revision)
    }
}

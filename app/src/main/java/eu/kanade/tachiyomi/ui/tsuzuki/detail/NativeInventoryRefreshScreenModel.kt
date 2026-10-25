package eu.kanade.tachiyomi.ui.tsuzuki.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedCandidate
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaDexVerifiedWorkLinker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshStop
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshIndependentInventory
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

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
    private val mangaDexWorkLinker: MangaDexVerifiedWorkLinker,
    private val integrationRegistry: IntegrationRegistry,
) : ViewModel() {
    private data class MangaDexPermissionSnapshot(
        val titleId: String,
        val configurationFingerprint: String,
    )

    private val mutableState = MutableStateFlow(NativeInventoryRefreshState())
    val state: StateFlow<NativeInventoryRefreshState> = mutableState.asStateFlow()

    private var activeTitleId: String? = null
    private var operation: Job? = null
    private var linkOperation: Job? = null
    private var linkOperationGeneration = 0L
    private var linkPermissionSnapshot: MangaDexPermissionSnapshot? = null

    fun start(titleId: String) {
        if (activeTitleId == titleId) return
        invalidateLinkOperation()
        operation?.cancel()
        linkOperation?.cancel()
        activeTitleId = titleId
        linkPermissionSnapshot = null
        mutableState.value = NativeInventoryRefreshState()
    }

    fun refresh(titleId: String, producerId: String): Job? {
        if (titleId != activeTitleId || operation?.isActive == true) return null
        val producer = integrationRegistry.inventoryProducers().singleOrNull { it.producerId == producerId }
        if (producer == null) {
            mutableState.value = mutableState.value.copy(
                producerId = producerId,
                refreshing = false,
                lastStop = InventoryRefreshStop.DISABLED,
                revision = mutableState.value.revision + 1,
            )
            return null
        }
        val configurationFingerprint = integrationRegistry.configurationFingerprint()
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
                            integrationRegistry.configurationFingerprint() == configurationFingerprint &&
                            integrationRegistry.inventoryProducers().singleOrNull {
                                it.producerId == producerId
                            } === producer
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
        val permission = captureMangaDexPermission(titleId)
        if (permission == null) {
            rejectMangaDexLink()
            return null
        }
        val operationGeneration = nextLinkOperationGeneration()
        linkOperation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                linking = true,
                linkCandidate = null,
                linkFailed = false,
                linkCompleted = false,
            )
            val result = try {
                mangaDexWorkLinker.preview(titleId, mangaDexId.trim()) {
                    isMangaDexPermissionCurrent(permission, operationGeneration)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.failure(IllegalStateException("MangaDex work verification failed"))
            }
            currentCoroutineContext().ensureActive()
            val authorized = isMangaDexPermissionCurrent(permission, operationGeneration)
            val acceptedResult = if (authorized) {
                result
            } else {
                Result.failure(IllegalStateException("Inventory integration is no longer enabled"))
            }
            if (activeTitleId == titleId) {
                linkPermissionSnapshot = permission.takeIf { authorized && acceptedResult.isSuccess }
                mutableState.value = mutableState.value.copy(
                    linking = false,
                    linkCandidate = acceptedResult.getOrNull(),
                    linkFailed = acceptedResult.isFailure,
                )
            }
        }
        return linkOperation
    }

    /** Confirmation performs a fresh remote revalidation before saving a verified work ID. */
    fun confirmMangaDexLink(titleId: String): Job? {
        if (titleId != activeTitleId || linkOperation?.isActive == true || mutableState.value.refreshing) return null
        val candidate = mutableState.value.linkCandidate ?: return null
        val permission = linkPermissionSnapshot
        if (permission == null || !isMangaDexPermissionCurrent(permission)) {
            rejectMangaDexLink()
            return null
        }
        val operationGeneration = nextLinkOperationGeneration()
        linkOperation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(linking = true, linkFailed = false)
            val result = try {
                mangaDexWorkLinker.confirm(titleId, candidate) {
                    isMangaDexPermissionCurrent(permission, operationGeneration)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.failure(IllegalStateException("MangaDex work confirmation failed"))
            }
            currentCoroutineContext().ensureActive()
            val authorized = isMangaDexPermissionCurrent(permission, operationGeneration)
            val acceptedResult = if (authorized) {
                result
            } else {
                Result.failure(IllegalStateException("Inventory integration is no longer enabled"))
            }
            if (activeTitleId == titleId) {
                linkPermissionSnapshot = permission.takeIf { authorized && acceptedResult.isFailure }
                mutableState.value = mutableState.value.copy(
                    linking = false,
                    linkCandidate = if (acceptedResult.isSuccess) null else candidate,
                    linkFailed = acceptedResult.isFailure,
                    linkCompleted = acceptedResult.isSuccess,
                    revision = mutableState.value.revision + if (acceptedResult.isSuccess) 1 else 0,
                )
            }
        }
        return linkOperation
    }

    fun clearMangaDexLink() {
        invalidateLinkOperation()
        linkOperation?.cancel()
        linkPermissionSnapshot = null
        mutableState.value = mutableState.value.copy(
            linking = false,
            linkCandidate = null,
            linkFailed = false,
            linkCompleted = false,
        )
    }

    fun cancel() {
        invalidateLinkOperation()
        operation?.cancel()
        linkOperation?.cancel()
        linkPermissionSnapshot = null
        mutableState.value = mutableState.value.copy(
            refreshing = false,
            linking = false,
            linkCandidate = null,
            linkCompleted = false,
        )
    }

    private fun captureMangaDexPermission(titleId: String): MangaDexPermissionSnapshot? {
        val permission = MangaDexPermissionSnapshot(
            titleId = titleId,
            configurationFingerprint = integrationRegistry.configurationFingerprint(),
        )
        return permission.takeIf(::isMangaDexPermissionCurrent)
    }

    private fun isMangaDexPermissionCurrent(
        permission: MangaDexPermissionSnapshot,
        operationGeneration: Long? = null,
    ): Boolean =
        (operationGeneration == null || linkOperationGeneration == operationGeneration) &&
            activeTitleId == permission.titleId &&
            integrationRegistry.configurationFingerprint() == permission.configurationFingerprint &&
            integrationRegistry.inventoryProducers().singleOrNull { it.producerId == MANGADEX_PRODUCER_ID } != null

    private fun rejectMangaDexLink() {
        invalidateLinkOperation()
        linkPermissionSnapshot = null
        mutableState.value = mutableState.value.copy(
            linking = false,
            linkCandidate = null,
            linkFailed = true,
            linkCompleted = false,
        )
    }

    private fun nextLinkOperationGeneration(): Long = ++linkOperationGeneration

    private fun invalidateLinkOperation() {
        linkOperationGeneration++
    }

    private companion object {
        const val MANGADEX_PRODUCER_ID = "mangadex"
    }
}

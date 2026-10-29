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
import eu.kanade.tachiyomi.data.tsuzuki.inventory.MangaUpdatesVerifiedWorkLinker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchFailureReason
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshStop
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshIndependentInventory
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

enum class InventoryWorkDiscoveryStatus {
    SEARCHING,
    LINKED,
    NO_VERIFIED_MATCH,
    FAILED,
    REQUIRES_MANGADEX,
    INTERRUPTED,
}

data class NativeInventoryRefreshState(
    val discoveryStatus: Map<String, InventoryWorkDiscoveryStatus> = emptyMap(),
    val refreshStops: Map<String, InventoryRefreshStop> = emptyMap(),
    val refreshFailureReasons: Map<String, InventoryFetchFailureReason> = emptyMap(),
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
 * Inventory network access requires explicit integration/capability opt-in.
 * Opening an existing canonical title can trigger bounded metadata-only refresh;
 * configuration changes invalidate requests and prevent stale writes.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NativeInventoryRefreshScreenModel(
    private val refreshInventory: RefreshIndependentInventory,
    private val mangaDexWorkLinker: MangaDexVerifiedWorkLinker,
    private val mangaUpdatesWorkLinker: MangaUpdatesVerifiedWorkLinker,
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
    private var autoOperation: Job? = null
    private var integrationChangesObserver: Job? = null
    private var autoGeneration = 0L

    fun start(titleId: String) {
        if (activeTitleId == titleId) return
        invalidateLinkOperation()
        operation?.cancel()
        linkOperation?.cancel()
        autoGeneration++
        autoOperation?.cancel()
        integrationChangesObserver?.cancel()
        activeTitleId = titleId
        linkPermissionSnapshot = null
        mutableState.value = NativeInventoryRefreshState()
    }

    /**
     * Keep an open title in sync with explicit integration consent changes.
     * A settings fingerprint change invalidates in-flight requests, then
     * re-runs bounded work discovery. No network on a default-off registry.
     */
    fun observeIntegrationChanges(titleId: String): Job? {
        if (titleId != activeTitleId) return null
        integrationChangesObserver?.cancel()
        integrationChangesObserver = viewModelScope.launch {
            integrationRegistry.awaitReady()
            var previousFingerprint = integrationRegistry.configurationFingerprint()
            integrationRegistry.observeChanges().collect {
                if (titleId != activeTitleId) return@collect
                val currentFingerprint = integrationRegistry.configurationFingerprint()
                if (currentFingerprint == previousFingerprint) return@collect
                previousFingerprint = currentFingerprint
                autoGeneration++
                autoOperation?.cancel()
                mutableState.value = mutableState.value.copy(
                    refreshing = false,
                    discoveryStatus = emptyMap(),
                    refreshStops = emptyMap(),
                    refreshFailureReasons = emptyMap(),
                    lastStop = null,
                )
                discoverAndRefreshAutomatically(titleId)
            }
        }
        return integrationChangesObserver
    }

    /**
     * A title-open can enrich an explicitly opted-in inventory without any
     * manual UUID or refresh button. Never search when the integration is off;
     * provider-independent release evidence stays shadow-only.
     */
    fun discoverAndRefreshAutomatically(titleId: String): Job? {
        if (titleId != activeTitleId || autoOperation?.isActive == true) return null
        val generation = ++autoGeneration
        autoOperation = viewModelScope.launch {
            try {
                integrationRegistry.awaitReady()
                if (titleId != activeTitleId || generation != autoGeneration) return@launch
                val fingerprint = integrationRegistry.configurationFingerprint()
                val enabledProducers = integrationRegistry.inventoryProducers()
                if (enabledProducers.isEmpty()) return@launch
                val producerIds = enabledProducers.map { it.producerId }.toSet()
                mutableState.value = mutableState.value.copy(
                    refreshing = true,
                    lastStop = null,
                    discoveryStatus = buildMap {
                        if (MANGADEX_PRODUCER_ID in producerIds) {
                            put(MANGADEX_PRODUCER_ID, InventoryWorkDiscoveryStatus.SEARCHING)
                        }
                        if (MANGAUPDATES_PRODUCER_ID in producerIds) {
                            put(
                                MANGAUPDATES_PRODUCER_ID,
                                if (MANGADEX_PRODUCER_ID in producerIds) {
                                    InventoryWorkDiscoveryStatus.SEARCHING
                                } else {
                                    InventoryWorkDiscoveryStatus.REQUIRES_MANGADEX
                                },
                            )
                        }
                    },
                    refreshStops = emptyMap(),
                    refreshFailureReasons = emptyMap(),
                )
                val isCurrent: (String) -> Boolean = { id ->
                    activeTitleId == titleId &&
                        generation == autoGeneration &&
                        integrationRegistry.configurationFingerprint() == fingerprint &&
                        enabledProducers.singleOrNull { it.producerId == id }?.let { original ->
                            integrationRegistry.inventoryProducers()
                                .singleOrNull { it.producerId == id } === original
                        } == true
                }
                fun recordDiscovery(id: String, status: InventoryWorkDiscoveryStatus) {
                    mutableState.value = mutableState.value.copy(
                        discoveryStatus = mutableState.value.discoveryStatus + (id to status),
                    )
                }
                // Search terms only produce candidates. Persisting a work always
                // requires a crosswalk already verified for this canonical title.
                if (isCurrent(MANGADEX_PRODUCER_ID)) {
                    val found = mangaDexWorkLinker.discoverUnique(titleId) {
                        isCurrent(MANGADEX_PRODUCER_ID)
                    }
                    if (!isCurrent(MANGADEX_PRODUCER_ID)) return@launch
                    val candidate = found.getOrNull()
                    val status = when {
                        found.isFailure -> InventoryWorkDiscoveryStatus.FAILED
                        candidate == null -> InventoryWorkDiscoveryStatus.NO_VERIFIED_MATCH
                        else -> {
                            val linked = mangaDexWorkLinker.confirm(titleId, candidate) {
                                isCurrent(MANGADEX_PRODUCER_ID)
                            }
                            if (!isCurrent(MANGADEX_PRODUCER_ID)) return@launch
                            mutableState.value = mutableState.value.copy(
                                linkCompleted = linked.isSuccess,
                                linkFailed = linked.isFailure,
                            )
                            if (linked.isSuccess) {
                                InventoryWorkDiscoveryStatus.LINKED
                            } else {
                                InventoryWorkDiscoveryStatus.FAILED
                            }
                        }
                    }
                    recordDiscovery(MANGADEX_PRODUCER_ID, status)
                }
                // MU work identity is derived from a *verified* MangaDex link.
                // No title-only association can escape into canonical inventory.
                if (isCurrent(MANGAUPDATES_PRODUCER_ID) && isCurrent(MANGADEX_PRODUCER_ID)) {
                    val authorized = {
                        isCurrent(MANGAUPDATES_PRODUCER_ID) && isCurrent(MANGADEX_PRODUCER_ID)
                    }
                    val found = mangaUpdatesWorkLinker.discoverUnique(titleId, authorized)
                    if (!authorized()) return@launch
                    val candidate = found.getOrNull()
                    val status = when {
                        found.isFailure -> InventoryWorkDiscoveryStatus.FAILED
                        candidate == null -> InventoryWorkDiscoveryStatus.NO_VERIFIED_MATCH
                        else -> {
                            val linked = mangaUpdatesWorkLinker.confirm(titleId, candidate, authorized)
                            if (!authorized()) return@launch
                            mutableState.value = mutableState.value.copy(
                                linkCompleted = mutableState.value.linkCompleted || linked.isSuccess,
                                linkFailed = mutableState.value.linkFailed || linked.isFailure,
                            )
                            if (linked.isSuccess) {
                                InventoryWorkDiscoveryStatus.LINKED
                            } else {
                                InventoryWorkDiscoveryStatus.FAILED
                            }
                        }
                    }
                    recordDiscovery(MANGAUPDATES_PRODUCER_ID, status)
                }
                var mostRecentStop: InventoryRefreshStop? = null
                for (producer in enabledProducers) {
                    if (!isCurrent(producer.producerId)) return@launch
                    val result = refreshInventory.execute(
                        canonicalTitleId = titleId,
                        producer = producer,
                        maxPages = 3,
                        isEnabled = { isCurrent(producer.producerId) },
                    )
                    if (!isCurrent(producer.producerId)) return@launch
                    mutableState.value = mutableState.value.copy(
                        refreshStops = mutableState.value.refreshStops + (producer.producerId to result.stop),
                        refreshFailureReasons = mutableState.value.refreshFailureReasons.toMutableMap().apply {
                            val reason = result.failureReason
                            if (reason == null) {
                                remove(producer.producerId)
                            } else {
                                put(producer.producerId, reason)
                            }
                        },
                    )
                    if (result.stop != InventoryRefreshStop.NO_VERIFIED_WORK ||
                        mostRecentStop == null
                    ) {
                        mostRecentStop = result.stop
                    }
                }
                if (titleId == activeTitleId && generation == autoGeneration) {
                    mutableState.value = mutableState.value.copy(
                        refreshing = false,
                        lastStop = mostRecentStop,
                        revision = mutableState.value.revision + 1,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (titleId == activeTitleId && generation == autoGeneration) {
                    mutableState.value = mutableState.value.copy(
                        refreshing = false,
                        lastStop = InventoryRefreshStop.FAILED,
                        revision = mutableState.value.revision + 1,
                    )
                }
            } finally {
                // Mid-flight permission revocation and title switching must not
                // strand the loading state or publish a stale result.
                if (titleId == activeTitleId && generation == autoGeneration) {
                    mutableState.value = mutableState.value.copy(
                        refreshing = false,
                        discoveryStatus = mutableState.value.discoveryStatus.mapValues { (_, status) ->
                            if (status == InventoryWorkDiscoveryStatus.SEARCHING) {
                                InventoryWorkDiscoveryStatus.INTERRUPTED
                            } else {
                                status
                            }
                        },
                    )
                }
            }
        }
        return autoOperation
    }

    fun refresh(titleId: String, producerId: String): Job? {
        if (titleId != activeTitleId || operation?.isActive == true || autoOperation?.isActive == true) return null
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
                        refreshFailureReasons = mutableState.value.refreshFailureReasons.toMutableMap().apply {
                            val reason = result.failureReason
                            if (reason == null) {
                                remove(producerId)
                            } else {
                                put(producerId, reason)
                            }
                        },
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
                        refreshFailureReasons = mutableState.value.refreshFailureReasons +
                            (producerId to InventoryFetchFailureReason.UNKNOWN),
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
        autoGeneration++
        autoOperation?.cancel()
        integrationChangesObserver?.cancel()
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
        const val MANGAUPDATES_PRODUCER_ID = "mangaupdates"
    }
}

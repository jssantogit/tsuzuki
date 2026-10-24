package eu.kanade.tachiyomi.ui.tsuzuki.sync

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionResult
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.service.CloudSyncRuntime
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeState
import tachiyomi.domain.tsuzuki.sync.service.SyncTrigger

@Immutable
data class TsuzukiSyncScreenState(
    val accountState: AccountState = AccountState.LoggedOut,
    val diagnosticsAccountId: String? = null,
    val runtimeState: SyncRuntimeState = SyncRuntimeState.Idle,
    val pendingMutationCount: Int = 0,
    val lastSuccessfulSyncAtEpochMillis: Long? = null,
    val conflicts: List<StoredSyncConflict> = emptyList(),
    val isLoadingDiagnostics: Boolean = false,
    val resolvingConflictKey: String? = null,
    val error: TsuzukiSyncScreenError? = null,
) {
    val cloudEnabled: Boolean
        get() = (accountState as? AccountState.Authenticated)?.account?.id == diagnosticsAccountId &&
            diagnosticsAccountId != null

    val unresolvedConflictCount: Int
        get() = conflicts.size
}

enum class TsuzukiSyncScreenError {
    AUTHORIZATION_REQUIRED,
    ACCOUNT_SCOPE_MISMATCH,
    NETWORK_UNAVAILABLE,
    REMOTE_UNAVAILABLE,
    CONFLICT_REMAINS,
    UNKNOWN,
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiSyncScreenModel(
    private val accountRepository: AccountRepository,
    private val runtime: CloudSyncRuntime,
    private val stateStore: SupabaseSyncStateStore,
    private val conflictRepository: SyncConflictRepository,
) : ViewModel() {

    private val diagnostics = MutableStateFlow(SyncDiagnostics())
    private val isLoadingDiagnostics = MutableStateFlow(false)
    private val resolvingConflictKey = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<TsuzukiSyncScreenError?>(null)

    private val localUiState = combine(
        diagnostics,
        isLoadingDiagnostics,
        resolvingConflictKey,
        error,
    ) { currentDiagnostics, loading, resolving, currentError ->
        SyncLocalUiState(
            diagnostics = currentDiagnostics,
            isLoadingDiagnostics = loading,
            resolvingConflictKey = resolving,
            error = currentError,
        )
    }

    val state: StateFlow<TsuzukiSyncScreenState> = combine(
        accountRepository.state,
        runtime.state,
        localUiState,
    ) { account, runtimeState, local ->
        TsuzukiSyncScreenState(
            accountState = account,
            diagnosticsAccountId = local.diagnostics.accountId,
            runtimeState = runtimeState,
            pendingMutationCount = if ((account as? AccountState.Authenticated)?.account?.id ==
                local.diagnostics.accountId
            ) {
                local.diagnostics.pendingMutationCount
            } else {
                0
            },
            lastSuccessfulSyncAtEpochMillis = if (
                (account as? AccountState.Authenticated)?.account?.id == local.diagnostics.accountId
            ) {
                local.diagnostics.lastSuccessfulSyncAtEpochMillis
            } else {
                null
            },
            conflicts = if ((account as? AccountState.Authenticated)?.account?.id == local.diagnostics.accountId) {
                local.diagnostics.conflicts
            } else {
                emptyList()
            },
            isLoadingDiagnostics = local.isLoadingDiagnostics,
            resolvingConflictKey = local.resolvingConflictKey,
            error = local.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TsuzukiSyncScreenState(
            accountState = accountRepository.state.value,
            runtimeState = runtime.state.value,
        ),
    )

    init {
        refreshDiagnostics()
    }

    fun syncNow() {
        if (accountRepository.state.value !is AccountState.Authenticated) {
            error.value = TsuzukiSyncScreenError.AUTHORIZATION_REQUIRED
            return
        }
        if (!state.value.cloudEnabled) {
            error.value = TsuzukiSyncScreenError.ACCOUNT_SCOPE_MISMATCH
            return
        }

        viewModelScope.launch {
            error.value = null
            try {
                val report = runtime.run(SyncTrigger.MANUAL)
                report.globalFailure?.let { failure ->
                    error.value = failure.reason.toScreenError()
                }
                if (report.hasConflicts && error.value == null) {
                    error.value = TsuzukiSyncScreenError.CONFLICT_REMAINS
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                error.value = TsuzukiSyncScreenError.UNKNOWN
            } finally {
                loadDiagnostics()
            }
        }
    }

    fun resolveConflict(
        stored: StoredSyncConflict,
        choice: SyncConflictResolutionChoice,
    ) {
        if (!state.value.cloudEnabled || stored !in state.value.conflicts) {
            error.value = TsuzukiSyncScreenError.ACCOUNT_SCOPE_MISMATCH
            return
        }
        val key = stored.conflict.key()
        if (resolvingConflictKey.value != null) return

        viewModelScope.launch {
            resolvingConflictKey.value = key
            error.value = null
            try {
                when (val result = runtime.resolveConflict(stored.conflict, choice)) {
                    SyncConflictResolutionResult.Resolved -> Unit
                    is SyncConflictResolutionResult.StillConflicted -> {
                        error.value = TsuzukiSyncScreenError.CONFLICT_REMAINS
                    }
                    is SyncConflictResolutionResult.Failed -> {
                        error.value = result.failure.reason.toScreenError()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                error.value = TsuzukiSyncScreenError.UNKNOWN
            } finally {
                resolvingConflictKey.value = null
                loadDiagnostics()
            }
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            loadDiagnostics()
        }
    }

    fun clearError() {
        error.value = null
    }

    private suspend fun loadDiagnostics() {
        if (isLoadingDiagnostics.value) return
        isLoadingDiagnostics.value = true
        try {
            val currentAccountId = (accountRepository.state.value as? AccountState.Authenticated)
                ?.account?.id
            if (currentAccountId == null) {
                diagnostics.value = SyncDiagnostics()
                return
            }
            val ownerFailure = runtime.accountScopeFailure()
            if (ownerFailure != null) {
                diagnostics.value = SyncDiagnostics()
                if (ownerFailure.reason == SyncFailureReason.ACCOUNT_SCOPE_MISMATCH) {
                    error.value = TsuzukiSyncScreenError.ACCOUNT_SCOPE_MISMATCH
                }
                return
            }
            val kinds = SyncDocumentKind.supabaseDurableKinds
            val pending = kinds.count { stateStore.getPending(it) != null }
            val cursors = kinds.mapNotNull { stateStore.getCursor(it) }
            val conflicts = kinds.flatMap { conflictRepository.getForDocument(it) }

            if (error.value == TsuzukiSyncScreenError.ACCOUNT_SCOPE_MISMATCH) {
                error.value = null
            }
            diagnostics.value = SyncDiagnostics(
                accountId = currentAccountId,
                pendingMutationCount = pending,
                lastSuccessfulSyncAtEpochMillis = cursors
                    .mapNotNull { it.lastSuccessfulSyncAtEpochMillis }
                    .maxOrNull(),
                conflicts = conflicts.sortedWith(
                    compareBy<StoredSyncConflict>(
                        { it.conflict.documentKind.ordinal },
                        { it.conflict.recordId },
                        { it.conflict.propertyPath.joinToString("/") },
                    ),
                ),
            )
        } finally {
            isLoadingDiagnostics.value = false
        }
    }

    private fun SyncFailureReason.toScreenError(): TsuzukiSyncScreenError = when (this) {
        SyncFailureReason.AUTHORIZATION_REQUIRED ->
            TsuzukiSyncScreenError.AUTHORIZATION_REQUIRED
        SyncFailureReason.ACCOUNT_SCOPE_MISMATCH ->
            TsuzukiSyncScreenError.ACCOUNT_SCOPE_MISMATCH
        SyncFailureReason.NETWORK_UNAVAILABLE,
        SyncFailureReason.RATE_LIMITED,
        -> TsuzukiSyncScreenError.NETWORK_UNAVAILABLE
        SyncFailureReason.REMOTE_UNAVAILABLE,
        SyncFailureReason.REMOTE_ACCESS_DENIED,
        SyncFailureReason.REMOTE_NOT_FOUND,
        -> TsuzukiSyncScreenError.REMOTE_UNAVAILABLE
        else -> TsuzukiSyncScreenError.UNKNOWN
    }

    private fun tachiyomi.domain.tsuzuki.sync.model.SyncConflict.key(): String {
        return remoteConflictId?.let { "remote:$it" }
            ?: listOf(
                documentKind.name,
                recordId,
                propertyPath.joinToString("/"),
                kind.name,
            ).joinToString("|")
    }

    private data class SyncDiagnostics(
        val accountId: String? = null,
        val pendingMutationCount: Int = 0,
        val lastSuccessfulSyncAtEpochMillis: Long? = null,
        val conflicts: List<StoredSyncConflict> = emptyList(),
    )

    private data class SyncLocalUiState(
        val diagnostics: SyncDiagnostics,
        val isLoadingDiagnostics: Boolean,
        val resolvingConflictKey: String?,
        val error: TsuzukiSyncScreenError?,
    )
}

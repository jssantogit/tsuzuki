package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionResult
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport

/**
 * Runtime boundary for optional cloud sync surfaces.
 *
 * Local reading and library flows do not depend on an authenticated cloud session.
 * Cloud failures must never gate local reading or library use.
 */
interface CloudSyncRuntime {
    val state: StateFlow<SyncRuntimeState>

    /**
     * Read-only ownership preflight for UI diagnostics. A cloud state from
     * another or unknown account must not be rendered as the current account's.
     */
    suspend fun accountScopeFailure(): tachiyomi.domain.tsuzuki.sync.model.SyncFailure? = null

    suspend fun run(trigger: SyncTrigger): SyncCycleReport

    suspend fun resolveConflict(
        conflict: SyncConflict,
        choice: SyncConflictResolutionChoice,
    ): SyncConflictResolutionResult
}

package tachiyomi.domain.tsuzuki.sync.interactor

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter

/**
 * A read-only, device-local hazard inspection before a future progress cutover.
 *
 * This is NOT a migration gate or an atomic snapshot: concurrent local writes,
 * other installations and their offline outboxes are not observable here.
 * Neither an empty set of risks nor a cursor matching the server can enable v2.
 */
class InspectLocalProgressCutover(
    private val outboxRepository: SyncOutboxRepository,
    private val stateStore: SupabaseSyncStateStore,
    private val stateRepository: SyncStateRepository,
    private val conflictRepository: SyncConflictRepository,
    private val readingAdapter: SyncDocumentAdapter,
) {
    init {
        require(readingAdapter.documentKind == SyncDocumentKind.READING_PROGRESS) {
            "The local cutover inventory must use the legacy reading adapter"
        }
    }

    suspend fun inspect(): LocalProgressCutoverInspection = try {
        val legacy = SyncDocumentKind.READING_PROGRESS
        val exact = SyncDocumentKind.READING_PROGRESS_V2
        val outbox = outboxRepository.get(legacy)
        val pending = stateStore.getPending(legacy)
        val experimentalOutbox = outboxRepository.get(exact)
        val experimentalPending = stateStore.getPending(exact)
        val cursor = stateStore.getCursor(legacy)
        val acceptedBase = stateRepository.get(legacy)?.acceptedBase
        val conflicts = conflictRepository.getForDocument(legacy)
        val unportable = readingAdapter.hasUnportableLocalState()

        val risks = buildSet {
            if (pending != null) add(LocalProgressCutoverRisk.PENDING_LEGACY_MUTATION)
            if (outbox != null) add(LocalProgressCutoverRisk.DIRTY_LEGACY_OUTBOX)
            if (experimentalOutbox != null || experimentalPending != null) {
                add(LocalProgressCutoverRisk.EXPERIMENTAL_V2_LOCAL_STATE)
            }
            if (cursor == null) add(LocalProgressCutoverRisk.MISSING_LEGACY_CURSOR)
            if (acceptedBase == null) {
                add(LocalProgressCutoverRisk.MISSING_ACCEPTED_BASE)
            } else if (
                acceptedBase.schemaVersion != 1 ||
                acceptedBase.kind != legacy
            ) {
                add(LocalProgressCutoverRisk.UNSUPPORTED_ACCEPTED_BASE)
            }
            if (conflicts.isNotEmpty()) add(LocalProgressCutoverRisk.UNRESOLVED_LEGACY_CONFLICTS)
            if (unportable) add(LocalProgressCutoverRisk.UNPORTABLE_LOCAL_PROGRESS)
        }

        LocalProgressCutoverInspection(
            observedLegacyCursor = cursor?.eventCursor,
            risks = risks,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Corrupted SQLite payloads, partial reads or absent storage cannot
        // be interpreted as an empty outbox, cursor or conflict inventory.
        LocalProgressCutoverInspection(
            observedLegacyCursor = null,
            risks = setOf(LocalProgressCutoverRisk.INSPECTION_FAILED),
        )
    }
}

enum class LocalProgressCutoverRisk {
    PENDING_LEGACY_MUTATION,
    DIRTY_LEGACY_OUTBOX,
    EXPERIMENTAL_V2_LOCAL_STATE,
    UNRESOLVED_LEGACY_CONFLICTS,
    UNPORTABLE_LOCAL_PROGRESS,
    MISSING_LEGACY_CURSOR,
    MISSING_ACCEPTED_BASE,
    UNSUPPORTED_ACCEPTED_BASE,
    INSPECTION_FAILED,
}

/**
 * Descriptive evidence only, not approval, lease, receipt or proof of quiescence.
 * A locally empty outbox says nothing about disconnected clients or races.
 */
data class LocalProgressCutoverInspection(
    val observedLegacyCursor: Long?,
    val risks: Set<LocalProgressCutoverRisk>,
) {
    val migrationReady: Boolean get() = false
    val historicalDeviceCoverageProven: Boolean get() = false
    val remotePendingWritesObservable: Boolean get() = false
}

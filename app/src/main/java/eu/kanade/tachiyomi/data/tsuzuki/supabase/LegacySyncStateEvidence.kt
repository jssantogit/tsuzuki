package eu.kanade.tachiyomi.data.tsuzuki.supabase

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind

/**
 * A missing account-owner marker may only be initialized on a genuinely clean
 * installation. A dirty outbox without a remote cursor is still user data and
 * cannot be attributed to whoever signs in next.
 */
internal class LegacySyncStateEvidence(
    private val hasCursor: suspend (SyncDocumentKind) -> Boolean,
    private val hasPending: suspend (SyncDocumentKind) -> Boolean,
    private val hasAcceptedBase: suspend (SyncDocumentKind) -> Boolean,
    private val hasConflict: suspend (SyncDocumentKind) -> Boolean,
    private val hasOutbox: suspend (SyncDocumentKind) -> Boolean,
) {
    suspend fun hasUnscopedState(): Boolean =
        SyncDocumentKind.supabaseDurableKinds.any { kind ->
            hasCursor(kind) ||
                hasPending(kind) ||
                hasAcceptedBase(kind) ||
                hasConflict(kind) ||
                hasOutbox(kind)
        }
}

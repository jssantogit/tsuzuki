package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.tsuzuki.sync.interactor.InspectLocalProgressCutover
import tachiyomi.domain.tsuzuki.sync.interactor.LocalProgressCutoverRisk
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SupabasePendingMutation
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncCursor
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter

class InspectLocalProgressCutoverTest {
    private val kind = SyncDocumentKind.READING_PROGRESS
    private val exactKind = SyncDocumentKind.READING_PROGRESS_V2

    @Test
    fun `pending retries and dirty outbox block local cutover even when backoff is in future`() = runTest {
        val fixture = Fixture()
        fixture.pending = pending()
        fixture.outbox = SyncOutboxEntry(kind, 5, nextAttemptAtEpochMillis = 100000)
        val result = fixture.inspect()

        result.risks shouldBe setOf(
            LocalProgressCutoverRisk.PENDING_LEGACY_MUTATION,
            LocalProgressCutoverRisk.DIRTY_LEGACY_OUTBOX,
        )
        result.observedLegacyCursor shouldBe 42L
        result.migrationReady shouldBe false
        fixture.writes shouldBe 0
    }

    @Test
    fun `experimental v2 leftovers are visible without replaying them`() = runTest {
        val fixture = Fixture()
        fixture.pendingV2 = pending(exactKind)
        fixture.outboxV2 = SyncOutboxEntry(exactKind, 7)
        val result = fixture.inspect()

        result.risks shouldBe setOf(LocalProgressCutoverRisk.EXPERIMENTAL_V2_LOCAL_STATE)
        result.migrationReady shouldBe false
        fixture.writes shouldBe 0
    }

    @Test
    fun `missing local cursor and accepted base cannot appear safe`() = runTest {
        val fixture = Fixture()
        fixture.cursor = null
        fixture.accepted = null
        val result = fixture.inspect()

        result.risks shouldBe setOf(
            LocalProgressCutoverRisk.MISSING_LEGACY_CURSOR,
            LocalProgressCutoverRisk.MISSING_ACCEPTED_BASE,
        )
        result.observedLegacyCursor shouldBe null
        result.migrationReady shouldBe false
    }

    @Test
    fun `conflicts unportable chapters and incompatible base are explicit blockers`() = runTest {
        val fixture = Fixture()
        fixture.unportable = true
        fixture.accepted = fixture.accepted!!.copy(
            acceptedBase = fixture.document.copy(schemaVersion = 2),
        )
        fixture.conflicts = listOf(
            StoredSyncConflict(
                conflict = SyncConflict(
                    documentKind = kind,
                    recordId = "chapter",
                    propertyPath = listOf("read"),
                    kind = SyncConflictKind.FIELD_DIVERGENCE,
                    base = SyncConflictValue.Missing,
                    local = SyncConflictValue.Missing,
                    remote = SyncConflictValue.Missing,
                ),
                createdAtEpochMillis = 1,
            ),
        )
        val result = fixture.inspect()

        result.risks shouldBe setOf(
            LocalProgressCutoverRisk.UNRESOLVED_LEGACY_CONFLICTS,
            LocalProgressCutoverRisk.UNPORTABLE_LOCAL_PROGRESS,
            LocalProgressCutoverRisk.UNSUPPORTED_ACCEPTED_BASE,
        )
        result.migrationReady shouldBe false
    }

    @Test
    fun `even a locally clean inventory is never remote migration approval`() = runTest {
        val fixture = Fixture()
        val result = fixture.inspect()

        result.risks shouldBe emptySet()
        result.observedLegacyCursor shouldBe 42L
        result.migrationReady shouldBe false
        result.historicalDeviceCoverageProven shouldBe false
        result.remotePendingWritesObservable shouldBe false
        fixture.writes shouldBe 0
    }

    @Test
    fun `a repository failure cannot be interpreted as an empty offline outbox`() = runTest {
        val fixture = Fixture()
        fixture.failRead = true
        val result = fixture.inspect()

        result.risks shouldBe setOf(LocalProgressCutoverRisk.INSPECTION_FAILED)
        result.migrationReady shouldBe false
        fixture.writes shouldBe 0
    }

    @Test
    fun `coroutine cancellation propagates rather than becoming a benign inventory`() = runTest {
        val fixture = Fixture()
        fixture.cancelRead = true
        assertThrows<CancellationException> { fixture.inspect() }
    }

    private fun pending(documentKind: SyncDocumentKind = kind) = SupabasePendingMutation(
        batch = SupabaseMutationBatch(
            mutationId = "mutation",
            originClientId = "client",
            domain = documentKind.name,
            baseCursor = 0,
            operations = listOf(SyncMutation.RemoveField("chapter", listOf("read"), 1)),
        ),
        createdAtEpochMillis = 5,
    )

    private inner class Fixture {
        val document = SyncDocumentEnvelope(
            schemaVersion = 1,
            kind = kind,
            revision = SyncRevision("device", 1),
            generatedAtEpochMillis = 10,
            records = emptyMap(),
        )
        var accepted: SyncStoredState? = SyncStoredState(
            documentKind = kind,
            acceptedBase = document,
            remoteRevision = null,
            lastSuccessfulSyncAtEpochMillis = 10,
        )
        var cursor: SupabaseSyncCursor? = SupabaseSyncCursor(kind, 42, 10)
        var pending: SupabasePendingMutation? = null
        var pendingV2: SupabasePendingMutation? = null
        var outbox: SyncOutboxEntry? = null
        var outboxV2: SyncOutboxEntry? = null
        var conflicts: List<StoredSyncConflict> = emptyList()
        var unportable = false
        var failRead = false
        var cancelRead = false
        var writes = 0

        val outboxStore = object : SyncOutboxRepository {
            override suspend fun get(documentKind: SyncDocumentKind): SyncOutboxEntry? {
                if (cancelRead) throw CancellationException("cancelled")
                if (failRead) error("corrupt local data")
                return if (documentKind == kind) outbox else outboxV2
            }
            override suspend fun markDirty(documentKind: SyncDocumentKind, enqueuedAtEpochMillis: Long) { writes++ }
            override suspend fun getPending(nowEpochMillis: Long, limit: Int) = emptyList<SyncOutboxEntry>()
            override suspend fun recordFailure(documentKind: SyncDocumentKind, nextAttemptAtEpochMillis: Long?) { writes++ }
            override suspend fun clear(documentKind: SyncDocumentKind) { writes++ }
        }
        val stateStore = object : SupabaseSyncStateStore {
            override suspend fun getCursor(documentKind: SyncDocumentKind) = cursor
            override suspend fun putCursor(documentKind: SyncDocumentKind, eventCursor: Long, lastSuccessfulSyncAtEpochMillis: Long?) { writes++ }
            override suspend fun getPending(documentKind: SyncDocumentKind): SupabasePendingMutation? =
                if (documentKind == kind) pending else pendingV2
            override suspend fun putPending(mutation: SupabasePendingMutation) { writes++ }
            override suspend fun recordPendingFailure(mutation: SupabasePendingMutation, nextAttemptAtEpochMillis: Long?) { writes++ }
            override suspend fun deletePending(mutationId: String) { writes++ }
        }
        val stateRepository = object : SyncStateRepository {
            override suspend fun get(documentKind: SyncDocumentKind): SyncStoredState? = accepted
            override suspend fun put(state: SyncStoredState) { writes++ }
            override suspend fun clear(documentKind: SyncDocumentKind) { writes++ }
        }
        val conflictRepository = object : SyncConflictRepository {
            override suspend fun getForDocument(documentKind: SyncDocumentKind) = conflicts
            override suspend fun replaceForDocument(
                documentKind: SyncDocumentKind,
                conflicts: List<SyncConflict>,
                createdAtEpochMillis: Long,
            ) { writes++ }
            override suspend fun clearForDocument(documentKind: SyncDocumentKind) { writes++ }
        }
        val adapter = object : SyncDocumentAdapter {
            override val documentKind = kind
            override suspend fun exportDocument() = document
            override suspend fun applyDocument(document: SyncDocumentEnvelope) { writes++ }
            override suspend fun hasUnportableLocalState() = unportable
        }
        suspend fun inspect() = InspectLocalProgressCutover(
            outboxRepository = outboxStore,
            stateStore = stateStore,
            stateRepository = stateRepository,
            conflictRepository = conflictRepository,
            readingAdapter = adapter,
        ).inspect()
    }
}

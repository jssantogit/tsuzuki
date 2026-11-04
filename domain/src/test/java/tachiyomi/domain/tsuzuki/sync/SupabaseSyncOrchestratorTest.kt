package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabasePendingMutation
import tachiyomi.domain.tsuzuki.sync.model.SupabasePushResult
import tachiyomi.domain.tsuzuki.sync.model.SupabaseRemoteConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncCursor
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncEvent
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncOperation
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshot
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshotRecord
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.CanonicalIdentityClaimTransport
import tachiyomi.domain.tsuzuki.sync.service.CanonicalIdentitySyncRepository
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleMergePort
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncOrchestrator
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncTransport
import tachiyomi.domain.tsuzuki.sync.service.SyncClientIdentityProvider
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.VerifiedCanonicalIdentity

class SupabaseSyncOrchestratorTest {

    @Test
    fun `unportable edition-owned progress stops v1 sync before network and local tombstone generation`() =
        runTest {
            val kind = SyncDocumentKind.READING_PROGRESS
            val base = document(status = "READING").copy(kind = kind)
            val local = base.copy(records = emptyMap())
            val adapter = FakeAdapter(local, unportable = true)
            val stored = FakeStoredState().apply {
                put(SyncStoredState(kind, base, null, 1L))
            }
            val cloud = FakeCloudState().apply {
                putCursor(kind, 3L, 1L)
            }
            val remote = FakeTransport()
            val outbox = FakeOutbox().apply { markDirty(kind, 1L) }

            val result = orchestrator(
                adapter = adapter,
                storedState = stored,
                cloudState = cloud,
                transport = remote,
                outbox = outbox,
            ).sync(kind)

            (result as SyncDocumentResult.Failed).failure.reason shouldBe
                SyncFailureReason.LOCAL_STATE_UNAVAILABLE
            remote.networkCalls shouldBe 0
            remote.pushedBatches shouldBe emptyList()
            cloud.getCursor(kind)?.eventCursor shouldBe 3L
            outbox.get(kind) shouldNotBe null
            adapter.current shouldBe local
        }

    @Test
    fun `missing v1 progress record is not converted to a remote deletion tombstone`() = runTest {
        val kind = SyncDocumentKind.READING_PROGRESS
        val base = document(status = "READING").copy(kind = kind)
        val local = base.copy(records = emptyMap())
        val adapter = FakeAdapter(local)
        val stored = FakeStoredState().apply {
            put(SyncStoredState(kind, base, null, 1L))
        }
        val cloud = FakeCloudState().apply {
            putCursor(kind, 3L, 1L)
        }
        val remote = FakeTransport().apply {
            snapshotValue = SupabaseSyncSnapshot(
                documentKind = kind,
                cursor = 3L,
                records = listOf(
                    SupabaseSyncSnapshotRecord(
                        recordId = "title-1",
                        isDeleted = false,
                        fields = base.records.getValue("title-1").fields,
                    ),
                ),
            )
        }

        orchestrator(
            adapter = adapter,
            storedState = stored,
            cloudState = cloud,
            transport = remote,
        ).sync(kind)

        remote.pushedBatches.flatMap { it.operations }.none {
            it is SyncMutation.DeleteRecord && it.recordId == "title-1"
        } shouldBe true
    }

    @Test
    fun `unnegotiated v2 schema never contacts legacy Supabase sync transport`() = runTest {
        val local = document(status = "READING").copy(schemaVersion = 2)
        val adapter = FakeAdapter(local)
        val transport = FakeTransport()
        val outbox = FakeOutbox().apply { markDirty(SyncDocumentKind.LIBRARY, 1) }
        val orchestrator = orchestrator(
            adapter = adapter,
            outbox = outbox,
            transport = transport,
        )

        val result = orchestrator.sync(SyncDocumentKind.LIBRARY)

        (result as SyncDocumentResult.Failed).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        adapter.current shouldBe local
        transport.networkCalls shouldBe 0
        outbox.get(SyncDocumentKind.LIBRARY) shouldNotBe null
    }

    @Test
    fun `unnegotiated v2 progress adapter cannot sync despite isolated namespace`() = runTest {
        val v2 = SyncDocumentKind.READING_PROGRESS_V2
        val local = document(status = "READING").copy(
            kind = v2,
            schemaVersion = 2,
        )
        val adapter = FakeAdapter(local)
        val transport = FakeTransport()
        val outbox = FakeOutbox().apply { markDirty(v2, 1) }

        val result = orchestrator(
            adapter = adapter,
            outbox = outbox,
            transport = transport,
        ).sync(v2)

        (result as SyncDocumentResult.Failed).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        transport.networkCalls shouldBe 0
        transport.pushedBatches.isEmpty() shouldBe true
        adapter.current shouldBe local
        outbox.get(v2) shouldNotBe null
    }

    @Test
    fun `background sync cannot publish v2 progress without server negotiation`() = runTest {
        val v2 = SyncDocumentKind.READING_PROGRESS_V2
        val local = document(status = "READING").copy(
            kind = v2,
            schemaVersion = 2,
        )
        val adapter = FakeAdapter(local)
        val transport = FakeTransport()
        val outbox = FakeOutbox().apply { markDirty(v2, 1) }

        val report = orchestrator(
            adapter = adapter,
            outbox = outbox,
            transport = transport,
        ).runOnce()

        report.documentResults.single().let {
            (it as SyncDocumentResult.Failed).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        }
        transport.networkCalls shouldBe 0
        transport.pushedBatches.isEmpty() shouldBe true
        adapter.current shouldBe local
        outbox.get(v2) shouldNotBe null
    }

    @Test
    fun `stored schema mismatch refuses sync before pulling or applying remote records`() = runTest {
        val local = document(status = "READING")
        val adapter = FakeAdapter(local)
        val stored = FakeStoredState().apply {
            put(
                SyncStoredState(
                    documentKind = SyncDocumentKind.LIBRARY,
                    acceptedBase = document(status = "OLD").copy(schemaVersion = 2),
                    remoteRevision = null,
                    lastSuccessfulSyncAtEpochMillis = 1,
                ),
            )
        }
        val cloud = FakeCloudState().apply {
            putCursor(
                documentKind = SyncDocumentKind.LIBRARY,
                eventCursor = 5,
                lastSuccessfulSyncAtEpochMillis = 1,
            )
        }
        val transport = FakeTransport()
        val result = orchestrator(
            adapter = adapter,
            storedState = stored,
            cloudState = cloud,
            transport = transport,
        ).sync(SyncDocumentKind.LIBRARY)

        (result as SyncDocumentResult.Failed).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        adapter.current shouldBe local
        transport.networkCalls shouldBe 0
        cloud.getCursor(SyncDocumentKind.LIBRARY)?.eventCursor shouldBe 5L
    }

    @Test
    fun `timeout after server apply reuses persisted mutation id on retry`() = runTest {
        val adapter = FakeAdapter(document(status = "READING"))
        val outbox = FakeOutbox().apply { markDirty(SyncDocumentKind.LIBRARY, 1) }
        val cloudState = FakeCloudState()
        val transport = FakeTransport().apply {
            pushOutcomes += PushOutcome.AppliedResponseLost(
                cursor = 12,
                events = listOf(
                    event(
                        id = 12,
                        recordId = "title-1",
                        fieldPath = "status",
                        value = JsonPrimitive("READING"),
                    ),
                ),
            )
            pushOutcomes += PushOutcome.Success(cursor = 12)
        }
        val orchestrator = orchestrator(
            adapter = adapter,
            outbox = outbox,
            cloudState = cloudState,
            transport = transport,
        )

        orchestrator.sync(SyncDocumentKind.LIBRARY)

        cloudState.getPending(SyncDocumentKind.LIBRARY)?.attemptCount shouldBe 1
        outbox.get(SyncDocumentKind.LIBRARY) shouldNotBe null

        orchestrator.sync(SyncDocumentKind.LIBRARY)

        cloudState.getPending(SyncDocumentKind.LIBRARY) shouldBe null
        cloudState.getCursor(SyncDocumentKind.LIBRARY)?.eventCursor shouldBe 12
        transport.mutationIds.toSet().shouldHaveSize(1)
        outbox.get(SyncDocumentKind.LIBRARY) shouldBe null
    }

    @Test
    fun `server epoch fence retains unsent v1 progress and pending mutation`() = runTest {
        val progress = SyncDocumentKind.READING_PROGRESS
        val local = document(status = "READING").copy(kind = progress)
        val adapter = FakeAdapter(local)
        val outbox = FakeOutbox().apply { markDirty(progress, 1) }
        val cloudState = FakeCloudState()
        val transport = FakeTransport().apply {
            pushOutcomes += PushOutcome.UnsupportedEpoch
        }

        val result = orchestrator(
            adapter = adapter,
            outbox = outbox,
            cloudState = cloudState,
            transport = transport,
        ).sync(progress)

        (result as SyncDocumentResult.Failed).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        adapter.current shouldBe local
        outbox.get(progress) shouldNotBe null
        cloudState.getPending(progress)?.attemptCount shouldBe 1
        // The successful initial remote read may persist the accepted cursor 0.
        // A rejected push must not advance it to an unaccepted write frontier.
        cloudState.getCursor(progress)?.eventCursor shouldBe 0L
        transport.pushedBatches.shouldHaveSize(1)
    }

    @Test
    fun `refused legacy progress delta cannot overwrite local state or replay pending writes`() = runTest {
        val progress = SyncDocumentKind.READING_PROGRESS
        val local = document(status = "READING").copy(kind = progress)
        val adapter = FakeAdapter(local)
        val outbox = FakeOutbox().apply { markDirty(progress, 1) }
        val cloudState = FakeCloudState()
        val transport = FakeTransport().apply {
            pullFailure = SyncFailureReason.UNSUPPORTED_SCHEMA
        }

        val result = orchestrator(
            adapter = adapter,
            outbox = outbox,
            cloudState = cloudState,
            transport = transport,
        ).sync(progress)

        (result as SyncDocumentResult.Failed).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        adapter.current shouldBe local
        outbox.get(progress) shouldNotBe null
        cloudState.getCursor(progress) shouldBe null
        transport.networkCalls shouldBe 1
        transport.pushedBatches.shouldHaveSize(0)
    }

    @Test
    fun `refused legacy progress snapshot preserves last accepted local frontier`() = runTest {
        val progress = SyncDocumentKind.READING_PROGRESS
        val local = document(status = "READING").copy(kind = progress)
        val adapter = FakeAdapter(local)
        val outbox = FakeOutbox().apply { markDirty(progress, 1) }
        val cloudState = FakeCloudState().apply {
            putCursor(
                documentKind = progress,
                eventCursor = 5,
                lastSuccessfulSyncAtEpochMillis = 1,
            )
        }
        val transport = FakeTransport().apply {
            snapshotFailure = SyncFailureReason.UNSUPPORTED_SCHEMA
        }

        val result = orchestrator(
            adapter = adapter,
            outbox = outbox,
            cloudState = cloudState,
            transport = transport,
        ).sync(progress)

        (result as SyncDocumentResult.Failed).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        adapter.current shouldBe local
        cloudState.getCursor(progress)?.eventCursor shouldBe 5L
        outbox.get(progress) shouldNotBe null
        transport.networkCalls shouldBe 1
        transport.pushedBatches.shouldHaveSize(0)
    }

    @Test
    fun `logged out sync keeps local mutation pending and performs no network request`() = runTest {
        val adapter = FakeAdapter(document(status = "READING"))
        val outbox = FakeOutbox().apply { markDirty(SyncDocumentKind.LIBRARY, 1) }
        val transport = FakeTransport()
        val orchestrator = orchestrator(
            adapter = adapter,
            outbox = outbox,
            transport = transport,
            account = FakeAccountRepository(AccountState.LoggedOut),
        )

        val result = orchestrator.sync(SyncDocumentKind.LIBRARY)

        (result as SyncDocumentResult.Failed).failure.reason shouldBe
            SyncFailureReason.AUTHORIZATION_REQUIRED
        outbox.get(SyncDocumentKind.LIBRARY) shouldNotBe null
        transport.networkCalls shouldBe 0
    }

    @Test
    fun `snapshot rebuilds encoded field paths into nested local document`() = runTest {
        val adapter = FakeAdapter(emptyDocument())
        val cloudState = FakeCloudState().apply {
            putCursor(
                documentKind = SyncDocumentKind.LIBRARY,
                eventCursor = 5,
                lastSuccessfulSyncAtEpochMillis = 1,
            )
        }
        val transport = FakeTransport().apply {
            snapshotValue = SupabaseSyncSnapshot(
                documentKind = SyncDocumentKind.LIBRARY,
                cursor = 5,
                records = listOf(
                    SupabaseSyncSnapshotRecord(
                        recordId = "title-1",
                        isDeleted = false,
                        fields = buildJsonObject {
                            put("progress/chapter~1number", JsonPrimitive(3))
                        },
                    ),
                ),
            )
        }
        val orchestrator = orchestrator(
            adapter = adapter,
            cloudState = cloudState,
            transport = transport,
        )

        orchestrator.sync(SyncDocumentKind.LIBRARY)

        adapter.current.records.getValue("title-1").fields shouldBe
            buildJsonObject {
                put(
                    "progress",
                    buildJsonObject {
                        put("chapter/number", JsonPrimitive(3))
                    },
                )
            }
        transport.mutationIds shouldBe emptyList()
    }

    @Test
    fun `field conflict preserves local field and applies independent remote field`() = runTest {
        val accepted = document(status = "PLANNING")
        val adapter = FakeAdapter(document(status = "READING"))
        val outbox = FakeOutbox().apply { markDirty(SyncDocumentKind.LIBRARY, 1) }
        val stored = FakeStoredState().apply {
            put(
                SyncStoredState(
                    documentKind = SyncDocumentKind.LIBRARY,
                    acceptedBase = accepted,
                    remoteRevision = null,
                    lastSuccessfulSyncAtEpochMillis = 1,
                ),
            )
        }
        val cloudState = FakeCloudState().apply {
            putCursor(
                SyncDocumentKind.LIBRARY,
                eventCursor = 5,
                lastSuccessfulSyncAtEpochMillis = 1,
            )
        }
        val transport = FakeTransport().apply {
            pushOutcomes += PushOutcome.Success(
                cursor = 7,
                events = listOf(
                    event(
                        id = 6,
                        recordId = "title-1",
                        fieldPath = "status",
                        value = JsonPrimitive("COMPLETED"),
                    ),
                    event(
                        id = 7,
                        recordId = "title-1",
                        fieldPath = "score",
                        value = JsonPrimitive(8),
                    ),
                ),
                conflicts = listOf(
                    SupabaseRemoteConflict(
                        conflictId = 99,
                        recordId = "title-1",
                        fieldPath = "status",
                        kind = SyncConflictKind.FIELD_DIVERGENCE,
                        localValue = buildJsonObject {
                            put("removed", JsonPrimitive(false))
                            put("value", JsonPrimitive("READING"))
                        },
                        remoteValue = buildJsonObject {
                            put("removed", JsonPrimitive(false))
                            put("value", JsonPrimitive("COMPLETED"))
                        },
                    ),
                ),
            )
        }
        val conflicts = FakeConflictRepository()
        val orchestrator = orchestrator(
            adapter = adapter,
            outbox = outbox,
            storedState = stored,
            cloudState = cloudState,
            transport = transport,
            conflicts = conflicts,
        )

        val result = orchestrator.sync(SyncDocumentKind.LIBRARY)

        result shouldBe SyncDocumentResult.Conflict(
            documentKind = SyncDocumentKind.LIBRARY,
            conflictCount = 1,
        )
        adapter.field("status") shouldBe JsonPrimitive("READING")
        adapter.field("score") shouldBe JsonPrimitive(8)
        conflicts.getForDocument(SyncDocumentKind.LIBRARY).shouldHaveSize(1)
        outbox.get(SyncDocumentKind.LIBRARY) shouldNotBe null
        cloudState.getPending(SyncDocumentKind.LIBRARY) shouldBe null
    }

    @Test
    fun `unresolved conflict blocks repush while independent remote fields still apply`() = runTest {
        val adapter = FakeAdapter(document(status = "READING", score = 8))
        val accepted = document(status = "COMPLETED", score = 8)
        val stored = FakeStoredState().apply {
            put(
                SyncStoredState(
                    documentKind = SyncDocumentKind.LIBRARY,
                    acceptedBase = accepted,
                    remoteRevision = null,
                    lastSuccessfulSyncAtEpochMillis = 1,
                ),
            )
        }
        val cloudState = FakeCloudState().apply {
            putCursor(
                SyncDocumentKind.LIBRARY,
                eventCursor = 7,
                lastSuccessfulSyncAtEpochMillis = 1,
            )
        }
        val outbox = FakeOutbox().apply {
            markDirty(SyncDocumentKind.LIBRARY, 1)
        }
        val conflicts = FakeConflictRepository().apply {
            replaceForDocument(
                documentKind = SyncDocumentKind.LIBRARY,
                conflicts = listOf(
                    SyncConflict(
                        documentKind = SyncDocumentKind.LIBRARY,
                        recordId = "title-1",
                        propertyPath = listOf("status"),
                        kind = SyncConflictKind.FIELD_DIVERGENCE,
                        base = SyncConflictValue.Present(JsonPrimitive("PLANNING")),
                        local = SyncConflictValue.Present(JsonPrimitive("READING")),
                        remote = SyncConflictValue.Present(JsonPrimitive("COMPLETED")),
                    ),
                ),
                createdAtEpochMillis = 1,
            )
        }
        val transport = FakeTransport().apply {
            events += event(
                id = 8,
                recordId = "title-1",
                fieldPath = "note",
                value = JsonPrimitive("remote-note"),
            )
        }
        val orchestrator = orchestrator(
            adapter = adapter,
            outbox = outbox,
            storedState = stored,
            cloudState = cloudState,
            transport = transport,
            conflicts = conflicts,
        )

        val result = orchestrator.sync(SyncDocumentKind.LIBRARY)

        result shouldBe SyncDocumentResult.Conflict(
            documentKind = SyncDocumentKind.LIBRARY,
            conflictCount = 1,
        )
        adapter.field("status") shouldBe JsonPrimitive("READING")
        adapter.field("score") shouldBe JsonPrimitive(8)
        adapter.field("note") shouldBe JsonPrimitive("remote-note")
        transport.mutationIds shouldBe emptyList()
        outbox.get(SyncDocumentKind.LIBRARY) shouldNotBe null
    }

    @Test
    fun `verified external identity claim rekeys through merge port without title matching`() = runTest {
        val mergePort = RecordingMergePort()
        val identityRepo = FakeIdentityRepository(
            listOf(
                VerifiedCanonicalIdentity(
                    canonicalTitleId = "canon-local",
                    provider = "kitsu",
                    externalId = "1",
                ),
            ),
        )
        val claimTransport = FakeClaimTransport(
            result = SyncTransportResult.Success("canon-cloud"),
        )
        val orchestrator = orchestrator(
            adapter = FakeAdapter(emptyDocument()),
            identityRepository = identityRepo,
            claimTransport = claimTransport,
            mergePort = mergePort,
        )

        orchestrator.sync(SyncDocumentKind.LIBRARY)

        claimTransport.claims shouldBe listOf(
            Triple("kitsu", "1", "canon-local"),
        )
        mergePort.calls shouldBe listOf(
            "canon-cloud" to "canon-local",
        )
    }

    private fun orchestrator(
        adapter: FakeAdapter,
        outbox: FakeOutbox = FakeOutbox(),
        storedState: FakeStoredState = FakeStoredState(),
        cloudState: FakeCloudState = FakeCloudState(),
        transport: FakeTransport = FakeTransport(),
        conflicts: FakeConflictRepository = FakeConflictRepository(),
        account: FakeAccountRepository = FakeAccountRepository(
            AccountState.Authenticated(
                TsuzukiAccount("user-1", "reader@example.com"),
            ),
        ),
        identityRepository: CanonicalIdentitySyncRepository = FakeIdentityRepository(emptyList()),
        claimTransport: CanonicalIdentityClaimTransport = FakeClaimTransport(
            SyncTransportResult.Success("unused"),
        ),
        mergePort: CanonicalTitleMergePort = RecordingMergePort(),
    ): SupabaseSyncOrchestrator {
        var mutationSequence = 0
        return SupabaseSyncOrchestrator(
            accountRepository = account,
            transport = transport,
            identityClaimTransport = claimTransport,
            identityRepository = identityRepository,
            canonicalTitleMergePort = mergePort,
            outboxRepository = outbox,
            stateRepository = storedState,
            supabaseStateStore = cloudState,
            conflictRepository = conflicts,
            adapters = listOf(adapter),
            clientIdentityProvider = SyncClientIdentityProvider { "device-1" },
            clock = object : SyncClock {
                private var now = 100L
                override fun nowEpochMillis(): Long = now++
            },
            mutationIdSource = {
                mutationSequence += 1
                "10000000-0000-0000-0000-${mutationSequence.toString().padStart(12, '0')}"
            },
        )
    }

    private fun emptyDocument() = SyncDocumentEnvelope(
        schemaVersion = 1,
        kind = SyncDocumentKind.LIBRARY,
        revision = SyncRevision("local", 1),
        generatedAtEpochMillis = 1,
        records = emptyMap(),
    )

    private fun document(
        status: String,
        score: Int? = null,
    ): SyncDocumentEnvelope {
        val fields = buildJsonObject {
            put("status", JsonPrimitive(status))
            score?.let { put("score", JsonPrimitive(it)) }
        }
        return SyncDocumentEnvelope(
            schemaVersion = 1,
            kind = SyncDocumentKind.LIBRARY,
            revision = SyncRevision("local", 1),
            generatedAtEpochMillis = 1,
            records = mapOf(
                "title-1" to SyncRecordEnvelope(
                    id = "title-1",
                    revision = SyncRevision("local", 1),
                    updatedAtEpochMillis = 1,
                    fields = fields,
                ),
            ),
        )
    }

    private fun event(
        id: Long,
        recordId: String,
        fieldPath: String,
        value: JsonPrimitive,
    ) = SupabaseSyncEvent(
        eventId = id,
        recordId = recordId,
        fieldPath = fieldPath,
        operation = SupabaseSyncOperation.SET,
        value = value,
    )

    private class FakeAdapter(
        var current: SyncDocumentEnvelope,
        private val unportable: Boolean = false,
    ) : SyncDocumentAdapter {
        override suspend fun hasUnportableLocalState(): Boolean = unportable

        override val documentKind: SyncDocumentKind
            get() = current.kind

        override suspend fun exportDocument(): SyncDocumentEnvelope = current

        override suspend fun applyDocument(document: SyncDocumentEnvelope) {
            current = document
        }

        fun field(name: String) = current.records["title-1"]?.fields?.get(name)
    }

    private class FakeAccountRepository(
        initial: AccountState,
    ) : AccountRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<AccountState> = mutableState

        override suspend fun register(email: String, password: String) =
            error("not used")

        override suspend fun login(email: String, password: String) =
            error("not used")

        override suspend fun logout() = error("not used")

        override suspend fun sendPasswordRecovery(email: String) =
            error("not used")

        override suspend fun updatePassword(password: String) = error("not used")

        override suspend fun refreshSession() = error("not used")

        override suspend fun getAccessToken() = Result.success(
            if (state.value is AccountState.Authenticated) "access-token" else null,
        )
    }

    private class FakeOutbox : SyncOutboxRepository {
        private val entries = mutableMapOf<SyncDocumentKind, SyncOutboxEntry>()

        override suspend fun get(documentKind: SyncDocumentKind) = entries[documentKind]

        override suspend fun markDirty(documentKind: SyncDocumentKind, enqueuedAtEpochMillis: Long) {
            entries[documentKind] = entries[documentKind]
                ?.markDirty(enqueuedAtEpochMillis)
                ?: SyncOutboxEntry(documentKind, enqueuedAtEpochMillis)
        }

        override suspend fun getPending(nowEpochMillis: Long, limit: Int) =
            entries.values.filter { it.isReady(nowEpochMillis) }.take(limit)

        override suspend fun recordFailure(
            documentKind: SyncDocumentKind,
            nextAttemptAtEpochMillis: Long?,
        ) {
            val entry = entries[documentKind] ?: return
            entries[documentKind] = entry.recordFailure(nextAttemptAtEpochMillis)
        }

        override suspend fun clear(documentKind: SyncDocumentKind) {
            entries.remove(documentKind)
        }
    }

    private class FakeStoredState : SyncStateRepository {
        private val states = mutableMapOf<SyncDocumentKind, SyncStoredState>()

        override suspend fun get(documentKind: SyncDocumentKind) = states[documentKind]

        override suspend fun put(state: SyncStoredState) {
            states[state.documentKind] = state
        }

        override suspend fun clear(documentKind: SyncDocumentKind) {
            states.remove(documentKind)
        }
    }

    private class FakeCloudState : SupabaseSyncStateStore {
        private val cursors = mutableMapOf<SyncDocumentKind, SupabaseSyncCursor>()
        private val pending = mutableMapOf<SyncDocumentKind, SupabasePendingMutation>()

        override suspend fun getCursor(documentKind: SyncDocumentKind) = cursors[documentKind]

        override suspend fun putCursor(
            documentKind: SyncDocumentKind,
            eventCursor: Long,
            lastSuccessfulSyncAtEpochMillis: Long?,
        ) {
            cursors[documentKind] = SupabaseSyncCursor(
                documentKind = documentKind,
                eventCursor = eventCursor,
                lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAtEpochMillis,
            )
        }

        override suspend fun getPending(documentKind: SyncDocumentKind) = pending[documentKind]

        override suspend fun putPending(mutation: SupabasePendingMutation) {
            pending[mutation.batch.documentKind] = mutation
        }

        override suspend fun recordPendingFailure(
            mutation: SupabasePendingMutation,
            nextAttemptAtEpochMillis: Long?,
        ) {
            pending[mutation.batch.documentKind] = mutation.copy(
                attemptCount = mutation.attemptCount + 1,
                nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            )
        }

        override suspend fun deletePending(mutationId: String) {
            pending.entries.removeAll { it.value.batch.mutationId == mutationId }
        }
    }

    private class FakeConflictRepository : SyncConflictRepository {
        private val values = mutableMapOf<SyncDocumentKind, List<StoredSyncConflict>>()

        override suspend fun replaceForDocument(
            documentKind: SyncDocumentKind,
            conflicts: List<SyncConflict>,
            createdAtEpochMillis: Long,
        ) {
            values[documentKind] = conflicts.mapIndexed { index, conflict ->
                StoredSyncConflict(
                    conflict = conflict,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
            }
        }

        override suspend fun getForDocument(documentKind: SyncDocumentKind) =
            values[documentKind].orEmpty()

        override suspend fun clearForDocument(documentKind: SyncDocumentKind) {
            values.remove(documentKind)
        }
    }

    private sealed interface PushOutcome {
        data object UnsupportedEpoch : PushOutcome

        data class AppliedResponseLost(
            val cursor: Long,
            val events: List<SupabaseSyncEvent>,
        ) : PushOutcome

        data class Success(
            val cursor: Long,
            val events: List<SupabaseSyncEvent> = emptyList(),
            val conflicts: List<SupabaseRemoteConflict> = emptyList(),
        ) : PushOutcome
    }

    private class FakeTransport : SupabaseSyncTransport {
        val pushOutcomes = ArrayDeque<PushOutcome>()
        val mutationIds = mutableListOf<String>()
        val pushedBatches = mutableListOf<tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch>()
        val acknowledgedConflictIds = mutableListOf<Long>()
        val events = mutableListOf<SupabaseSyncEvent>()
        var snapshotValue: SupabaseSyncSnapshot? = null
        var pullFailure: SyncFailureReason? = null
        var snapshotFailure: SyncFailureReason? = null
        var networkCalls = 0

        override suspend fun pullDelta(
            documentKind: SyncDocumentKind,
            sinceEventId: Long,
            limit: Int,
        ): SyncTransportResult<List<SupabaseSyncEvent>> {
            networkCalls += 1
            pullFailure?.let { return SyncTransportResult.Failure(SyncFailure(it)) }
            return SyncTransportResult.Success(
                events.filter { it.eventId > sinceEventId }
                    .sortedBy { it.eventId }
                    .take(limit),
            )
        }

        override suspend fun snapshot(
            documentKind: SyncDocumentKind,
        ): SyncTransportResult<SupabaseSyncSnapshot> {
            networkCalls += 1
            snapshotFailure?.let { return SyncTransportResult.Failure(SyncFailure(it)) }
            return SyncTransportResult.Success(
                snapshotValue ?: SupabaseSyncSnapshot(
                    documentKind = documentKind,
                    cursor = events.maxOfOrNull { it.eventId } ?: 0,
                    records = emptyList(),
                ),
            )
        }

        override suspend fun ackConflict(
            conflictId: Long,
        ): SyncTransportResult<Boolean> {
            networkCalls += 1
            acknowledgedConflictIds += conflictId
            return SyncTransportResult.Success(true)
        }

        override suspend fun push(
            batch: tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch,
        ): SyncTransportResult<SupabasePushResult> {
            networkCalls += 1
            mutationIds += batch.mutationId
            pushedBatches += batch
            return when (val outcome = pushOutcomes.removeFirstOrNull()) {
                PushOutcome.UnsupportedEpoch -> SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA),
                )
                is PushOutcome.AppliedResponseLost -> {
                    events += outcome.events
                    SyncTransportResult.Failure(
                        SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE),
                    )
                }
                is PushOutcome.Success -> {
                    events += outcome.events
                    SyncTransportResult.Success(
                        SupabasePushResult(
                            cursor = outcome.cursor,
                            conflicts = outcome.conflicts,
                        ),
                    )
                }
                null -> SyncTransportResult.Success(
                    SupabasePushResult(
                        cursor = events.maxOfOrNull { it.eventId } ?: 0,
                        conflicts = emptyList(),
                    ),
                )
            }
        }
    }

    private class FakeIdentityRepository(
        private val identities: List<VerifiedCanonicalIdentity>,
    ) : CanonicalIdentitySyncRepository {
        override suspend fun getVerifiedIdentities() = identities
    }

    private class FakeClaimTransport(
        private val result: SyncTransportResult<String>,
    ) : CanonicalIdentityClaimTransport {
        val claims = mutableListOf<Triple<String, String, String>>()

        override suspend fun claim(
            provider: String,
            externalId: String,
            proposedCanonicalTitleId: String,
        ): SyncTransportResult<String> {
            claims += Triple(provider, externalId, proposedCanonicalTitleId)
            return when (result) {
                is SyncTransportResult.Success -> {
                    if (result.value == "unused") {
                        SyncTransportResult.Success(proposedCanonicalTitleId)
                    } else {
                        result
                    }
                }
                is SyncTransportResult.Failure -> result
            }
        }
    }

    private class RecordingMergePort : CanonicalTitleMergePort {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun merge(targetId: String, localId: String): Result<Unit> {
            calls += targetId to localId
            return Result.success(Unit)
        }
    }
}

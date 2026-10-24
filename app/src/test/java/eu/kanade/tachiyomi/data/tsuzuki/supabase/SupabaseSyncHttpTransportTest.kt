package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult

class SupabaseSyncHttpTransportTest {

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `logged out transport returns authorization required without request`() = runTest {
        val transport = transport(
            account = FakeAccountRepository(AccountState.LoggedOut, token = null),
        )

        val result = transport.pullDelta(
            documentKind = SyncDocumentKind.LIBRARY,
            sinceEventId = 0,
        )

        (result as SyncTransportResult.Failure).failure.reason shouldBe
            SyncFailureReason.AUTHORIZATION_REQUIRED
        server.requestCount shouldBe 0
    }

    @Test
    fun `progress protocol discovery accepts authenticated legacy-only server status`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,
                      "protocolEpoch":1,
                      "activeDomain":"READING_PROGRESS",
                      "exactProgressEnabled":false,
                      "migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().getProgressProtocol()

        (result as SyncTransportResult.Success).value.protocolEpoch shouldBe 1L
        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_get_progress_protocol"
        request.headers["Authorization"] shouldBe "Bearer access-token"
        request.body!!.utf8() shouldBe "{}"
    }

    @Test
    fun `unexpected future progress epoch is rejected before enabling any migration`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":2,
                      "protocolEpoch":2,
                      "activeDomain":"READING_PROGRESS_V2",
                      "exactProgressEnabled":true,
                      "migrationReady":true
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().getProgressProtocol()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `malformed progress protocol status is never treated as a negotiated rollout`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"schemaVersion":1,"protocolEpoch":"invalid"}""")
                .build(),
        )

        val result = transport().getProgressProtocol()

        (result as SyncTransportResult.Failure).failure.reason shouldBe
            SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
    }

    @Test
    fun `server epoch refusal is surfaced as unsupported schema during protocol discovery`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"code":"0A000","message":"progress protocol epoch is not supported by this client"}""",
                )
                .build(),
        )

        val result = transport().getProgressProtocol()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 1
    }

    @Test
    fun `downgraded client progress mutation reports unsupported schema without conflict lookup`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"code":"0A000","message":"legacy progress writes are disabled at this protocol epoch"}""",
                )
                .build(),
        )

        val result = transport().push(
            SupabaseMutationBatch(
                mutationId = "10000000-0000-0000-0000-000000000099",
                originClientId = "legacy-device",
                domain = SyncDocumentKind.READING_PROGRESS.name,
                baseCursor = 0,
                operations = listOf(
                    SyncMutation.SetField(
                        recordId = "chapter-1",
                        propertyPath = listOf("lastPageRead"),
                        value = JsonPrimitive(7),
                        recordUpdatedAtEpochMillis = 1,
                    ),
                ),
            ),
        )

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 1
        server.takeRequest().url.encodedPath shouldBe "/rest/v1/rpc/sync_apply_mutation_batch"
    }

    @Test
    fun `downgraded client refuses stale progress delta from server epoch fence`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"code":"0A000","message":"progress protocol epoch is not supported by this client"}""",
                )
                .build(),
        )

        val result = transport().pullDelta(SyncDocumentKind.READING_PROGRESS, 0)

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 1
        server.takeRequest().url.encodedPath shouldBe "/rest/v1/rpc/sync_pull_delta"
    }

    @Test
    fun `downgraded client refuses stale progress snapshot from server epoch fence`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"code":"0A000","message":"progress protocol epoch is not supported by this client"}""",
                )
                .build(),
        )

        val result = transport().snapshot(SyncDocumentKind.READING_PROGRESS)

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 1
        server.takeRequest().url.encodedPath shouldBe "/rest/v1/rpc/sync_snapshot_domain"
    }

    @Test
    fun `downgraded client cannot acknowledge legacy reading conflict after epoch transition`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"code":"0A000","message":"progress protocol epoch is not supported by this client"}""",
                )
                .build(),
        )

        val result = transport().ackConflict(77)

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 1
        server.takeRequest().url.encodedPath shouldBe "/rest/v1/rpc/sync_ack_conflict"
    }

    @Test
    fun `transport refuses direct unnegotiated v2 delta without contacting backend`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body("""{"code":"0A000","message":"exact progress requires a negotiated protocol rollout"}""")
                .build(),
        )

        val result = transport().pullDelta(SyncDocumentKind.READING_PROGRESS_V2, 0)

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 0
    }

    @Test
    fun `transport refuses direct unnegotiated v2 snapshot without contacting backend`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body("""{"code":"0A000","message":"exact progress requires a negotiated protocol rollout"}""")
                .build(),
        )

        val result = transport().snapshot(SyncDocumentKind.READING_PROGRESS_V2)

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 0
    }

    @Test
    fun `transport refuses direct unnegotiated v2 mutation without contacting backend`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body("""{"code":"0A000","message":"exact progress requires a negotiated protocol rollout"}""")
                .build(),
        )
        val mutation = SupabaseMutationBatch(
            mutationId = "10000000-0000-0000-0000-000000000077",
            originClientId = "v2-unnegotiated",
            domain = SyncDocumentKind.READING_PROGRESS_V2.name,
            baseCursor = 0,
            operations = listOf(
                SyncMutation.SetField(
                    recordId = "v2-chapter",
                    propertyPath = listOf("lastPageRead"),
                    value = JsonPrimitive(2),
                    recordUpdatedAtEpochMillis = 1,
                ),
            ),
        )

        val result = transport().push(mutation)

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
        server.requestCount shouldBe 0
    }

    @Test
    fun `logged-out direct v2 transport preserves authorization refusal without network`() = runTest {
        val transport = transport(
            account = FakeAccountRepository(AccountState.LoggedOut, token = null),
        )

        val result = transport.pullDelta(SyncDocumentKind.READING_PROGRESS_V2, 0)

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.AUTHORIZATION_REQUIRED
        server.requestCount shouldBe 0
    }

    @Test
    fun `invalid mutation stays malformed document instead of protocol refusal`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body("""{"code":"22023","message":"invalid mutation request"}""")
                .build(),
        )

        val result = transport().push(
            SupabaseMutationBatch(
                mutationId = "10000000-0000-0000-0000-000000000098",
                originClientId = "device-1",
                domain = SyncDocumentKind.READING_PROGRESS.name,
                baseCursor = 0,
                operations = listOf(
                    SyncMutation.SetField(
                        recordId = "chapter-1",
                        propertyPath = listOf("lastPageRead"),
                        value = JsonPrimitive(7),
                        recordUpdatedAtEpochMillis = 1,
                    ),
                ),
            ),
        )

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.MALFORMED_DOCUMENT
        server.requestCount shouldBe 1
    }

    @Test
    fun `logged-out protocol discovery performs no remote request`() = runTest {
        val result = transport(
            account = FakeAccountRepository(AccountState.LoggedOut, token = null),
        ).getProgressProtocol()

        (result as SyncTransportResult.Failure).failure.reason shouldBe
            SyncFailureReason.AUTHORIZATION_REQUIRED
        server.requestCount shouldBe 0
    }

    @Test
    fun `legacy device registration records a safe heartbeat without enabling v2`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,
                      "protocolEpoch":1,
                      "leaseExpiresAt":"2026-11-22T12:00:00Z",
                      "exactProgressEnabled":false,
                      "migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().registerLegacyProgressDevice("22000000-0000-0000-0000-000000000001")

        (result as SyncTransportResult.Success).value.leaseExpiresAt shouldBe "2026-11-22T12:00:00Z"
        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_register_progress_device"
        request.headers["Authorization"] shouldBe "Bearer access-token"
        request.body!!.utf8().contains(
            "\"p_client_id\":\"22000000-0000-0000-0000-000000000001\"",
        ) shouldBe true
    }

    @Test
    fun `device heartbeat rejects future epoch lease even when server says ready`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":2,
                      "protocolEpoch":2,
                      "leaseExpiresAt":"2026-11-22T12:00:00Z",
                      "exactProgressEnabled":true,
                      "migrationReady":true
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().registerLegacyProgressDevice("22000000-0000-0000-0000-000000000002")

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `device heartbeat refusing a changed epoch yields unsupported schema`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"code":"0A000","message":"legacy device registration is disabled at this protocol epoch"}""",
                )
                .build(),
        )

        val result = transport().registerLegacyProgressDevice("22000000-0000-0000-0000-000000000003")

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `logged-out device heartbeat cannot call remote registration`() = runTest {
        val result = transport(
            account = FakeAccountRepository(AccountState.LoggedOut, token = null),
        ).registerLegacyProgressDevice("22000000-0000-0000-0000-000000000004")

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.AUTHORIZATION_REQUIRED
        server.requestCount shouldBe 0
    }

    @Test
    fun `progress preflight accepts server observed legacy counts without authorizing v2`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":2,"unexpiredLegacyDeviceCount":1,
                      "legacyRecordCount":23,"legacyTombstoneCount":4,"legacyEventCursor":105,
                      "legacyIncompleteRecordCount":2,"legacyInvalidValueRecordCount":0,"unresolvedProgressConflictCount":1,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        val preview = (result as SyncTransportResult.Success).value
        preview.registeredDeviceCount shouldBe 2L
        preview.unexpiredLegacyDeviceCount shouldBe 1L
        preview.legacyRecordCount shouldBe 23L
        preview.legacyTombstoneCount shouldBe 4L
        preview.legacyEventCursor shouldBe 105L
        preview.legacyIncompleteRecordCount shouldBe 2L
        preview.legacyInvalidValueRecordCount shouldBe 0L
        preview.unresolvedProgressConflictCount shouldBe 1L
        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_preview_progress_rollout"
        request.headers["Authorization"] shouldBe "Bearer access-token"
        request.body!!.utf8() shouldBe "{}"
    }

    @Test
    fun `progress preflight rejects claims of device coverage or migration authorization`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":1,"unexpiredLegacyDeviceCount":1,
                      "legacyRecordCount":1,"legacyTombstoneCount":0,"legacyEventCursor":1,
                      "legacyIncompleteRecordCount":0,"legacyInvalidValueRecordCount":0,"unresolvedProgressConflictCount":0,
                      "historicalDeviceCoverageProven":true,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `progress preflight rejects negative or contradictory counts`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":1,"unexpiredLegacyDeviceCount":2,
                      "legacyRecordCount":1,"legacyTombstoneCount":2,"legacyEventCursor":-1,
                      "legacyIncompleteRecordCount":2,"legacyInvalidValueRecordCount":0,"unresolvedProgressConflictCount":-1,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `rollout preview rejects more incomplete records than active records`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":1,"unexpiredLegacyDeviceCount":1,
                      "legacyRecordCount":1,"legacyTombstoneCount":0,"legacyEventCursor":10,
                      "legacyIncompleteRecordCount":2,"legacyInvalidValueRecordCount":0,"unresolvedProgressConflictCount":0,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `rollout preview refuses negative unresolved conflict counts`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":1,"unexpiredLegacyDeviceCount":1,
                      "legacyRecordCount":1,"legacyTombstoneCount":0,"legacyEventCursor":10,
                      "legacyIncompleteRecordCount":0,"legacyInvalidValueRecordCount":0,"unresolvedProgressConflictCount":-1,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `rollout preview refuses negative invalid-value count`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":1,"unexpiredLegacyDeviceCount":1,
                      "legacyRecordCount":1,"legacyTombstoneCount":0,"legacyEventCursor":10,
                      "legacyIncompleteRecordCount":0,"legacyInvalidValueRecordCount":-1,"unresolvedProgressConflictCount":0,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `rollout preview refuses response missing invalid-value risk counter`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":1,"unexpiredLegacyDeviceCount":1,
                      "legacyRecordCount":1,"legacyTombstoneCount":0,"legacyEventCursor":10,
                      "legacyIncompleteRecordCount":0,"unresolvedProgressConflictCount":0,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
    }

    @Test
    fun `rollout preview refuses invalid-value count greater than active records`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":1,"unexpiredLegacyDeviceCount":1,
                      "legacyRecordCount":1,"legacyTombstoneCount":0,"legacyEventCursor":10,
                      "legacyIncompleteRecordCount":0,"legacyInvalidValueRecordCount":2,"unresolvedProgressConflictCount":0,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `rollout preview rejects older payload missing risk counters`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS",
                      "registeredDeviceCount":0,"unexpiredLegacyDeviceCount":0,
                      "legacyRecordCount":0,"legacyTombstoneCount":0,"legacyEventCursor":0,
                      "historicalDeviceCoverageProven":false,"pendingLocalWritesObservable":false,
                      "exactProgressEnabled":false,"migrationReady":false
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe
            SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
    }

    @Test
    fun `future progress epoch refuses legacy inventory preview`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body("""{"code":"0A000","message":"progress protocol epoch is not supported by this client"}""")
                .build(),
        )

        val result = transport().previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `logged-out progress preflight performs no network request`() = runTest {
        val result = transport(
            account = FakeAccountRepository(AccountState.LoggedOut, token = null),
        ).previewLegacyProgressRollout()

        (result as SyncTransportResult.Failure).failure.reason shouldBe SyncFailureReason.AUTHORIZATION_REQUIRED
        server.requestCount shouldBe 0
    }

    @Test
    fun `push converts local mutation paths and returns mutation scoped conflicts`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("12")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    [
                      {
                        "conflictId":99,
                        "recordId":"title-1",
                        "fieldPath":"progress/chapter~1number",
                        "kind":"FIELD_DIVERGENCE",
                        "localValue":{"removed":false,"value":3},
                        "remoteValue":{"removed":false,"value":2}
                      }
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val transport = transport()
        val batch = SupabaseMutationBatch(
            mutationId = "10000000-0000-0000-0000-000000000001",
            originClientId = "device-1",
            domain = SyncDocumentKind.LIBRARY.name,
            baseCursor = 7,
            operations = listOf(
                SyncMutation.SetField(
                    recordId = "title-1",
                    propertyPath = listOf("progress", "chapter/number"),
                    value = JsonPrimitive(3),
                    recordUpdatedAtEpochMillis = 10,
                ),
            ),
        )

        val result = transport.push(batch)

        val success = result as SyncTransportResult.Success
        success.value.cursor shouldBe 12
        success.value.conflicts.shouldHaveSize(1)
        success.value.conflicts.single().kind shouldBe SyncConflictKind.FIELD_DIVERGENCE

        val rpc = server.takeRequest()
        rpc.url.encodedPath shouldBe "/rest/v1/rpc/sync_apply_mutation_batch"
        rpc.headers["apikey"] shouldBe "publishable-test-key"
        rpc.headers["Authorization"] shouldBe "Bearer access-token"
        val body = rpc.body!!.utf8()
        body.contains("\"mutationId\":\"10000000-0000-0000-0000-000000000001\"") shouldBe true
        body.contains("\"fieldPath\":\"progress/chapter~1number\"") shouldBe true
        body.contains("\"recordUpdatedAtEpochMillis\"") shouldBe false

        val conflicts = server.takeRequest()
        conflicts.url.encodedPath shouldBe "/rest/v1/rpc/sync_get_mutation_conflicts"
        conflicts.body!!.utf8().contains(
            "\"p_mutation_id\":\"10000000-0000-0000-0000-000000000001\"",
        ) shouldBe true
    }

    @Test
    fun `pull delta sends accepted cursor and decodes ordered events`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    [
                      {
                        "eventId":8,
                        "domain":"LIBRARY",
                        "recordId":"title-1",
                        "fieldPath":"status",
                        "operation":"set",
                        "value":"READING",
                        "originClientId":"device-2",
                        "mutationId":"10000000-0000-0000-0000-000000000002",
                        "createdAt":"2026-09-21T12:00:00Z"
                      }
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val transport = transport()

        val result = transport.pullDelta(
            documentKind = SyncDocumentKind.LIBRARY,
            sinceEventId = 7,
            limit = 50,
        )

        val success = result as SyncTransportResult.Success
        success.value.single().eventId shouldBe 8
        success.value.single().value shouldBe JsonPrimitive("READING")

        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_pull_delta"
        val body = request.body!!.utf8()
        body.contains("\"p_domain\":\"LIBRARY\"") shouldBe true
        body.contains("\"p_since_event_id\":7") shouldBe true
        body.contains("\"p_limit\":50") shouldBe true
    }

    @Test
    fun `acknowledge conflict uses authenticated owner scoped rpc`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("true")
                .build(),
        )
        val transport = transport()

        val result = transport.ackConflict(42)

        (result as SyncTransportResult.Success).value shouldBe true
        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_ack_conflict"
        request.headers["Authorization"] shouldBe "Bearer access-token"
        request.body!!.utf8().contains("\"p_conflict_id\":42") shouldBe true
    }

    @Test
    fun `claim external identity uses authenticated rpc and returns claimed id`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("\"canon-cloud\"")
                .build(),
        )
        val transport = transport()

        val result = transport.claim(
            provider = "kitsu",
            externalId = "1",
            proposedCanonicalTitleId = "canon-local",
        )

        (result as SyncTransportResult.Success).value shouldBe "canon-cloud"
        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_claim_external_identity"
        request.body!!.utf8().contains("\"p_proposed_canonical_title_id\":\"canon-local\"") shouldBe true
    }

    private fun transport(
        account: AccountRepository = FakeAccountRepository(
            AccountState.Authenticated(
                TsuzukiAccount("user-1", "reader@example.com"),
            ),
            token = "access-token",
        ),
    ) = SupabaseSyncHttpTransport(
        client = OkHttpClient(),
        configuration = SupabaseConfiguration(
            url = server.url("/").toString().removeSuffix("/"),
            publishableKey = "publishable-test-key",
        ),
        accountRepository = account,
    )

    private class FakeAccountRepository(
        initial: AccountState,
        private val token: String?,
    ) : AccountRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<AccountState> = mutableState

        override suspend fun register(email: String, password: String) = error("not used")

        override suspend fun login(email: String, password: String) = error("not used")

        override suspend fun logout() = error("not used")

        override suspend fun sendPasswordRecovery(email: String) = error("not used")

        override suspend fun refreshSession() = error("not used")

        override suspend fun getAccessToken() = Result.success(token)
    }
}

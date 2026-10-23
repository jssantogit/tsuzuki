package eu.kanade.tachiyomi.data.tsuzuki.supabase

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.SupabaseFieldPathCodec
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SupabasePushResult
import tachiyomi.domain.tsuzuki.sync.model.SupabaseRemoteConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncEvent
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshot
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshotRecord
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.service.CanonicalIdentityClaimTransport
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncTransport
import java.io.IOException

class SupabaseSyncHttpTransport(
    private val client: OkHttpClient,
    private val configuration: SupabaseConfiguration,
    private val accountRepository: AccountRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : SupabaseSyncTransport, CanonicalIdentityClaimTransport {

    /**
     * Read-only protocol discovery. It never enables v2 transport or changes
     * an account epoch. A future / contradictory server response fails closed.
     */
    suspend fun getProgressProtocol(): SyncTransportResult<LegacyProgressProtocol> {
        val response = executeAuthenticated(
            request = rpcRequest("sync_get_progress_protocol", buildJsonObject {}),
        ) { raw ->
            json.decodeFromString(ProgressProtocolResponse.serializer(), raw)
        }
        return when (response) {
            is SyncTransportResult.Failure -> response
            is SyncTransportResult.Success -> {
                val protocol = response.value
                if (
                    protocol.schemaVersion != 1 ||
                    protocol.protocolEpoch != 1L ||
                    protocol.activeDomain != SyncDocumentKind.READING_PROGRESS.name ||
                    protocol.exactProgressEnabled ||
                    protocol.migrationReady
                ) {
                    SyncTransportResult.Failure(
                        SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA),
                    )
                } else {
                    SyncTransportResult.Success(
                        LegacyProgressProtocol(protocolEpoch = protocol.protocolEpoch),
                    )
                }
            }
        }
    }

    /**
     * Authenticated presence for an existing v1 client. It is deliberately
     * separate from normal sync and cannot authorize a v2 migration.
     * Do not call from production sync until the backend migration is deployed.
     */
    suspend fun registerLegacyProgressDevice(clientId: String): SyncTransportResult<LegacyProgressDeviceLease> {
        require(clientId.isNotBlank()) { "Sync client identity must not be blank" }
        val response = executeAuthenticated(
            request = rpcRequest(
                "sync_register_progress_device",
                buildJsonObject { put("p_client_id", JsonPrimitive(clientId)) },
            ),
        ) { raw ->
            json.decodeFromString(ProgressDeviceRegistrationResponse.serializer(), raw)
        }
        return when (response) {
            is SyncTransportResult.Failure -> response
            is SyncTransportResult.Success -> {
                val lease = response.value
                if (
                    lease.schemaVersion != 1 ||
                    lease.protocolEpoch != 1L ||
                    lease.leaseExpiresAt.isBlank() ||
                    lease.exactProgressEnabled ||
                    lease.migrationReady
                ) {
                    SyncTransportResult.Failure(SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA))
                } else {
                    SyncTransportResult.Success(
                        LegacyProgressDeviceLease(leaseExpiresAt = lease.leaseExpiresAt),
                    )
                }
            }
        }
    }

    /**
     * Server-observed v1 inventory for manual migration review only.
     * No amount of positive-looking counters can authorize v2 sync.
     */
    suspend fun previewLegacyProgressRollout(): SyncTransportResult<LegacyProgressRolloutPreview> {
        val response = executeAuthenticated(
            request = rpcRequest("sync_preview_progress_rollout", buildJsonObject {}),
        ) { raw ->
            json.decodeFromString(ProgressRolloutPreviewResponse.serializer(), raw)
        }
        return when (response) {
            is SyncTransportResult.Failure -> response
            is SyncTransportResult.Success -> {
                val preview = response.value
                if (
                    preview.schemaVersion != 1 ||
                    preview.protocolEpoch != 1L ||
                    preview.activeDomain != SyncDocumentKind.READING_PROGRESS.name ||
                    preview.registeredDeviceCount < 0 ||
                    preview.unexpiredLegacyDeviceCount < 0 ||
                    preview.unexpiredLegacyDeviceCount > preview.registeredDeviceCount ||
                    preview.legacyRecordCount < 0 ||
                    preview.legacyTombstoneCount < 0 ||
                    preview.legacyTombstoneCount > preview.legacyRecordCount ||
                    preview.legacyEventCursor < 0 ||
                    preview.historicalDeviceCoverageProven ||
                    preview.pendingLocalWritesObservable ||
                    preview.exactProgressEnabled ||
                    preview.migrationReady
                ) {
                    SyncTransportResult.Failure(
                        SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA),
                    )
                } else {
                    SyncTransportResult.Success(
                        LegacyProgressRolloutPreview(
                            registeredDeviceCount = preview.registeredDeviceCount,
                            unexpiredLegacyDeviceCount = preview.unexpiredLegacyDeviceCount,
                            legacyRecordCount = preview.legacyRecordCount,
                            legacyTombstoneCount = preview.legacyTombstoneCount,
                            legacyEventCursor = preview.legacyEventCursor,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun pullDelta(
        documentKind: SyncDocumentKind,
        sinceEventId: Long,
        limit: Int,
    ): SyncTransportResult<List<SupabaseSyncEvent>> {
        require(sinceEventId >= 0) { "Supabase delta cursor must not be negative" }
        require(limit in 1..1000) { "Supabase delta limit must be between 1 and 1000" }

        val body = buildJsonObject {
            put("p_domain", JsonPrimitive(documentKind.name))
            put("p_since_event_id", JsonPrimitive(sinceEventId))
            put("p_limit", JsonPrimitive(limit))
        }
        return executeAuthenticated(
            request = rpcRequest("sync_pull_delta", body),
        ) { raw ->
            json.decodeFromString(
                JsonArray.serializer(),
                raw,
            ).map { element ->
                json.decodeFromJsonElement(SupabaseSyncEvent.serializer(), element)
            }
        }
    }

    override suspend fun snapshot(
        documentKind: SyncDocumentKind,
    ): SyncTransportResult<SupabaseSyncSnapshot> {
        val body = buildJsonObject {
            put("p_domain", JsonPrimitive(documentKind.name))
        }
        return executeAuthenticated(
            request = rpcRequest("sync_snapshot_domain", body),
        ) { raw ->
            val response = json.decodeFromString(
                SnapshotResponse.serializer(),
                raw,
            )
            SupabaseSyncSnapshot(
                documentKind = documentKind,
                cursor = response.cursor,
                records = response.records.map { record ->
                    SupabaseSyncSnapshotRecord(
                        recordId = record.recordId,
                        isDeleted = record.isDeleted,
                        fields = record.fields,
                    )
                },
            )
        }
    }

    override suspend fun push(
        batch: SupabaseMutationBatch,
    ): SyncTransportResult<SupabasePushResult> {
        val payload = buildJsonObject {
            put("mutationId", JsonPrimitive(batch.mutationId))
            put("originClientId", JsonPrimitive(batch.originClientId))
            put("domain", JsonPrimitive(batch.domain))
            put("baseCursor", JsonPrimitive(batch.baseCursor))
            put(
                "operations",
                buildJsonArray {
                    batch.operations.forEach { add(it.toBackendOperation()) }
                },
            )
        }
        val requestBody = buildJsonObject {
            put("p_request", payload)
        }

        val pushed = executeAuthenticated(
            request = rpcRequest("sync_apply_mutation_batch", requestBody),
        ) { raw ->
            raw.trim().toLong()
        }
        val cursor = when (pushed) {
            is SyncTransportResult.Failure -> return pushed
            is SyncTransportResult.Success -> pushed.value
        }

        val conflicts = getMutationConflicts(batch.mutationId)
        return when (conflicts) {
            is SyncTransportResult.Failure -> conflicts
            is SyncTransportResult.Success -> {
                SyncTransportResult.Success(
                    SupabasePushResult(
                        cursor = cursor,
                        conflicts = conflicts.value,
                    ),
                )
            }
        }
    }

    override suspend fun ackConflict(
        conflictId: Long,
    ): SyncTransportResult<Boolean> {
        require(conflictId > 0) { "Conflict ID must be positive" }

        val body = buildJsonObject {
            put("p_conflict_id", JsonPrimitive(conflictId))
        }
        return executeAuthenticated(
            request = rpcRequest("sync_ack_conflict", body),
        ) { raw ->
            raw.trim().toBooleanStrict()
        }
    }

    override suspend fun claim(
        provider: String,
        externalId: String,
        proposedCanonicalTitleId: String,
    ): SyncTransportResult<String> {
        require(provider.isNotBlank()) { "Identity provider must not be blank" }
        require(externalId.isNotBlank()) { "External identity must not be blank" }
        require(proposedCanonicalTitleId.isNotBlank()) {
            "Proposed canonical title ID must not be blank"
        }

        val body = buildJsonObject {
            put("p_provider", JsonPrimitive(provider))
            put("p_external_id", JsonPrimitive(externalId))
            put(
                "p_proposed_canonical_title_id",
                JsonPrimitive(proposedCanonicalTitleId),
            )
        }
        return executeAuthenticated(
            request = rpcRequest("sync_claim_external_identity", body),
        ) { raw ->
            json.decodeFromString<String>(raw)
        }
    }

    private suspend fun getMutationConflicts(
        mutationId: String,
    ): SyncTransportResult<List<SupabaseRemoteConflict>> {
        val body = buildJsonObject {
            put("p_mutation_id", JsonPrimitive(mutationId))
        }
        return executeAuthenticated(
            request = rpcRequest("sync_get_mutation_conflicts", body),
        ) { raw ->
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    MutationConflictResponse.serializer(),
                ),
                raw,
            ).map { row ->
                SupabaseRemoteConflict(
                    conflictId = row.conflictId,
                    recordId = row.recordId,
                    fieldPath = row.fieldPath,
                    kind = row.kind,
                    localValue = row.localValue,
                    remoteValue = row.remoteValue,
                )
            }
        }
    }

    private fun rpcRequest(
        functionName: String,
        body: JsonObject,
    ): Request {
        return Request.Builder()
            .url(configuration.endpoint("rest/v1/rpc/$functionName"))
            .post(
                json.encodeToString(JsonObject.serializer(), body)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
    }

    private suspend fun <T> executeAuthenticated(
        request: Request,
        decode: (String) -> T,
    ): SyncTransportResult<T> {
        if (accountRepository.state.value !is AccountState.Authenticated) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
            )
        }

        val tokenResult = accountRepository.getAccessToken()
        val accessToken = tokenResult.getOrElse { error ->
            return SyncTransportResult.Failure(error.toSyncFailure())
        }
        if (accessToken.isNullOrBlank()) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
            )
        }

        val authenticated = request.newBuilder()
            .header("apikey", configuration.publishableKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(authenticated).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) {
                    return SyncTransportResult.Failure(
                        response.code.toSyncFailure(
                            retryAfterHeader = response.header("Retry-After"),
                            responseBody = raw,
                        ),
                    )
                }

                runCatching { decode(raw) }
                    .fold(
                        onSuccess = { value -> SyncTransportResult.Success(value) },
                        onFailure = {
                            SyncTransportResult.Failure(
                                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                            )
                        },
                    )
            }
        } catch (_: IOException) {
            SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE),
            )
        }
    }

    private fun SyncMutation.toBackendOperation(): JsonObject {
        return when (this) {
            is SyncMutation.SetField -> buildJsonObject {
                put("type", JsonPrimitive("set"))
                put("recordId", JsonPrimitive(recordId))
                put(
                    "fieldPath",
                    JsonPrimitive(SupabaseFieldPathCodec.encode(propertyPath)),
                )
                put("value", value)
            }
            is SyncMutation.RemoveField -> buildJsonObject {
                put("type", JsonPrimitive("remove"))
                put("recordId", JsonPrimitive(recordId))
                put(
                    "fieldPath",
                    JsonPrimitive(SupabaseFieldPathCodec.encode(propertyPath)),
                )
            }
            is SyncMutation.DeleteRecord -> buildJsonObject {
                put("type", JsonPrimitive("delete"))
                put("recordId", JsonPrimitive(recordId))
            }
        }
    }

    private fun Throwable.toSyncFailure(): SyncFailure {
        return if (this is IOException) {
            SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE)
        } else {
            SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED)
        }
    }

    private fun Int.toSyncFailure(
        retryAfterHeader: String?,
        responseBody: String,
    ): SyncFailure {
        // PostgREST reports server-side epoch fences with SQLSTATE 0A000
        // and HTTP 400. Do not misclassify a forbidden progress protocol as
        // corrupted local data, or bypass other ordinary 400 validation.
        if (this == 400 || this == 422) {
            val postgresCode = runCatching {
                (json.parseToJsonElement(responseBody) as? JsonObject)
                    ?.get("code")
                    ?.let { it as? JsonPrimitive }
                    ?.content
            }.getOrNull()
            if (postgresCode == "0A000") {
                return SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA)
            }
        }
        val reason = when (this) {
            400, 422 -> SyncFailureReason.MALFORMED_DOCUMENT
            401 -> SyncFailureReason.AUTHORIZATION_REQUIRED
            403 -> SyncFailureReason.REMOTE_ACCESS_DENIED
            404 -> SyncFailureReason.REMOTE_NOT_FOUND
            409 -> SyncFailureReason.REMOTE_CHANGED
            429 -> SyncFailureReason.RATE_LIMITED
            in 500..599 -> SyncFailureReason.REMOTE_UNAVAILABLE
            else -> SyncFailureReason.UNKNOWN
        }
        val retryAfterMillis = if (this == 429) {
            retryAfterHeader
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.let { seconds ->
                    if (seconds > Long.MAX_VALUE / 1_000L) {
                        Long.MAX_VALUE
                    } else {
                        seconds * 1_000L
                    }
                }
        } else {
            null
        }
        return SyncFailure(
            reason = reason,
            retryAfterMillis = retryAfterMillis,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * The only protocol a client may acknowledge while remote migration, client
 * capabilities and downgrade fencing are unavailable.
 */
data class LegacyProgressProtocol(
    val protocolEpoch: Long,
)

@Serializable
private data class ProgressProtocolResponse(
    val schemaVersion: Int,
    val protocolEpoch: Long,
    val activeDomain: String,
    val exactProgressEnabled: Boolean,
    val migrationReady: Boolean,
)

/** Device-presence receipt only; never a protocol activation credential. */
data class LegacyProgressDeviceLease(
    val leaseExpiresAt: String,
)

@Serializable
private data class ProgressDeviceRegistrationResponse(
    val schemaVersion: Int,
    val protocolEpoch: Long,
    val leaseExpiresAt: String,
    val exactProgressEnabled: Boolean,
    val migrationReady: Boolean,
)

/** Non-authorizing account v1 inventory; local pending writes remain unknown. */
data class LegacyProgressRolloutPreview(
    val registeredDeviceCount: Long,
    val unexpiredLegacyDeviceCount: Long,
    val legacyRecordCount: Long,
    val legacyTombstoneCount: Long,
    val legacyEventCursor: Long,
)

@Serializable
private data class ProgressRolloutPreviewResponse(
    val schemaVersion: Int,
    val protocolEpoch: Long,
    val activeDomain: String,
    val registeredDeviceCount: Long,
    val unexpiredLegacyDeviceCount: Long,
    val legacyRecordCount: Long,
    val legacyTombstoneCount: Long,
    val legacyEventCursor: Long,
    val historicalDeviceCoverageProven: Boolean,
    val pendingLocalWritesObservable: Boolean,
    val exactProgressEnabled: Boolean,
    val migrationReady: Boolean,
)

@Serializable
private data class MutationConflictResponse(
    val conflictId: Long,
    val recordId: String,
    val fieldPath: String?,
    val kind: tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind,
    val localValue: kotlinx.serialization.json.JsonElement?,
    val remoteValue: kotlinx.serialization.json.JsonElement?,
)

@Serializable
private data class SnapshotResponse(
    val cursor: Long,
    val records: List<SnapshotRecordResponse>,
)

@Serializable
private data class SnapshotRecordResponse(
    val recordId: String,
    val isDeleted: Boolean,
    val fields: JsonObject,
)

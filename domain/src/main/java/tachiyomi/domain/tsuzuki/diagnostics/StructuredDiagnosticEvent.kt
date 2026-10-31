package tachiyomi.domain.tsuzuki.diagnostics

/** Versioned, provider-neutral diagnostics contract. Free-form messages and throwables are absent. */
data class StructuredDiagnosticEvent(
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val subsystem: DiagnosticSubsystem,
    val name: DiagnosticEventName,
    val sessionId: String,
    val operationId: String?,
    val workflowId: String? = null,
    val parentOperationId: String? = null,
    val workflow: DiagnosticWorkflow? = null,
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val durationMillis: Long? = null,
    val attempt: Int? = null,
    val attributes: Map<String, DiagnosticAttributeValue> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class DiagnosticSubsystem {
    APP,
    NETWORK,
    INTEGRATION,
    METADATA,
    ARTWORK,
    IMAGE,
    LIBRARY,
    SOURCE,
    CONTENT,
    CHAPTER,
    READER,
    DOWNLOAD,
    DATABASE,
    DIAGNOSTICS,
    COLLECTIONS,
}

enum class DiagnosticWorkflow {
    OPEN_TITLE,
    CONTINUE_READING,
    LIBRARY_SYNC,
    SOURCE_RESOLUTION,
    CONTENT_RESOLUTION,
    CHAPTER_REFRESH,
    READER_OPEN,
    READER_SOURCE_SWITCH,
    DOWNLOAD_CHAPTER,
    ARTWORK_RESOLUTION,
    METADATA_RESOLUTION,
    DIAGNOSTIC_CAPTURE,
    COLLECTION_EXECUTION,
}

enum class DiagnosticEventName {
    WORKFLOW_STARTED,
    WORKFLOW_COMPLETED,

    SOURCE_RESOLVE_STARTED,
    SOURCE_RESOLVE_MAPPING_REUSED,
    SOURCE_RESOLVE_PREFERRED_SOURCES,
    SOURCE_SEARCH_STARTED,
    SOURCE_SEARCH_COMPLETED,
    SOURCE_SEARCH_FAILED,
    SOURCE_MATCH_EVALUATED,
    SOURCE_MAPPING_CONFIRMATION_FAILED,
    SOURCE_RESOLVE_COMPLETED,

    ARTWORK_RESOLVE_STARTED,
    ARTWORK_OBSERVATIONS_READ,
    ARTWORK_PROVIDER_SELECTED,
    ARTWORK_CANDIDATES_BUILT,
    ARTWORK_LOAD_ATTEMPT,
    ARTWORK_LOAD_COMPLETED,

    METADATA_RESOLVE_STARTED,
    METADATA_PROVIDER_RESULT,
    RATING_PROVIDER_RESULT,
    TSUZUKI_RATING_COMPUTED,
    METADATA_RESOLVE_COMPLETED,

    LIBRARY_SYNC_STARTED,
    LIBRARY_PROVIDER_FETCHED,
    LIBRARY_SYNC_COMPLETED,

    CONTENT_BINDING_LOOKUP,
    CONTENT_BINDING_RESOLVE_STARTED,
    CONTENT_BINDING_RESOLVE_COMPLETED,

    CHAPTER_REFRESH_STARTED,
    CHAPTER_EVIDENCE_RECONCILED,
    CHAPTER_REFRESH_COMPLETED,
    PROVIDER_READING_EVIDENCE,
    PROVIDER_READING_LOOKUP_ATTEMPT,
    PROVIDER_READING_PAGE,
    PROVIDER_READING_PAGE_DETAIL,
    PROVIDER_READING_BINDING,
    PROVIDER_READING_CANDIDATE,

    READER_OPEN_STARTED,
    READER_SOURCE_SELECTED,
    READER_PAGES_READY,
    READER_OPEN_COMPLETED,
    READER_PROGRESS_RECORDED,

    DOWNLOAD_STARTED,
    DOWNLOAD_PREPARED,
    DOWNLOAD_COMPLETED,

    TORRENT_METADATA_SUMMARY,
    TORRENT_MATCH_SUMMARY,
    TORRENT_RELEASE_EVIDENCE_SUMMARY,
    TORRENT_CANDIDATE_EVALUATED,
    PROVIDER_P2P_EVENT,

    CACHE_LOOKUP,
    DATABASE_OPERATION,
    INVARIANT_VIOLATION,
    DIAGNOSTIC_RECORDER_HEALTH,
    CAPTURE_SESSION_STARTED,
    CAPTURE_SESSION_STOPPED,
    CRASH_CONTEXT_UPDATED,

    COLLECTION_QUERY_PLANNED,
    COLLECTION_EXECUTION_COMPLETED,
}

enum class DiagnosticStage {
    WORKFLOW,
    RESOLVE,
    PREFERRED_SOURCES,
    SEARCH,
    MATCH,
    CONFIRMATION,
    READ,
    WRITE,
    LOOKUP,
    PERSIST,
    IMAGE_LOAD,
    RENDER,
    SYNC,
    BINDING,
    RECONCILE,
    READER,
    DOWNLOAD,
    SUMMARY,
    CAPTURE,
    CRASH_CONTEXT,
    COMPLETE,
}

enum class DiagnosticOutcome {
    STARTED,
    SUCCEEDED,
    REUSED,
    CANDIDATES,
    NEEDS_CONFIRMATION,
    TYPED_FAILURE,
    THREW,
    NOT_FOUND_NO_CANDIDATES,
    NOT_FOUND_WITH_SOURCE_FAILURES,
    NO_PREFERRED_SOURCES,
    HIT,
    MISS,
    ACCEPTED,
    REJECTED,
    PARTIAL,
    SKIPPED,
    READY,
    EMPTY,
    TIMEOUT,
    FAILED,
    CANCELLED,
}

enum class DiagnosticAttribute {
    SOURCE_ID,
    SOURCE_COUNT,
    PREFERRED_SOURCE_COUNT,
    TARGET_SOURCE_COUNT,
    CANDIDATE_COUNT,
    CANDIDATE_INDEX,
    QUERY_INDEX,
    PROVIDER_WORK_ID,
    PROVIDER_WORK_TITLE,
    PROVIDER_WORK_URL,
    PROVIDER_WORK_ALIASES,
    PROVIDER_LOOKUP_TITLE,
    LOOKUP_DECISION,
    PROVIDER_CHAPTER_FIRST_ID,
    PROVIDER_CHAPTER_LAST_ID,
    CHAPTER_NUMBER_MIN_MILLI,
    CHAPTER_NUMBER_MAX_MILLI,
    PARTIAL_EVIDENCE_COUNT,
    PROVIDER_WORK_CHAPTER_COUNT,
    PROVIDER_CANDIDATE_REASON,
    PROVIDER_DETAIL_OUTCOME,
    BROADENED,
    MAPPING_REUSED,
    LANGUAGE,
    PROVIDER_ID,
    PROVIDER_VERSION_NAME,
    PROVIDER_VERSION_CODE,
    PROVIDER_ERROR_CODE,
    PROVIDER_RETRYABLE,
    ADDON_ID,
    CANONICAL_TITLE_REF,
    MIHON_MANGA_REF,
    CONFIDENCE_SCORE,
    CONFIDENCE_BUCKET,
    AUTO_CONFIRM_ATTEMPTED,
    AMBIGUOUS,
    ERROR_CATEGORY,
    HTTP_STATUS,

    OBSERVATION_COUNT,
    ITEM_COUNT,
    ACCEPTED_COUNT,
    REJECTED_COUNT,
    PAGE_COUNT,
    PAGE_INDEX,
    BINDING_COUNT,
    CHAPTER_COUNT,
    VARIANT_COUNT,
    QUEUE_DEPTH,

    TORRENT_HYDRATION_ATTEMPTED_COUNT,
    TORRENT_HYDRATED_COUNT,
    TORRENT_HYDRATION_FAILED_COUNT,
    TORRENT_READABLE_COUNT,
    TORRENT_PARSED_COUNT,
    TORRENT_EMBEDDED_COUNT,
    TORRENT_IDENTITY_MATCH_COUNT,
    TORRENT_VOLUME_MATCH_COUNT,
    TORRENT_EXACT_MATCH_COUNT,
    TORRENT_AMBIGUOUS_MATCH_COUNT,
    TORRENT_RELEASE_EXPLICIT_COUNT,
    TORRENT_RELEASE_EXACT_COUNT,
    TORRENT_SINGLE_READABLE_COUNT,
    TORRENT_RELEASE_EXACT_SINGLE_COUNT,
    TORRENT_RELEASE_EXACT_READABLE_COUNT,
    TORRENT_RELEASE_EXACT_MULTI_READABLE_COUNT,
    TORRENT_FILE_TOKEN_COUNT,
    TORRENT_FILE_TOKEN_SINGLE_COUNT,
    TORRENT_FILE_TITLE_TOKEN_COUNT,
    TORRENT_FILE_TITLE_TOKEN_SINGLE_COUNT,
    TORRENT_VOLUME_REQUESTED,
    TORRENT_NO_MATCH_REASON,
    TORRENT_CANDIDATE_MATCH_REASON,
    TORRENT_CANDIDATE_EXACT_COUNT,
    TORRENT_CANDIDATE_WORK_UNPROVEN_COUNT,
    TORRENT_CANDIDATE_CHAPTER_UNPROVEN_COUNT,
    TORRENT_CANDIDATE_NO_ARCHIVE_COUNT,
    TORRENT_CANDIDATE_IMAGE_DIRECTORY_UNSUPPORTED_COUNT,
    TORRENT_CANDIDATE_VOLUME_UNPROVEN_COUNT,
    TORRENT_CANDIDATE_AMBIGUOUS_COUNT,
    TORRENT_CANDIDATE_METADATA_UNAVAILABLE_COUNT,
    PROVIDER_P2P_LINE,

    COVER_PRESENT,
    SOURCE_ARTWORK_PRESENT,
    CANONICAL_ARTWORK_PRESENT,
    REQUEST_DATA_PRESENT,
    INITIALIZED,
    COMPLETED,
    IDENTITY_VERIFIED,
    RATING_PRESENT,
    RATING_SOURCE_COUNT,
    RATING_VERIFIED_SOURCE_COUNT,
    RATING_CORROBORATED_SOURCE_COUNT,
    TSUZUKI_RATING_PRESENT,

    CANDIDATE_TYPE,
    CACHE_STATUS,
    INVARIANT_CODE,

    EVENTS_RECEIVED,
    EVENTS_SANITIZED,
    EVENTS_REJECTED,
    LOGCAT_SINK_FAILURES,
    HISTORY_SINK_FAILURES,
    HISTORY_DROPPED_EVENTS,
    EXPORT_FLUSH_TIMEOUTS,
}

sealed interface DiagnosticAttributeValue {
    data class Number(val value: Long) : DiagnosticAttributeValue

    data class Flag(val value: Boolean) : DiagnosticAttributeValue

    data class Text(val value: String) : DiagnosticAttributeValue

    data class Code(val value: DiagnosticSafeCode) : DiagnosticAttributeValue
}

/** Closed technical values only; callers cannot attach provider or exception text. */
sealed interface DiagnosticSafeCode

enum class DiagnosticErrorCategory : DiagnosticSafeCode {
    NETWORK,
    TIMEOUT,
    HTTP,
    CAPTCHA,
    SOURCE_UNAVAILABLE,
    MALFORMED_RESPONSE,
    EXTENSION,
    DATABASE,
    IMAGE,
    DIAGNOSTICS,
    UNKNOWN,
}

enum class DiagnosticConfidenceBucket : DiagnosticSafeCode {
    LOW,
    MEDIUM,
    HIGH,
}

enum class DiagnosticCandidateType : DiagnosticSafeCode {
    PROVIDER,
    SOURCE_AWARE,
    SOURCE_RAW,
    LOCAL,
}

/** Closed, content-free reason codes for a torrent search with no safe exact match. */
enum class DiagnosticTorrentNoMatchReason : DiagnosticSafeCode {
    NO_CANDIDATES,
    NO_READABLE_ARCHIVES,
    IMAGE_DIRECTORY_UNSUPPORTED,
    CHAPTER_IDENTITY_NOT_PROVEN,
    VOLUME_NOT_PROVEN,
    AMBIGUOUS_ARCHIVES,
    NO_EXACT_MATCH,
}

/** Private, closed classifications for a single torrent candidate. Never include file paths or titles. */
enum class DiagnosticTorrentCandidateMatchReason : DiagnosticSafeCode {
    EXACT,
    AMBIGUOUS_ARCHIVES,
    METADATA_UNAVAILABLE,
    NO_READABLE_ARCHIVES,
    IMAGE_DIRECTORY_UNSUPPORTED,
    CHAPTER_IDENTITY_NOT_PROVEN,
    WORK_IDENTITY_NOT_PROVEN,
    VOLUME_NOT_PROVEN,
}

/** Source identity is unresolved even when lookup HTTP/API completed successfully. */
enum class DiagnosticProviderLookupDecision : DiagnosticSafeCode {
    SELECTED,
    NO_MATCH,
    MULTIPLE_PRIMARY_IDENTITIES,
    AMBIGUOUS_EDITION,
}

/** Detailed-capture-only classification; never use title similarity as binding authorization. */
enum class DiagnosticProviderCandidateReason : DiagnosticSafeCode {
    SELECTED,
    PRIMARY_MATCH_BLOCKED,
    ALIAS_MATCH_BLOCKED,
    ALTERNATE_EDITION,
    FACET_MISMATCH,
    TITLE_MISMATCH,
}

/** Closed source-enrichment outcomes: no upstream response text or secrets. */
enum class DiagnosticProviderDetailOutcome : DiagnosticSafeCode {
    NOT_NEEDED,
    SKIPPED_IRRELEVANT,
    BUDGET_EXHAUSTED,
    VERIFIED_WITH_ALIASES,
    VERIFIED_NO_ALIASES,
    IDENTITY_MISMATCH,
    INVALID_RESPONSE,
    CHALLENGE_BLOCKED,
    DETAIL_FAILED,
}

enum class DiagnosticCacheStatus : DiagnosticSafeCode {
    HIT,
    MISS,
    STALE,
    BYPASSED,
}

enum class DiagnosticInvariantCode : DiagnosticSafeCode {
    ARTWORK_LOST_AFTER_RESOLUTION,
    CONTENT_BINDING_EXISTS_BUT_NOT_CONSUMED,
    CHAPTER_VARIANT_WITHOUT_EVIDENCE,
    READER_OPEN_WITHOUT_SELECTED_SOURCE,
    SOURCE_MAPPING_WITHOUT_RESTORABLE_CANDIDATE,
    LIBRARY_MEMBERSHIP_WITHOUT_CANONICAL_TITLE,
    PROVIDER_IDENTITY_WITHOUT_PROVIDER,
    DOWNLOAD_WITHOUT_CONTENT_OPTION,
}

/** Event accepted by domain sinks after all untrusted values have passed the allowlist. */
class SanitizedStructuredDiagnosticEvent internal constructor(
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val subsystem: DiagnosticSubsystem,
    val name: DiagnosticEventName,
    val sessionId: String,
    val workflowId: String?,
    val operationId: String?,
    val parentOperationId: String?,
    val workflow: DiagnosticWorkflow?,
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val durationMillis: Long?,
    val attempt: Int?,
    attributes: Map<DiagnosticAttribute, DiagnosticAttributeValue>,
    val schemaVersion: Int,
) {
    val attributes: Map<DiagnosticAttribute, DiagnosticAttributeValue> =
        java.util.Collections.unmodifiableMap(attributes.toMap())
}

/** Converts an event draft to the only shape suitable for persistence or logging. */
object StructuredDiagnosticSanitizer {
    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
    private val languageTagPattern = Regex("^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,3}$")
    private val pseudonymousReferencePattern = Regex("^[0-9a-f]{16,64}$")
    private val providerIdPattern = Regex("^[a-zA-Z0-9._-]{1,64}$")
    private val providerVersionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val providerErrorCodePattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")

    // Detailed capture accepts only bounded MangaFire public title paths, never query strings or credentials.
    private val providerWorkIdPattern = Regex("^[A-Za-z0-9_-]{1,80}$")
    private val providerChapterIdPattern = Regex("^[0-9]{1,32}$")
    private val providerWorkUrlPattern = Regex("^https://mangafire\\.to/title/[A-Za-z0-9-]{2,160}/?$")
    private val p2pEventPattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val p2pOperationPattern = Regex("^opRef=[0-9a-f]{16}$")
    private val p2pProviderIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val p2pClassPattern = Regex("^[A-Za-z_][A-Za-z0-9_.$]{0,127}$")
    private val p2pCodePattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val p2pCodeFields = setOf(
        "abi", "failure", "format", "phase", "preference", "providerError", "reason",
        "route", "source", "stage", "state", "status",
    )
    private val p2pNumberFields = setOf(
        "actualBytes", "doneBytes", "downloadRate", "elapsedMs", "expectedBytes",
        "fileCount", "fileIndex", "firstByteMs", "firstPeerMs", "ignoredFileCount",
        "initialPeerCount", "magnetTrackerCount", "metadataBytes", "nativeErrorCode",
        "peakPeerCount", "peakSeedCount", "peerCount", "pendingPollCount", "pollCount",
        "pollIntervalMs", "progressPercent", "seedCount", "selectedIndex", "stalledMs",
        "timeoutMs", "wantedBytes", "workingUsableBytes",
    )
    private val p2pFlagFields = setOf(
        "alertListenerAttached", "cleanupSucceeded", "dhtRunning", "directP2pAllowed",
        "exists", "finished", "firstByteObserved", "firstPeerObserved", "hasInfoHash",
        "hasInitialPeers", "hasMagnet", "hasMagnetTrackers", "hasTorrentUrl", "hasV1",
        "hasV2", "indexMatched", "isFile", "lsdExplicitlyDisabled", "matched",
        "nativeSupported", "pathMatched", "retryable", "sizeComplete", "sizeMatched",
        "stopped", "withinJobRoot",
    )
    private val p2pAlertFields = setOf(
        "alertMetadataReceived", "alertMetadataFailed", "alertTrackerReply",
        "alertTrackerWarning", "alertTrackerError", "alertDhtBootstrap",
        "alertDhtGetPeers", "alertDhtGetPeersReply", "alertDhtError",
        "alertListenSucceeded", "alertListenFailed", "alertUdpError", "alertPortmap",
        "alertPortmapError", "alertSessionError", "alertTorrentError", "alertPeerConnect",
        "alertPeerDisconnected", "alertPeerError", "alertFileError", "alertHashFailed",
        "alertFileCompleted", "alertTorrentFinished", "alertAlertsDropped",
    )

    fun sanitize(event: StructuredDiagnosticEvent): SanitizedStructuredDiagnosticEvent? {
        if (event.schemaVersion != StructuredDiagnosticEvent.CURRENT_SCHEMA_VERSION) return null
        if (!event.sessionId.isSafeUuid()) return null
        if (event.workflowId?.isSafeUuid() == false) return null
        if (event.operationId?.isSafeUuid() == false) return null
        if (event.parentOperationId?.isSafeUuid() == false) return null
        if (event.timestampMillis < 0) return null
        if (event.durationMillis?.let { it !in 0..MAX_DURATION_MILLIS } == true) return null
        if (event.attempt?.let { it !in 1..MAX_COUNT.toInt() } == true) return null

        val attributes = event.attributes.mapNotNull { (key, value) ->
            val attribute = DiagnosticAttribute.entries.firstOrNull { it.name.toAttributeKey() == key }
                ?: return@mapNotNull null
            sanitizeAttribute(attribute, value)?.let { attribute to it }
        }.toMap()

        return SanitizedStructuredDiagnosticEvent(
            timestampMillis = event.timestampMillis,
            severity = event.severity,
            subsystem = event.subsystem,
            name = event.name,
            sessionId = event.sessionId.lowercase(),
            workflowId = event.workflowId?.lowercase(),
            operationId = event.operationId?.lowercase(),
            parentOperationId = event.parentOperationId?.lowercase(),
            workflow = event.workflow,
            stage = event.stage,
            outcome = event.outcome,
            durationMillis = event.durationMillis,
            attempt = event.attempt,
            attributes = attributes,
            schemaVersion = event.schemaVersion,
        )
    }

    /** A strictly delimited rendition of Host-only, content-free P2P fields. */
    private fun isSafeP2pLine(line: String): Boolean {
        if (line.length !in 40..2048 || line.any { it.code !in 32..126 }) return false
        val fields = line.split(' ')
        if (fields.size < 3 || fields[0] != "provider_p2p") return false
        if (!fields[1].startsWith("event=") || !p2pEventPattern.matches(fields[1].removePrefix("event="))) {
            return false
        }
        if (!p2pOperationPattern.matches(fields[2])) return false
        return fields.drop(3).all { field ->
            val equal = field.indexOf('=')
            if (equal <= 0 || equal == field.lastIndex) return@all false
            val key = field.substring(0, equal)
            val value = field.substring(equal + 1)
            when {
                key == "jobId" -> uuidPattern.matches(value)
                key == "providerId" -> p2pProviderIdPattern.matches(value)
                key == "exceptionClass" -> p2pClassPattern.matches(value)
                key in p2pCodeFields -> p2pCodePattern.matches(value)
                key in p2pNumberFields || key in p2pAlertFields ->
                    value.toLongOrNull()?.let { it >= 0 } == true
                key in p2pFlagFields -> value == "true" || value == "false"
                else -> false
            }
        }
    }

    private fun sanitizeAttribute(
        attribute: DiagnosticAttribute,
        value: DiagnosticAttributeValue,
    ): DiagnosticAttributeValue? = when (attribute) {
        DiagnosticAttribute.SOURCE_ID -> (value as? DiagnosticAttributeValue.Number)
        DiagnosticAttribute.SOURCE_COUNT,
        DiagnosticAttribute.PREFERRED_SOURCE_COUNT,
        DiagnosticAttribute.TARGET_SOURCE_COUNT,
        DiagnosticAttribute.CANDIDATE_COUNT,
        DiagnosticAttribute.CANDIDATE_INDEX,
        DiagnosticAttribute.QUERY_INDEX,
        DiagnosticAttribute.PROVIDER_WORK_CHAPTER_COUNT,
        DiagnosticAttribute.PARTIAL_EVIDENCE_COUNT,
        DiagnosticAttribute.OBSERVATION_COUNT,
        DiagnosticAttribute.ITEM_COUNT,
        DiagnosticAttribute.ACCEPTED_COUNT,
        DiagnosticAttribute.REJECTED_COUNT,
        DiagnosticAttribute.PAGE_COUNT,
        DiagnosticAttribute.PAGE_INDEX,
        DiagnosticAttribute.BINDING_COUNT,
        DiagnosticAttribute.CHAPTER_COUNT,
        DiagnosticAttribute.VARIANT_COUNT,
        DiagnosticAttribute.QUEUE_DEPTH,
        DiagnosticAttribute.RATING_SOURCE_COUNT,
        DiagnosticAttribute.RATING_VERIFIED_SOURCE_COUNT,
        DiagnosticAttribute.RATING_CORROBORATED_SOURCE_COUNT,
        DiagnosticAttribute.EVENTS_RECEIVED,
        DiagnosticAttribute.EVENTS_SANITIZED,
        DiagnosticAttribute.EVENTS_REJECTED,
        DiagnosticAttribute.LOGCAT_SINK_FAILURES,
        DiagnosticAttribute.HISTORY_SINK_FAILURES,
        DiagnosticAttribute.HISTORY_DROPPED_EVENTS,
        DiagnosticAttribute.EXPORT_FLUSH_TIMEOUTS,
        DiagnosticAttribute.TORRENT_HYDRATION_ATTEMPTED_COUNT,
        DiagnosticAttribute.TORRENT_HYDRATED_COUNT,
        DiagnosticAttribute.TORRENT_HYDRATION_FAILED_COUNT,
        DiagnosticAttribute.TORRENT_READABLE_COUNT,
        DiagnosticAttribute.TORRENT_PARSED_COUNT,
        DiagnosticAttribute.TORRENT_EMBEDDED_COUNT,
        DiagnosticAttribute.TORRENT_IDENTITY_MATCH_COUNT,
        DiagnosticAttribute.TORRENT_VOLUME_MATCH_COUNT,
        DiagnosticAttribute.TORRENT_EXACT_MATCH_COUNT,
        DiagnosticAttribute.TORRENT_AMBIGUOUS_MATCH_COUNT,
        DiagnosticAttribute.TORRENT_RELEASE_EXPLICIT_COUNT,
        DiagnosticAttribute.TORRENT_RELEASE_EXACT_COUNT,
        DiagnosticAttribute.TORRENT_SINGLE_READABLE_COUNT,
        DiagnosticAttribute.TORRENT_RELEASE_EXACT_SINGLE_COUNT,
        DiagnosticAttribute.TORRENT_RELEASE_EXACT_READABLE_COUNT,
        DiagnosticAttribute.TORRENT_RELEASE_EXACT_MULTI_READABLE_COUNT,
        DiagnosticAttribute.TORRENT_FILE_TOKEN_COUNT,
        DiagnosticAttribute.TORRENT_FILE_TOKEN_SINGLE_COUNT,
        DiagnosticAttribute.TORRENT_FILE_TITLE_TOKEN_COUNT,
        DiagnosticAttribute.TORRENT_FILE_TITLE_TOKEN_SINGLE_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_EXACT_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_WORK_UNPROVEN_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_CHAPTER_UNPROVEN_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_NO_ARCHIVE_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_IMAGE_DIRECTORY_UNSUPPORTED_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_VOLUME_UNPROVEN_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_AMBIGUOUS_COUNT,
        DiagnosticAttribute.TORRENT_CANDIDATE_METADATA_UNAVAILABLE_COUNT,
        -> (value as? DiagnosticAttributeValue.Number)?.takeIf { it.value in 0..MAX_COUNT }

        // 1000x scales decimal source chapter numbers without collapsing 1180.5 to 1180.
        DiagnosticAttribute.CHAPTER_NUMBER_MIN_MILLI,
        DiagnosticAttribute.CHAPTER_NUMBER_MAX_MILLI,
        -> (value as? DiagnosticAttributeValue.Number)?.takeIf { it.value in 0..1_000_000_000L }

        DiagnosticAttribute.PROVIDER_VERSION_CODE -> (value as? DiagnosticAttributeValue.Number)
            ?.takeIf { it.value in 1..MAX_COUNT }

        DiagnosticAttribute.BROADENED,
        DiagnosticAttribute.MAPPING_REUSED,
        DiagnosticAttribute.AUTO_CONFIRM_ATTEMPTED,
        DiagnosticAttribute.AMBIGUOUS,
        DiagnosticAttribute.COVER_PRESENT,
        DiagnosticAttribute.SOURCE_ARTWORK_PRESENT,
        DiagnosticAttribute.CANONICAL_ARTWORK_PRESENT,
        DiagnosticAttribute.REQUEST_DATA_PRESENT,
        DiagnosticAttribute.INITIALIZED,
        DiagnosticAttribute.COMPLETED,
        DiagnosticAttribute.IDENTITY_VERIFIED,
        DiagnosticAttribute.RATING_PRESENT,
        DiagnosticAttribute.TSUZUKI_RATING_PRESENT,
        DiagnosticAttribute.PROVIDER_RETRYABLE,
        DiagnosticAttribute.TORRENT_VOLUME_REQUESTED,
        -> value as? DiagnosticAttributeValue.Flag

        DiagnosticAttribute.TORRENT_NO_MATCH_REASON -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticTorrentNoMatchReason }
        DiagnosticAttribute.TORRENT_CANDIDATE_MATCH_REASON -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticTorrentCandidateMatchReason }
        DiagnosticAttribute.PROVIDER_CANDIDATE_REASON -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticProviderCandidateReason }
        DiagnosticAttribute.PROVIDER_DETAIL_OUTCOME -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticProviderDetailOutcome }
        DiagnosticAttribute.LOOKUP_DECISION -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticProviderLookupDecision }

        DiagnosticAttribute.PROVIDER_P2P_LINE -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { isSafeP2pLine(it.value) }
        DiagnosticAttribute.PROVIDER_WORK_ID -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { providerWorkIdPattern.matches(it.value) }
        DiagnosticAttribute.PROVIDER_CHAPTER_FIRST_ID,
        DiagnosticAttribute.PROVIDER_CHAPTER_LAST_ID,
        -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { providerChapterIdPattern.matches(it.value) }
        DiagnosticAttribute.PROVIDER_WORK_URL -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { providerWorkUrlPattern.matches(it.value) }
        DiagnosticAttribute.PROVIDER_WORK_TITLE,
        DiagnosticAttribute.PROVIDER_WORK_ALIASES,
        DiagnosticAttribute.PROVIDER_LOOKUP_TITLE,
        -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { text ->
                text.value.length in 1..512 &&
                    text.value.all { char -> char.code >= 32 && char.code != 127 && char.code != 0x2028 }
            }

        DiagnosticAttribute.LANGUAGE -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { languageTagPattern.matches(it.value) }
        DiagnosticAttribute.PROVIDER_ID,
        DiagnosticAttribute.ADDON_ID,
        -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { providerIdPattern.matches(it.value) }
        DiagnosticAttribute.PROVIDER_VERSION_NAME -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { providerVersionPattern.matches(it.value) }
        DiagnosticAttribute.PROVIDER_ERROR_CODE -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { providerErrorCodePattern.matches(it.value) }
        DiagnosticAttribute.CANONICAL_TITLE_REF,
        DiagnosticAttribute.MIHON_MANGA_REF,
        -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { pseudonymousReferencePattern.matches(it.value) }

        DiagnosticAttribute.CONFIDENCE_SCORE -> (value as? DiagnosticAttributeValue.Number)
            ?.takeIf { it.value in 0..100 }
        DiagnosticAttribute.CONFIDENCE_BUCKET -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticConfidenceBucket }
        DiagnosticAttribute.ERROR_CATEGORY -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticErrorCategory }
        DiagnosticAttribute.HTTP_STATUS -> (value as? DiagnosticAttributeValue.Number)
            ?.takeIf { it.value in 100L..599L }
        DiagnosticAttribute.CANDIDATE_TYPE -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticCandidateType }
        DiagnosticAttribute.CACHE_STATUS -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticCacheStatus }
        DiagnosticAttribute.INVARIANT_CODE -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticInvariantCode }
    }

    private fun String.toAttributeKey(): String = lowercase()

    private fun String.isSafeUuid(): Boolean = uuidPattern.matches(this)

    private const val MAX_DURATION_MILLIS = 24 * 60 * 60 * 1_000L
    private const val MAX_COUNT = 1_000_000L
}

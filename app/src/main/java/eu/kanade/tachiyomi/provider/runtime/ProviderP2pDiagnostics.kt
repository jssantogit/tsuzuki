package eu.kanade.tachiyomi.provider.runtime

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import java.security.MessageDigest
import java.security.SecureRandom

internal enum class ProviderP2pDiagnosticEvent {
    ACQUISITION_REQUESTED,
    HOST_RESPONSE,
    JOB_CREATED,
    JOB_REUSED,
    JOB_REJECTED,
    WORKER_STARTED,
    NATIVE_SUPPORT,
    SESSION_START,
    SESSION_READY,
    SESSION_FAILED,
    NATIVE_ALERT_SUMMARY,
    METADATA_CACHE_LOOKUP,
    METADATA_START,
    METADATA_READY,
    METADATA_FAILED,
    IDENTITY_CHECK,
    FILE_SELECTION_CHECK,
    DOWNLOAD_SUBMITTED,
    HANDLE_WAIT,
    HANDLE_READY,
    HANDLE_FAILED,
    TRANSFER_PROGRESS,
    TRANSFER_FAILED,
    TRANSFER_TIMEOUT,
    SELECTED_FILE_CHECK,
    ADOPTION_START,
    ADOPTION_STAGE,
    ADOPTION_READY,
    ADOPTION_FAILED,
    JOB_COMPLETED,
    JOB_CANCELLED,
    READER_ACQUISITION_STATE,
    READER_ARTIFACT_READY,
    READER_ACQUISITION_FAILED,
}

/**
 * Privacy-safe Direct P2P diagnostics.
 *
 * Content-derived values (info hashes, magnets, URLs, paths, titles, peer addresses and
 * exception messages) are intentionally not accepted. Operation IDs are reduced to a
 * per-process salted reference so exported logs can correlate a single run without exposing
 * a stable content fingerprint.
 *
 * Production recording is strictly best-effort: formatting, hashing or logging failures are
 * swallowed so observability cannot change an acquisition result.
 */
internal object ProviderP2pDiagnostics {

    @Volatile
    private var structuredRecorder: StructuredDiagnosticRecorder? = null

    /** Installed once in the non-isolated Host process; never forwarded to Provider JS/Binder. */
    fun installRecorder(recorder: StructuredDiagnosticRecorder?) {
        structuredRecorder = recorder
    }

    private val salt: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ByteArray(32).also(SecureRandom()::nextBytes)
    }

    fun record(
        event: ProviderP2pDiagnosticEvent,
        operationId: String,
        jobId: String? = null,
        providerId: String? = null,
        codes: Map<String, String> = emptyMap(),
        numbers: Map<String, Long> = emptyMap(),
        flags: Map<String, Boolean> = emptyMap(),
        exceptionClass: String? = null,
        priority: LogPriority = LogPriority.INFO,
    ) {
        val line = runCatching {
            format(
                event = event,
                operationId = operationId,
                jobId = jobId,
                providerId = providerId,
                codes = codes,
                numbers = numbers,
                flags = flags,
                exceptionClass = exceptionClass,
            )
        }.getOrNull() ?: return

        // Independent best-effort mirrors: a logcat failure must not suppress durable evidence.
        runCatching { logcat(priority) { line } }
        val recorder = structuredRecorder ?: return
        runCatching {
            recorder.record(
                StructuredDiagnosticEvent(
                    timestampMillis = System.currentTimeMillis().coerceAtLeast(0L),
                    severity = when (priority) {
                        LogPriority.ERROR -> DiagnosticSeverity.ERROR
                        LogPriority.WARN -> DiagnosticSeverity.WARN
                        LogPriority.DEBUG -> DiagnosticSeverity.DEBUG
                        else -> DiagnosticSeverity.INFO
                    },
                    subsystem = DiagnosticSubsystem.CONTENT,
                    name = DiagnosticEventName.PROVIDER_P2P_EVENT,
                    sessionId = recorder.sessionId,
                    operationId = null,
                    stage = DiagnosticStage.SUMMARY,
                    outcome = when (event) {
                        ProviderP2pDiagnosticEvent.METADATA_FAILED,
                        ProviderP2pDiagnosticEvent.HANDLE_FAILED,
                        ProviderP2pDiagnosticEvent.TRANSFER_FAILED,
                        ProviderP2pDiagnosticEvent.ADOPTION_FAILED,
                        ProviderP2pDiagnosticEvent.READER_ACQUISITION_FAILED,
                        ProviderP2pDiagnosticEvent.SESSION_FAILED,
                        -> DiagnosticOutcome.FAILED
                        ProviderP2pDiagnosticEvent.JOB_CANCELLED -> DiagnosticOutcome.CANCELLED
                        ProviderP2pDiagnosticEvent.JOB_REJECTED -> DiagnosticOutcome.REJECTED
                        ProviderP2pDiagnosticEvent.TRANSFER_TIMEOUT -> DiagnosticOutcome.TIMEOUT
                        else -> DiagnosticOutcome.PARTIAL
                    },
                    attributes = mapOf(
                        "provider_p2p_line" to DiagnosticAttributeValue.Text(line),
                    ),
                ),
            )
        }
    }

    internal fun formatForTest(
        event: ProviderP2pDiagnosticEvent,
        operationId: String,
        jobId: String? = null,
        providerId: String? = null,
        codes: Map<String, String> = emptyMap(),
        numbers: Map<String, Long> = emptyMap(),
        flags: Map<String, Boolean> = emptyMap(),
        exceptionClass: String? = null,
    ): String = format(
        event = event,
        operationId = operationId,
        jobId = jobId,
        providerId = providerId,
        codes = codes,
        numbers = numbers,
        flags = flags,
        exceptionClass = exceptionClass,
    )

    private fun format(
        event: ProviderP2pDiagnosticEvent,
        operationId: String,
        jobId: String?,
        providerId: String?,
        codes: Map<String, String>,
        numbers: Map<String, Long>,
        flags: Map<String, Boolean>,
        exceptionClass: String?,
    ): String = buildString {
        append("provider_p2p event=")
        append(event.name)
        append(" opRef=")
        append(operationReference(operationId))

        jobId?.takeIf(JOB_ID::matches)?.let {
            append(" jobId=")
            append(it.lowercase())
        }
        providerId?.takeIf(PROVIDER_ID::matches)?.let {
            append(" providerId=")
            append(it)
        }

        codes.toSortedMap().forEach { (key, value) ->
            if (FIELD_KEY.matches(key) && CODE.matches(value)) {
                append(' ')
                append(key)
                append('=')
                append(value)
            }
        }
        numbers.toSortedMap().forEach { (key, value) ->
            if (FIELD_KEY.matches(key) && value >= 0L) {
                append(' ')
                append(key)
                append('=')
                append(value)
            }
        }
        flags.toSortedMap().forEach { (key, value) ->
            if (FIELD_KEY.matches(key)) {
                append(' ')
                append(key)
                append('=')
                append(value)
            }
        }
        exceptionClass?.takeIf(EXCEPTION_CLASS::matches)?.let {
            append(" exceptionClass=")
            append(it)
        }
    }

    private fun operationReference(operationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(operationId.encodeToByteArray())
        return digest.digest()
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private val JOB_ID = Regex("^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
    private val PROVIDER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val FIELD_KEY = Regex("^[A-Za-z][A-Za-z0-9]{0,31}$")
    private val CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val EXCEPTION_CLASS = Regex("^[A-Za-z_][A-Za-z0-9_.$]{0,127}$")
}

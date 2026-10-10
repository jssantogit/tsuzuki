package eu.kanade.tachiyomi.provider.runtime

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
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
        runCatching {
            val line = format(
                event = event,
                operationId = operationId,
                jobId = jobId,
                providerId = providerId,
                codes = codes,
                numbers = numbers,
                flags = flags,
                exceptionClass = exceptionClass,
            )
            logcat(priority) { line }
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

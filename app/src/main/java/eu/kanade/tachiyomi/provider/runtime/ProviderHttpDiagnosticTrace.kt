package eu.kanade.tachiyomi.provider.runtime

import tachiyomi.core.provider.runtime.ProviderHttpDiagnostic
import tachiyomi.core.provider.runtime.ProviderHttpDiagnosticPhase
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderHttpFailure
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent

/**
 * Host-mediated network telemetry for user-started detailed capture only.
 *
 * Only closed failure categories and a bounded HTTP status cross to the
 * structured recorder. The upstream URL, path, query/VRF, host, cookies,
 * response content and exception messages never enter diagnostic history.
 */
internal fun ProviderHttpDiagnostic.toStructuredDiagnosticEvent(
    providerId: String,
    sessionId: String,
    timestampMillis: Long,
): StructuredDiagnosticEvent {
    val category = failureFamily?.let {
        runCatching { DiagnosticProviderHttpFailure.valueOf(it.name) }
            .getOrDefault(DiagnosticProviderHttpFailure.UNKNOWN)
    }
    val details = buildMap<String, DiagnosticAttributeValue> {
        put("provider_id", DiagnosticAttributeValue.Text(providerId))
        if (statusCode != null && statusCode in 100..599) {
            put("http_status", DiagnosticAttributeValue.Number(statusCode.toLong()))
        }
        if (category != null) {
            put("provider_http_failure", DiagnosticAttributeValue.Code(category))
        }
    }
    return StructuredDiagnosticEvent(
        timestampMillis = timestampMillis.coerceAtLeast(0),
        severity = if (phase ==
            ProviderHttpDiagnosticPhase.FAILED
        ) {
            DiagnosticSeverity.WARN
        } else {
            DiagnosticSeverity.DEBUG
        },
        subsystem = DiagnosticSubsystem.NETWORK,
        name = DiagnosticEventName.PROVIDER_HTTP_REQUEST,
        sessionId = sessionId,
        operationId = null,
        stage = DiagnosticStage.READ,
        outcome = when (phase) {
            ProviderHttpDiagnosticPhase.STARTED -> DiagnosticOutcome.STARTED
            ProviderHttpDiagnosticPhase.SUCCEEDED -> DiagnosticOutcome.SUCCEEDED
            ProviderHttpDiagnosticPhase.FAILED -> DiagnosticOutcome.FAILED
        },
        durationMillis = elapsedMs.coerceIn(0, 86_400_000),
        attributes = details,
    )
}

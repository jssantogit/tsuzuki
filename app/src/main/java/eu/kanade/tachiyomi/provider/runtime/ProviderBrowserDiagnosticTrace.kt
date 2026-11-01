package eu.kanade.tachiyomi.provider.runtime

import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderBrowserFailure
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent

internal enum class ProviderBrowserDiagnosticPhase {
    STARTED,
    SUCCEEDED,
    FAILED,
}

internal enum class ProviderBrowserFailure {
    TIMEOUT,
    NAVIGATION,
    TLS,
    RENDERER_GONE,
    WEBVIEW_UNSUPPORTED,
    SELECTOR_MISSING,
    CANCELLED,
    UNKNOWN,
}

internal data class ProviderBrowserDiagnostic(
    val phase: ProviderBrowserDiagnosticPhase,
    val elapsedMs: Long,
    val operationId: String,
    val failure: ProviderBrowserFailure? = null,
)

/** Matches only known, internally authored error messages. Never store the message itself. */
internal fun classifyProviderBrowserFailure(error: Throwable): ProviderBrowserFailure = when (error.message) {
    "Provider browser operation timed out" -> ProviderBrowserFailure.TIMEOUT
    "Provider browser navigation failed" -> ProviderBrowserFailure.NAVIGATION
    "Provider browser TLS validation failed" -> ProviderBrowserFailure.TLS
    "Provider browser renderer exited" -> ProviderBrowserFailure.RENDERER_GONE
    "Provider browser requires WebView multi-profile support" -> ProviderBrowserFailure.WEBVIEW_UNSUPPORTED
    "Provider browser selector did not resolve to text" -> ProviderBrowserFailure.SELECTOR_MISSING
    "Provider browser operation was cancelled",
    "Provider browser broker is closed",
    -> ProviderBrowserFailure.CANCELLED
    else -> ProviderBrowserFailure.UNKNOWN
}

/** Does not include URL, host, selector, headers, source text, or exception messages. */
internal fun ProviderBrowserDiagnostic.toStructuredDiagnosticEvent(
    providerId: String,
    sessionId: String,
    timestampMillis: Long,
): StructuredDiagnosticEvent {
    val attributes = buildMap<String, DiagnosticAttributeValue> {
        put("provider_id", DiagnosticAttributeValue.Text(providerId))
        failure?.let {
            put(
                "provider_browser_failure",
                DiagnosticAttributeValue.Code(DiagnosticProviderBrowserFailure.valueOf(it.name)),
            )
        }
    }
    return StructuredDiagnosticEvent(
        timestampMillis = timestampMillis.coerceAtLeast(0),
        severity = if (phase == ProviderBrowserDiagnosticPhase.FAILED) {
            DiagnosticSeverity.WARN
        } else {
            DiagnosticSeverity.DEBUG
        },
        subsystem = DiagnosticSubsystem.NETWORK,
        name = DiagnosticEventName.PROVIDER_BROWSER_REQUEST,
        sessionId = sessionId,
        operationId = operationId,
        stage = DiagnosticStage.READ,
        outcome = when (phase) {
            ProviderBrowserDiagnosticPhase.STARTED -> DiagnosticOutcome.STARTED
            ProviderBrowserDiagnosticPhase.SUCCEEDED -> DiagnosticOutcome.SUCCEEDED
            ProviderBrowserDiagnosticPhase.FAILED -> DiagnosticOutcome.FAILED
        },
        durationMillis = elapsedMs.coerceIn(0, 86_400_000),
        attributes = attributes,
    )
}

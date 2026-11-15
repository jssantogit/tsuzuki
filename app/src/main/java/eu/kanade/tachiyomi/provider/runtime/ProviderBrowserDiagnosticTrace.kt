package eu.kanade.tachiyomi.provider.runtime

import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderBrowserFailure
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent

enum class ProviderBrowserDiagnosticPhase {
    STARTED,
    SUCCEEDED,
    FAILED,
}

enum class ProviderBrowserFailure {
    UPSTREAM_CHALLENGE,
    HTTP_REJECTED,
    TIMEOUT,
    NAVIGATION,
    TLS,
    RENDERER_GONE,
    WEBVIEW_UNSUPPORTED,
    SELECTOR_MISSING,
    CANCELLED,
    UNKNOWN,
}

data class ProviderBrowserDiagnostic(
    val phase: ProviderBrowserDiagnosticPhase,
    val elapsedMs: Long,
    val operationId: String,
    val failure: ProviderBrowserFailure? = null,
    val sourceHost: String? = null,
)

/** Matches only known, internally authored error messages. Never store the message itself. */
internal fun classifyProviderBrowserFailure(error: Throwable): ProviderBrowserFailure = when (error.message) {
    "Provider browser upstream challenge" -> ProviderBrowserFailure.UPSTREAM_CHALLENGE
    "Provider browser HTTP response rejected" -> ProviderBrowserFailure.HTTP_REJECTED
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
        if (providerId == "app.tsuzuki.mangaall") {
            sourceHost?.let(::sourceFamilyForMangaAllHost)?.let { source ->
                put("mangaall_network_source", DiagnosticAttributeValue.Code(source))
            }
        }
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

/**
 * Fail closed on main-frame HTTP errors. A challenge marker means that the
 * browser reached an interstitial, not that the source API returned JSON.
 * Ignore all response bodies and all other upstream header values.
 */
internal fun classifyProviderBrowserMainFrameHttpFailure(
    statusCode: Int,
    responseHeaders: Map<String, String>,
): ProviderBrowserFailure? {
    if (statusCode !in 400..599) return null
    val challenge = responseHeaders.any { (name, value) ->
        name.equals("cf-mitigated", ignoreCase = true) &&
            value.trim().equals("challenge", ignoreCase = true)
    }
    return if (challenge) ProviderBrowserFailure.UPSTREAM_CHALLENGE else ProviderBrowserFailure.HTTP_REJECTED
}

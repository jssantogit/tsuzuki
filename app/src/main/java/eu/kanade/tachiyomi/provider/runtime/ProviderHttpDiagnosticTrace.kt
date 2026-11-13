package eu.kanade.tachiyomi.provider.runtime

import tachiyomi.core.provider.runtime.ProviderHttpDiagnostic
import tachiyomi.core.provider.runtime.ProviderHttpDiagnosticPhase
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticMangaAllNetworkSource
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
        if (providerId == "app.tsuzuki.mangaall") {
            sourceFamilyForMangaAllHost(host)?.let { source ->
                put("mangaall_network_source", DiagnosticAttributeValue.Code(source))
            }
        }
        statusCode?.takeIf { it in 100..599 }?.let { code ->
            put("http_status", DiagnosticAttributeValue.Number(code.toLong()))
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
        operationId = operationId,
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

// No arbitrary Host-provided network hostname enters diagnostics. This closed
// classification is limited to MangaAll's declared first-party metadata and
// image origins; unexpected hosts remain unclassified and are never exported.
private fun sourceFamilyForMangaAllHost(host: String): DiagnosticMangaAllNetworkSource? {
    val value = host.lowercase()
    return when {
        value == "api.mangadex.org" ||
            value == "uploads.mangadex.org" ||
            value.endsWith(".mangadex.network") -> DiagnosticMangaAllNetworkSource.MANGADEX
        value == "mangadot.net" -> DiagnosticMangaAllNetworkSource.MANGADOT
        value == "mangafire.to" ||
            value.endsWith(".mangafire.to") ||
            listOf("mfcdn.cc", "mfcdn.nl", "mfcdn1.xyz", "mfcdn2.xyz", "mfcdn3.xyz").any {
                value == it || value.endsWith(".$it")
            } -> DiagnosticMangaAllNetworkSource.MANGAFIRE
        else -> null
    }
}

package eu.kanade.tachiyomi.provider.runtime

import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticDiscoveryPhase
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder

class ProviderDiscoveryDiagnosticLogSink(
    private val recorder: StructuredDiagnosticRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun record(providerId: String, message: String) {
        if (!PROVIDER_ID.matches(providerId)) return

        val started = START_PATTERN.matchEntire(message)
        if (started != null) {
            val attempt = started.groupValues[1].toAttempt() ?: return
            val phase = started.groupValues[2].toPhase() ?: return
            recorder.record(
                event(
                    providerId = providerId,
                    attempt = attempt,
                    phase = phase,
                    outcome = DiagnosticOutcome.STARTED,
                ),
            )
            return
        }

        val completed = END_PATTERN.matchEntire(message) ?: return
        val attempt = completed.groupValues[1].toAttempt() ?: return
        val phase = completed.groupValues[2].toPhase() ?: return
        val raw = completed.groupValues[3].toCount() ?: return
        val accepted = completed.groupValues[4].toCount() ?: return
        val durationMillis = completed.groupValues[5].toDuration() ?: return
        if (accepted > raw) return

        recorder.record(
            event(
                providerId = providerId,
                attempt = attempt,
                phase = phase,
                outcome = if (accepted > 0L) DiagnosticOutcome.CANDIDATES else DiagnosticOutcome.EMPTY,
                durationMillis = durationMillis,
                raw = raw,
                accepted = accepted,
            ),
        )
    }

    private fun event(
        providerId: String,
        attempt: Int,
        phase: DiagnosticDiscoveryPhase,
        outcome: DiagnosticOutcome,
        durationMillis: Long? = null,
        raw: Long? = null,
        accepted: Long? = null,
    ): StructuredDiagnosticEvent =
        StructuredDiagnosticEvent(
            timestampMillis = clock(),
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.CONTENT,
            name = DiagnosticEventName.PROVIDER_DISCOVERY_ATTEMPT,
            sessionId = recorder.sessionId,
            operationId = null,
            stage = DiagnosticStage.SEARCH,
            outcome = outcome,
            durationMillis = durationMillis,
            attempt = attempt,
            attributes = buildMap {
                put("provider_id", DiagnosticAttributeValue.Text(providerId))
                put("discovery_phase", DiagnosticAttributeValue.Code(phase))
                if (raw != null && accepted != null) {
                    put("item_count", DiagnosticAttributeValue.Number(raw))
                    put("accepted_count", DiagnosticAttributeValue.Number(accepted))
                    put("rejected_count", DiagnosticAttributeValue.Number(raw - accepted))
                }
            },
        )

    private fun String.toAttempt(): Int? =
        toIntOrNull()?.takeIf { it in 1..MAX_ATTEMPTS }

    private fun String.toCount(): Long? =
        toLongOrNull()?.takeIf { it in 0..MAX_COUNT }

    private fun String.toDuration(): Long? =
        toLongOrNull()?.takeIf { it in 0..MAX_DURATION_MILLIS }

    private fun String.toPhase(): DiagnosticDiscoveryPhase? = when (this) {
        "primary_narrow" -> DiagnosticDiscoveryPhase.PRIMARY_NARROW
        "primary_fallback" -> DiagnosticDiscoveryPhase.PRIMARY_FALLBACK
        "alias_narrow" -> DiagnosticDiscoveryPhase.ALIAS_NARROW
        "alias_fallback" -> DiagnosticDiscoveryPhase.ALIAS_FALLBACK
        else -> null
    }

    private companion object {
        val PROVIDER_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
        val START_PATTERN = Regex(
            "^TSZ_DISCOVERY_V1 state=start attempt=(\\d+) " +
                "phase=(primary_narrow|primary_fallback|alias_narrow|alias_fallback)$",
        )
        val END_PATTERN = Regex(
            "^TSZ_DISCOVERY_V1 state=end attempt=(\\d+) " +
                "phase=(primary_narrow|primary_fallback|alias_narrow|alias_fallback) " +
                "raw=(\\d+) accepted=(\\d+) duration_ms=(\\d+)$",
        )
        const val MAX_ATTEMPTS = 64
        const val MAX_COUNT = 1_000_000L
        const val MAX_DURATION_MILLIS = 60_000L
    }
}

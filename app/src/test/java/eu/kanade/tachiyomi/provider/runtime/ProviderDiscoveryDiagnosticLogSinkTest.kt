package eu.kanade.tachiyomi.provider.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticDiscoveryPhase
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer

class ProviderDiscoveryDiagnosticLogSinkTest {

    @Test
    fun `bridges only allowlisted discovery metrics into sanitized diagnostics`() {
        val recorder = RecordingDiagnosticRecorder()
        val sink = ProviderDiscoveryDiagnosticLogSink(
            recorder = recorder,
            clock = { 1234L },
        )
        val invocationId = "provider:torrent.search:11111111-2222-3333-8444-555555555555"

        sink.record(
            providerId = "app.tsuzuki.nyaa",
            invocationId = invocationId,
            message = "TSZ_DISCOVERY_V1 state=start attempt=1 phase=primary_narrow",
        )
        sink.record(
            providerId = "app.tsuzuki.nyaa",
            invocationId = invocationId,
            message = "TSZ_DISCOVERY_V1 state=end attempt=1 phase=primary_narrow raw=2 accepted=1 duration_ms=120",
        )
        sink.record(
            providerId = "app.tsuzuki.nyaa",
            invocationId = invocationId,
            message = "Diagnostic Test https://nyaa.si/?q=secret",
        )
        sink.record(
            providerId = "app.tsuzuki.nyaa",
            invocationId = invocationId,
            message = "TSZ_DISCOVERY_V1 state=end attempt=1 phase=primary_narrow raw=2 accepted=1 duration_ms=120 title=secret",
        )

        assertEquals(2, recorder.events.size)

        val started = recorder.events[0]
        assertEquals(DiagnosticEventName.PROVIDER_DISCOVERY_ATTEMPT, started.name)
        assertEquals(DiagnosticStage.SEARCH, started.stage)
        assertEquals(DiagnosticOutcome.STARTED, started.outcome)
        assertEquals(1, started.attempt)
        assertEquals("11111111-2222-3333-8444-555555555555", started.operationId)
        assertEquals(DiagnosticAttributeValue.Text("app.tsuzuki.nyaa"), started.attributes["provider_id"])
        assertEquals(
            DiagnosticAttributeValue.Code(DiagnosticDiscoveryPhase.PRIMARY_NARROW),
            started.attributes["discovery_phase"],
        )
        assertNotNull(StructuredDiagnosticSanitizer.sanitize(started))

        val completed = recorder.events[1]
        assertEquals(DiagnosticEventName.PROVIDER_DISCOVERY_ATTEMPT, completed.name)
        assertEquals(DiagnosticOutcome.CANDIDATES, completed.outcome)
        assertEquals(120L, completed.durationMillis)
        assertEquals(DiagnosticAttributeValue.Number(2), completed.attributes["item_count"])
        assertEquals(DiagnosticAttributeValue.Number(1), completed.attributes["accepted_count"])
        assertNotNull(StructuredDiagnosticSanitizer.sanitize(completed))
    }

    private class RecordingDiagnosticRecorder : StructuredDiagnosticRecorder {
        val events = mutableListOf<StructuredDiagnosticEvent>()

        override val sessionId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null

        override fun mihonMangaReference(mihonMangaId: Long): String? = null

        override fun record(event: StructuredDiagnosticEvent) {
            events += event
        }
    }
}

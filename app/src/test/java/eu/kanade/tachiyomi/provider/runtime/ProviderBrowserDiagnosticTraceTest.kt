package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderBrowserFailure
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer

class ProviderBrowserDiagnosticTraceTest {
    private val session = "35d4ea34-f729-4336-b6ce-d6277ec3ac7c"
    private val operation = "028b4f42-3b61-42eb-9ae3-02d846dd248a"

    @Test
    fun `browser fallback deadline exposes typed failure without upstream URL or source text`() {
        val failed = ProviderBrowserDiagnostic(
            phase = ProviderBrowserDiagnosticPhase.FAILED,
            elapsedMs = 15000,
            failure = ProviderBrowserFailure.TIMEOUT,
            operationId = operation,
        )
        val event = failed.toStructuredDiagnosticEvent("app.tsuzuki.mangafire", session, 1793493480436)
        val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
        sanitized.name shouldBe DiagnosticEventName.PROVIDER_BROWSER_REQUEST
        sanitized.outcome shouldBe DiagnosticOutcome.FAILED
        sanitized.operationId shouldBe operation
        sanitized.durationMillis shouldBe 15000
        sanitized.attributes[DiagnosticAttribute.PROVIDER_BROWSER_FAILURE] shouldBe
            DiagnosticAttributeValue.Code(DiagnosticProviderBrowserFailure.TIMEOUT)
        sanitized.attributes.size shouldBe 2
    }

    @Test
    fun `browser fallback startup and clean return preserve phase and shared operation id`() {
        for (phase in listOf(ProviderBrowserDiagnosticPhase.STARTED, ProviderBrowserDiagnosticPhase.SUCCEEDED)) {
            val event = ProviderBrowserDiagnostic(
                phase = phase,
                elapsedMs = 7,
                operationId = operation,
            ).toStructuredDiagnosticEvent("app.tsuzuki.mangafire", session, 2000)
            val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
            sanitized.operationId shouldBe operation
            sanitized.outcome shouldBe when (phase) {
                ProviderBrowserDiagnosticPhase.STARTED -> DiagnosticOutcome.STARTED
                ProviderBrowserDiagnosticPhase.SUCCEEDED -> DiagnosticOutcome.SUCCEEDED
                ProviderBrowserDiagnosticPhase.FAILED -> DiagnosticOutcome.FAILED
            }
            sanitized.attributes.size shouldBe 1
        }
    }
}

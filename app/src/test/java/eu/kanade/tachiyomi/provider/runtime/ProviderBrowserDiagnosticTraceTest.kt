package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticMangaAllNetworkSource
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

    @Test
    fun `main frame HTTP challenge is not a successful browser response`() {
        classifyProviderBrowserMainFrameHttpFailure(
            403,
            mapOf("Cf-Mitigated" to "challenge"),
        ) shouldBe ProviderBrowserFailure.UPSTREAM_CHALLENGE
        classifyProviderBrowserMainFrameHttpFailure(
            403,
            emptyMap(),
        ) shouldBe ProviderBrowserFailure.HTTP_REJECTED
        classifyProviderBrowserMainFrameHttpFailure(
            503,
            mapOf("cf-mitigated" to "managed"),
        ) shouldBe ProviderBrowserFailure.HTTP_REJECTED
        classifyProviderBrowserMainFrameHttpFailure(
            200,
            mapOf("cf-mitigated" to "challenge"),
        ) shouldBe null
    }

    @Test
    fun `browser source family is closed for MangaAll and never exports raw host`() {
        val event = ProviderBrowserDiagnostic(
            phase = ProviderBrowserDiagnosticPhase.FAILED,
            elapsedMs = 12,
            failure = ProviderBrowserFailure.UPSTREAM_CHALLENGE,
            operationId = operation,
            sourceHost = "mangafire.to",
        ).toStructuredDiagnosticEvent("app.tsuzuki.mangaall", session, 2000)
        val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
        sanitized.attributes[DiagnosticAttribute.MANGAALL_NETWORK_SOURCE] shouldBe
            DiagnosticAttributeValue.Code(DiagnosticMangaAllNetworkSource.MANGAFIRE)
        sanitized.attributes[DiagnosticAttribute.PROVIDER_BROWSER_FAILURE] shouldBe
            DiagnosticAttributeValue.Code(DiagnosticProviderBrowserFailure.UPSTREAM_CHALLENGE)
        sanitized.attributes.size shouldBe 3
        val foreign = ProviderBrowserDiagnostic(
            phase = ProviderBrowserDiagnosticPhase.FAILED,
            elapsedMs = 12,
            failure = ProviderBrowserFailure.HTTP_REJECTED,
            operationId = operation,
            sourceHost = "credentials.attacker.example",
        ).toStructuredDiagnosticEvent("app.tsuzuki.mangaall", session, 2000)
        val safeForeign = requireNotNull(StructuredDiagnosticSanitizer.sanitize(foreign))
        safeForeign.attributes.containsKey(DiagnosticAttribute.MANGAALL_NETWORK_SOURCE) shouldBe false
        safeForeign.attributes.values.toString().contains("attacker") shouldBe false
    }

    @Test
    fun `browser challenge and rejected HTTP messages are classified without upstream exception text`() {
        classifyProviderBrowserFailure(
            tachiyomi.core.provider.runtime.ProviderHostServiceException("Provider browser upstream challenge"),
        ) shouldBe ProviderBrowserFailure.UPSTREAM_CHALLENGE
        classifyProviderBrowserFailure(
            tachiyomi.core.provider.runtime.ProviderHostServiceException("Provider browser HTTP response rejected"),
        ) shouldBe ProviderBrowserFailure.HTTP_REJECTED
        classifyProviderBrowserFailure(
            tachiyomi.core.provider.runtime.ProviderHostServiceException("untrusted HTML payload"),
        ) shouldBe ProviderBrowserFailure.UNKNOWN
    }

}

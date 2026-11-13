package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.runtime.ProviderHttpDiagnostic
import tachiyomi.core.provider.runtime.ProviderHttpDiagnosticPhase
import tachiyomi.core.provider.runtime.ProviderHttpFailureFamily
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticMangaAllNetworkSource
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderHttpFailure
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer

class ProviderHttpDiagnosticTraceTest {
    private val session = "35d4ea34-f729-4336-b6ce-d6277ec3ac7c"

    @Test
    fun `provider HTTP timeout is exported as a typed sanitized event, no URL or headers`() {
        val diagnostic = ProviderHttpDiagnostic(
            phase = ProviderHttpDiagnosticPhase.FAILED,
            host = "mangafire.to",
            elapsedMs = 19675,
            timeoutMs = 20000,
            statusCode = null,
            failureFamily = ProviderHttpFailureFamily.HOST_DEADLINE,
            operationId = "5af72325-6771-4599-8d20-1e72fd79e375",
        )
        val event = diagnostic.toStructuredDiagnosticEvent(
            providerId = "app.tsuzuki.mangafire",
            sessionId = session,
            timestampMillis = 1793493480436,
        )
        val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
        sanitized.name shouldBe DiagnosticEventName.PROVIDER_HTTP_REQUEST
        sanitized.operationId shouldBe "5af72325-6771-4599-8d20-1e72fd79e375"
        sanitized.outcome shouldBe DiagnosticOutcome.FAILED
        sanitized.durationMillis shouldBe 19675
        sanitized.attributes[DiagnosticAttribute.PROVIDER_ID] shouldBe
            DiagnosticAttributeValue.Text("app.tsuzuki.mangafire")
        sanitized.attributes[DiagnosticAttribute.PROVIDER_HTTP_FAILURE] shouldBe
            DiagnosticAttributeValue.Code(DiagnosticProviderHttpFailure.HOST_DEADLINE)
        sanitized.attributes[DiagnosticAttribute.HTTP_STATUS] shouldBe null
        sanitized.attributes.size shouldBe 2
    }

    @Test
    fun `MangaAll HTTP diagnostics distinguish only approved internal sources`() {
        val sources = listOf(
            "api.mangadex.org" to DiagnosticMangaAllNetworkSource.MANGADEX,
            "uploads.mangadex.org" to DiagnosticMangaAllNetworkSource.MANGADEX,
            "edge.mangadex.network" to DiagnosticMangaAllNetworkSource.MANGADEX,
            "mangadot.net" to DiagnosticMangaAllNetworkSource.MANGADOT,
            "mangafire.to" to DiagnosticMangaAllNetworkSource.MANGAFIRE,
            "s1.mangafire.to" to DiagnosticMangaAllNetworkSource.MANGAFIRE,
            "cdn.mfcdn.cc" to DiagnosticMangaAllNetworkSource.MANGAFIRE,
            "mfcdn2.xyz" to DiagnosticMangaAllNetworkSource.MANGAFIRE,
        )
        for ((host, source) in sources) {
            val event = ProviderHttpDiagnostic(
                phase = ProviderHttpDiagnosticPhase.SUCCEEDED,
                host = host,
                elapsedMs = 270,
                timeoutMs = 20_000,
                statusCode = 403,
            ).toStructuredDiagnosticEvent("app.tsuzuki.mangaall", session, 2000)
            val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
            sanitized.attributes[DiagnosticAttribute.MANGAALL_NETWORK_SOURCE] shouldBe
                DiagnosticAttributeValue.Code(source)
            sanitized.attributes[DiagnosticAttribute.HTTP_STATUS] shouldBe DiagnosticAttributeValue.Number(403)
            sanitized.attributes.size shouldBe 3
        }
    }

    @Test
    fun `network diagnostic omits unmapped host and never labels another provider MangaAll`() {
        for ((provider, host) in listOf(
            "app.tsuzuki.mangaall" to "secret.example",
            "app.tsuzuki.mangaall" to "mangafire.to.evil.example",
            "app.tsuzuki.other" to "mangafire.to",
        )) {
            val event = ProviderHttpDiagnostic(
                phase = ProviderHttpDiagnosticPhase.SUCCEEDED,
                host = host,
                elapsedMs = 270,
                timeoutMs = 20_000,
                statusCode = 403,
            ).toStructuredDiagnosticEvent(provider, session, 2000)
            val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
            sanitized.attributes[DiagnosticAttribute.MANGAALL_NETWORK_SOURCE] shouldBe null
            sanitized.attributes.size shouldBe 2
        }
    }

    @Test
    fun `HTTP phase and status preserve response evidence without leaking host`() {
        val response = ProviderHttpDiagnostic(
            phase = ProviderHttpDiagnosticPhase.SUCCEEDED,
            host = "secret.example",
            elapsedMs = 115,
            timeoutMs = 20000,
            statusCode = 403,
        )
        val event = response.toStructuredDiagnosticEvent("app.tsuzuki.mangafire", session, 2000)
        val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
        sanitized.outcome shouldBe DiagnosticOutcome.SUCCEEDED
        sanitized.attributes[DiagnosticAttribute.HTTP_STATUS] shouldBe DiagnosticAttributeValue.Number(403)
        sanitized.attributes[DiagnosticAttribute.PROVIDER_HTTP_FAILURE] shouldBe null
        sanitized.attributes.size shouldBe 2
    }
}

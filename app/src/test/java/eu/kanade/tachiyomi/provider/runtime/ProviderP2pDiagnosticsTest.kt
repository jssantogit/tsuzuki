package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProviderP2pDiagnosticsTest {

    @Test
    fun `diagnostics expose only bounded technical fields and pseudonymous operation ref`() {
        val line = ProviderP2pDiagnostics.formatForTest(
            event = ProviderP2pDiagnosticEvent.METADATA_FAILED,
            operationId = "reader:0123456789abcdef01234567:3",
            jobId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            providerId = "app.tsuzuki.direct-p2p",
            codes = mapOf(
                "source" to "MAGNET",
                "failure" to "METADATA_UNAVAILABLE",
            ),
            numbers = mapOf(
                "elapsedMs" to 30_001L,
            ),
            flags = mapOf(
                "nativeSupported" to true,
            ),
            exceptionClass = IllegalStateException::class.qualifiedName,
        )

        line.contains("provider_p2p") shouldBe true
        line.contains("event=METADATA_FAILED") shouldBe true
        line.contains("jobId=865624e0-50f1-41c9-81eb-9a68fc9e50a4") shouldBe true
        line.contains("providerId=app.tsuzuki.direct-p2p") shouldBe true
        line.contains("source=MAGNET") shouldBe true
        line.contains("failure=METADATA_UNAVAILABLE") shouldBe true
        line.contains("elapsedMs=30001") shouldBe true
        line.contains("nativeSupported=true") shouldBe true
        line.contains("exceptionClass=") shouldBe true
        line.contains("IllegalStateException") shouldBe true

        line.contains("reader:0123456789abcdef01234567:3") shouldBe false
        line.contains("0123456789abcdef01234567") shouldBe false
        Regex("opRef=[0-9a-f]{16}").containsMatchIn(line) shouldBe true
    }

    @Test
    fun `diagnostics reject unsafe text instead of serializing it`() {
        val line = ProviderP2pDiagnostics.formatForTest(
            event = ProviderP2pDiagnosticEvent.HOST_RESPONSE,
            operationId = "reader:0123456789abcdef01234567:3",
            jobId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            providerId = "app.tsuzuki.direct-p2p",
            codes = mapOf(
                "failure" to "https://private.example/?token=secret",
                "status" to "PENDING",
            ),
            exceptionClass = "java.lang.IllegalStateException: secret",
        )

        line.contains("status=PENDING") shouldBe true
        line.contains("private.example") shouldBe false
        line.contains("secret") shouldBe false
        line.contains("exceptionClass=") shouldBe false
    }
}

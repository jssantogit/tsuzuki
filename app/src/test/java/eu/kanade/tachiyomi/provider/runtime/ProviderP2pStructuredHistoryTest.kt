package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.diagnostics.StructuredDiagnosticHistory
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import java.nio.file.Files

class ProviderP2pStructuredHistoryTest {

    @Test
    fun `metadata acquisition stages survive export with their source and duration`() {
        val directory = Files.createTempDirectory("tsuzuki-p2p-history").toFile()
        try {
            StructuredDiagnosticHistory(directory, persistenceEnabled = { true }).use { history ->
                val recorder = HistoryRecorder { event ->
                    history.submit(requireNotNull(StructuredDiagnosticSanitizer.sanitize(event)))
                }
                ProviderP2pDiagnostics.installRecorder(recorder)
                try {
                    val operation = "reader:0123456789abcdef0123456789abcdef01234567:3"
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.METADATA_START,
                        operationId = operation,
                        codes = mapOf("source" to "MAGNET"),
                        numbers = mapOf("timeoutMs" to 30_000L),
                        flags = mapOf("hasMagnet" to true),
                    )
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.TRANSFER_PROGRESS,
                        operationId = operation,
                        numbers = mapOf(
                            "wantedBytes" to 4096L,
                            "doneBytes" to 1024L,
                            "peerCount" to 1L,
                            "progressPercent" to 25L,
                        ),
                        flags = mapOf("firstPeerObserved" to true),
                    )
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.NATIVE_ALERT_SUMMARY,
                        operationId = operation,
                        codes = mapOf("phase" to "METADATA_FAILED"),
                        numbers = mapOf("alertDhtGetPeers" to 2L),
                    )
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.METADATA_FAILED,
                        operationId = operation,
                        codes = mapOf("source" to "MAGNET", "failure" to "METADATA_UNAVAILABLE"),
                        numbers = mapOf("elapsedMs" to 30_001L),
                    )
                } finally {
                    ProviderP2pDiagnostics.installRecorder(null)
                }
                val exported = history.snapshot()
                exported.contains("\"name\":\"provider_p2p_event\"") shouldBe true
                exported.contains("METADATA_START") shouldBe true
                exported.contains("METADATA_FAILED") shouldBe true
                exported.contains("TRANSFER_PROGRESS") shouldBe true
                exported.contains("wantedBytes=4096") shouldBe true
                exported.contains("alertDhtGetPeers=2") shouldBe true
                exported.contains("source=MAGNET") shouldBe true
                exported.contains("elapsedMs=30001") shouldBe true
                exported.contains("failure=METADATA_UNAVAILABLE") shouldBe true
                exported.contains("0123456789abcdef0123456789abcdef01234567") shouldBe false
                exported.contains("reader:") shouldBe false
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unsafe free text is excluded from export and recorder errors never affect acquisition`() {
        val seen = mutableListOf<StructuredDiagnosticEvent>()
        ProviderP2pDiagnostics.installRecorder(HistoryRecorder { seen.add(it) })
        try {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.HOST_RESPONSE,
                operationId = "reader:secret",
                codes = mapOf(
                    "status" to "FAILED",
                    "unsafe" to "https://example.org/?token=secret",
                ),
                exceptionClass = "RuntimeException: token=secret",
            )
            seen.size shouldBe 1
            val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(seen.single()))
            val text = sanitized.attributes.toString()
            text.contains("status=FAILED") shouldBe true
            text.contains("secret") shouldBe false
            text.contains("example.org") shouldBe false

            ProviderP2pDiagnostics.installRecorder(HistoryRecorder { error("disk unavailable") })
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.SESSION_START,
                operationId = "reader:secret",
            )
        } finally {
            ProviderP2pDiagnostics.installRecorder(null)
        }
    }

    private class HistoryRecorder(
        private val sink: (StructuredDiagnosticEvent) -> Unit,
    ) : StructuredDiagnosticRecorder {
        override val sessionId = "11111111-1111-1111-1111-111111111111"

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null

        override fun mihonMangaReference(mihonMangaId: Long): String? = null

        override fun record(event: StructuredDiagnosticEvent) = sink(event)
    }
}

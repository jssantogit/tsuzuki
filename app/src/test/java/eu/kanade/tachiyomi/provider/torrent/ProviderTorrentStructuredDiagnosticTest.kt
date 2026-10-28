package eu.kanade.tachiyomi.provider.torrent

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.diagnostics.StructuredDiagnosticHistory
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest
import java.nio.file.Files

class ProviderTorrentStructuredDiagnosticTest {

    private val providerId = ProviderId("app.tsuzuki.nyaa")

    @Test
    fun `torrent summaries survive structured history snapshot without sensitive payloads`() = runTest {
        val recorder = RecordingDiagnosticRecorder()
        val candidate = TorrentCandidate(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            torrentUrl = "https://nyaa.si/download/1234567.torrent",
            displayName = "Secret Example Manga Chapter 12",
            files = listOf(
                TorrentCandidateFile(
                    index = 0,
                    path = "pack/Secret Example Manga 12.cbz",
                    sizeBytes = 12L,
                ),
            ),
        )
        val gateway = ProviderTorrentMetadataSearchGateway(
            delegate = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(listOf(candidate), nextCursor = null))
            },
            registry = registry(),
            inspector = ProviderTorrentMetadataInspector { _, _ ->
                error("pre-hydrated candidates must not be inspected")
            },
            diagnosticRecorder = recorder,
        )

        gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(
                titles = listOf("Secret Example Manga"),
                chapterNumber = "12",
            ),
        )

        recorder.events.map { it.name }.shouldContainExactlyInAnyOrder(
            DiagnosticEventName.TORRENT_METADATA_SUMMARY,
            DiagnosticEventName.TORRENT_MATCH_SUMMARY,
            DiagnosticEventName.TORRENT_RELEASE_EVIDENCE_SUMMARY,
        )

        val directory = Files.createTempDirectory("tsuzuki-torrent-diagnostics").toFile()
        val exported = try {
            StructuredDiagnosticHistory(
                directory = directory,
                persistenceEnabled = { true },
            ).use { history ->
                recorder.events.forEach { event ->
                    history.submit(requireNotNull(StructuredDiagnosticSanitizer.sanitize(event)))
                }
                history.snapshot()
            }
        } finally {
            directory.deleteRecursively()
        }

        // The canonical title-aware matcher also drives this export-durable exact summary.
        exported.contains("\"torrent_exact_match_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_identity_match_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"name\":\"torrent_release_evidence_summary\"") shouldBe true
        exported.contains("\"torrent_release_exact_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_single_readable_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_release_exact_single_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_release_exact_readable_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_release_exact_multi_readable_count\":{\"type\":\"number\",\"value\":0}") shouldBe
            true
        exported.contains("\"torrent_file_token_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_file_token_single_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_file_title_token_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("\"torrent_file_title_token_single_count\":{\"type\":\"number\",\"value\":1}") shouldBe true
        exported.contains("Secret Example Manga") shouldBe false
        exported.contains("Secret Example Manga 12.cbz") shouldBe false
        exported.contains("0123456789abcdef0123456789abcdef01234567") shouldBe false
        exported.contains("nyaa.si/download") shouldBe false
    }

    @Test
    fun `unmatched torrent reasons are classified and survive diagnostic sanitization`() = runTest {
        val cases = listOf(
            Triple(emptyList<TorrentCandidate>(), null, "NO_CANDIDATES"),
            Triple(
                listOf(unmatchedCandidate(listOf(TorrentCandidateFile(0, "images/page.jpg")))),
                null,
                "NO_READABLE_ARCHIVES",
            ),
            Triple(
                listOf(unmatchedCandidate(listOf(TorrentCandidateFile(0, "pack/unrelated.cbz")))),
                null,
                "CHAPTER_IDENTITY_NOT_PROVEN",
            ),
            Triple(
                listOf(unmatchedCandidate(listOf(TorrentCandidateFile(0, "pack/Chapter 12.cbz")))),
                2,
                "VOLUME_NOT_PROVEN",
            ),
            Triple(
                listOf(
                    unmatchedCandidate(
                        listOf(
                            TorrentCandidateFile(0, "pack/Chapter 12.cbz"),
                            TorrentCandidateFile(1, "alternate/Chapter 12.zip"),
                        ),
                    ),
                ),
                null,
                "AMBIGUOUS_ARCHIVES",
            ),
        )

        for ((candidates, volume, reason) in cases) {
            val recorder = RecordingDiagnosticRecorder()
            val gateway = ProviderTorrentMetadataSearchGateway(
                delegate = TorrentSearchGateway { _, _ ->
                    ProviderCallResult.Success(ProviderPage(candidates, nextCursor = null))
                },
                registry = registry(),
                inspector = ProviderTorrentMetadataInspector { _, _ ->
                    error("pre-hydrated fixture must not be inspected")
                },
                diagnosticRecorder = recorder,
            )

            gateway.search(
                providerId = providerId,
                request = TorrentSearchRequest(
                    titles = listOf("Secret Example Manga"),
                    chapterNumber = "12",
                    volume = volume,
                ),
            )

            val summary = recorder.events.single { it.name == DiagnosticEventName.TORRENT_MATCH_SUMMARY }
            val safe = requireNotNull(StructuredDiagnosticSanitizer.sanitize(summary))
            safe.attributes.entries
                .singleOrNull { it.key.name == "TORRENT_NO_MATCH_REASON" }
                ?.value
                ?.toString()
                ?.contains(reason) shouldBe true
            safe.attributes.values.any { it.toString().contains("Secret Example Manga") } shouldBe false
        }
    }

    @Test
    fun `Host hydration and match summaries retain one sanitized correlation operation`() = runTest {
        val recorder = RecordingDiagnosticRecorder()
        val gateway = ProviderTorrentMetadataSearchGateway(
            delegate = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(emptyList(), nextCursor = null))
            },
            registry = registry(),
            inspector = ProviderTorrentMetadataInspector { _, _ ->
                error("empty search must not inspect metadata")
            },
            diagnosticRecorder = recorder,
        )

        gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(
                titles = listOf("Secret Example Manga"),
                chapterNumber = "12",
            ),
        )

        val summaries = recorder.events.filter {
            it.name in setOf(
                DiagnosticEventName.TORRENT_METADATA_SUMMARY,
                DiagnosticEventName.TORRENT_MATCH_SUMMARY,
                DiagnosticEventName.TORRENT_RELEASE_EVIDENCE_SUMMARY,
            )
        }
        summaries.size shouldBe 3
        val ids = summaries.map { requireNotNull(it.operationId) }
        ids.distinct().size shouldBe 1
        ids.singleOrNull() shouldBe null
        ids.first().matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        ) shouldBe true
        summaries.map { requireNotNull(StructuredDiagnosticSanitizer.sanitize(it)).operationId }
            .distinct() shouldBe listOf(ids.first())
    }

    private fun unmatchedCandidate(files: List<TorrentCandidateFile>) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = null,
        displayName = "Secret Example Manga release",
        files = files,
    )

    private fun registry() = DefaultProviderRegistry(
        registrations = {
            listOf(
                ProviderRegistration(
                    descriptor = ProviderDescriptor(
                        id = providerId,
                        name = "Nyaa",
                        version = ProviderVersion("test", 1),
                        origin = ProviderOrigin.Repository("test"),
                        runtime = ProviderRuntimeKind.SCRIPT,
                        capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                        permissions = ProviderPermissionSet(
                            network = ProviderNetworkPermission(
                                origins = setOf("https://nyaa.si"),
                                localNetwork = false,
                            ),
                        ),
                        settings = emptyList(),
                        contentLanguages = emptySet(),
                    ),
                    lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                ),
            )
        },
    )

    private class RecordingDiagnosticRecorder : StructuredDiagnosticRecorder {
        override val sessionId = "11111111-1111-1111-1111-111111111111"
        val events = mutableListOf<StructuredDiagnosticEvent>()

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null

        override fun mihonMangaReference(mihonMangaId: Long): String? = null

        override fun record(event: StructuredDiagnosticEvent) {
            events += event
        }
    }
}

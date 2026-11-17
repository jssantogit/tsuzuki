package eu.kanade.tachiyomi.provider.reading

import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderLookupDecision
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.provider.reading.CollectProviderReadingEvidence
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingEvidenceCollection

/**
 * LIVE OPT-IN: executes actual CollectProviderReadingEvidence through the real
 * RefreshChapterEvidence and ReconcileChapterEvidence, persisting to the same
 * SQLDelight schema/repositories used by Tsuzuki (disposable SQLite/JDBC).
 *
 * Provenance inputs: only independently attested production Android catalog
 * and signed MangaAll real HTTP envelopes. No authored candidates/chapters.
 *
 * The SQLite canonical chapter query is NOT a rendered UI/Reader pixel test.
 */
internal object MangaAllRealSqliteReplay {
    internal data class Report(
        val caseId: String,
        val catalog: String,
        val stage: String,
        val sourceGaps: Int = 0,
        val upstreamErrors: Int = 0,
        val collectorCalled: Boolean = false,
        val collectorComplete: Boolean = false,
        val snapshotCount: Int = 0,
        val provisionalEvidenceCount: Int = 0,
        val partialEvidenceCount: Int = 0,
        val sqliteBindingRows: Int = 0,
        val sqliteEvidenceRows: Int = 0,
        val sqliteMappedRows: Int = 0,
        val sqliteChapterRows: Int = 0,
        val uiQueryChapterRows: Int = 0,
        val refreshOutcome: String = "NOT_EXECUTED_HOST",
        val lookupDecisions: String = "NONE",
    ) {
        fun toClosedLine(): String =
            "mode=CAPTURED_REAL_PROVIDER_REPLAY|catalogEvidence=DIRECT_LIVE_ANDROID_CATALOG_ONLY|" +
                "case=$caseId|catalog=$catalog|stage=$stage|replayGaps=$sourceGaps|" +
                "upstreamErrors=$upstreamErrors|collectorCalled=$collectorCalled|" +
                "complete=$collectorComplete|snapshots=$snapshotCount|" +
                "provisional=$provisionalEvidenceCount|partial=$partialEvidenceCount|" +
                "sqliteBindings=$sqliteBindingRows|sqliteEvidence=$sqliteEvidenceRows|" +
                "sqliteMapped=$sqliteMappedRows|sqliteChapters=$sqliteChapterRows|" +
                "uiRepositoryChapters=$uiQueryChapterRows|refresh=$refreshOutcome|" +
                "lookupDecisions=$lookupDecisions|uiPixels=NOT_EXECUTED_HOST|" +
                "reader=NOT_EXECUTED_HOST"
    }

    private class ClosedDiagnostics : StructuredDiagnosticRecorder {
        override val sessionId = "00000000-0000-0000-0000-000000000000"
        val events = mutableListOf<StructuredDiagnosticEvent>()

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null
        override fun mihonMangaReference(mihonMangaId: Long): String? = null
        override fun record(event: StructuredDiagnosticEvent) {
            events += event
        }

        fun decisionSummary(): String {
            val decisions = events.mapNotNull { event ->
                if (event.name != DiagnosticEventName.PROVIDER_READING_EVIDENCE) {
                    return@mapNotNull null
                }
                val code = (event.attributes["lookup_decision"] as? DiagnosticAttributeValue.Code)?.value
                code as? DiagnosticProviderLookupDecision
            }
            return MangaAllCanonicalCapturedReplay.summarizeDecisions(decisions)
        }

        fun refreshCode(): String =
            events.lastOrNull { it.name == DiagnosticEventName.CHAPTER_REFRESH_COMPLETED }
                ?.outcome?.name ?: "NOT_RECORDED"
    }

    internal suspend fun run(
        sourceCaptureText: String,
        catalogCaptureText: String,
        signedPackageBytes: ByteArray,
    ): List<Report> {
        val sources = MangaAllRealSourceReplay.validate(sourceCaptureText)
        val catalogs = MangaAllLiveCatalogAttestations.parse(catalogCaptureText)
        val envelope = Json.parseToJsonElement(sourceCaptureText).jsonObject
        val prepared = MangaAllRealSourceReplay.prepare(
            signedPackageBytes = signedPackageBytes,
            expectedSha256 = envelope.getValue("packageSha256").jsonPrimitive.content,
            expectedVersionCode = envelope.getValue("providerVersionCode").jsonPrimitive.content.toLong(),
        )
        require(sources.map { it.id }.distinct().size == sources.size)
        require(catalogs.map { it.id }.distinct().size == catalogs.size)
        val bySourceId = sources.associateBy { it.id }
        val joined = catalogs.map { catalog ->
            val initial = Report(catalog.id, catalog.outcome.name, "CATALOG_NOT_RESOLVED")
            if (catalog.outcome != MangaAllCatalogIdentityStatus.RESOLVED) return@map initial
            val metadata = catalog.canonicalMetadata ?: error("Resolved live identity lacks verified metadata")
            val owner = requireNotNull(catalog.identity)
            val source = bySourceId[catalog.id]
                ?: return@map initial.copy(stage = "SOURCE_CAPTURE_MISSING")
            if (source.replayCompleteness != "LOOKUP_CHAPTERS_ONLY") {
                return@map initial.copy(stage = "SOURCE_CAPTURE_INCOMPLETE")
            }
            val tape = MangaAllCapturedRequestTape(source.records)
            MangaAllDisposableSqlite.open().use { sqlite ->
                val canonicalId = "mangaall-ci-sqlite-" + catalog.id
                val canonical = sqlite.titleRepository.getOrCreateByExternalIdentity(
                    CanonicalTitle(
                        id = canonicalId,
                        displayTitle = owner.title,
                        identityState = CanonicalIdentityState.RESOLVED,
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                    ExternalIdentity(
                        canonicalTitleId = canonicalId,
                        provider = owner.provider,
                        externalId = owner.providerId,
                        verified = true,
                        createdAt = 1L,
                    ),
                )
                check(canonical.id == canonicalId) { "Disposable DB reused a catalog identity" }
                sqlite.metadataRepository.upsertIfNewer(
                    CanonicalMetadataSnapshot(
                        canonicalTitleId = canonicalId,
                        configurationFingerprint = "independent-live-catalog-android",
                        metadata = metadata,
                        refreshedAt = 1L,
                    ),
                )
                require(sqlite.metadataRepository.get(canonicalId)?.metadata?.externalIds == metadata.externalIds) {
                    "Independent catalog identity was not persisted in SQLDelight"
                }

                val diagnostics = ClosedDiagnostics()
                val realCollector = CollectProviderReadingEvidence(
                    canonicalTitleRepository = sqlite.titleRepository,
                    providerRegistry = prepared.registry,
                    gateway = prepared.gateway(tape),
                    bindingRepository = sqlite.bindingRepository,
                    evidenceAdapter = ProviderChapterEvidenceAdapter(),
                    clock = { 1L },
                    diagnostics = diagnostics,
                    canonicalMetadataSnapshotRepository = sqlite.metadataRepository,
                )
                // Only a transparent test observation proxy. Refresh invokes
                // the REAL collector once; no Provider results are fabricated.
                val observedCollector = mockk<CollectProviderReadingEvidence>()
                var collected: ProviderReadingEvidenceCollection? = null
                var calls = 0
                coEvery { observedCollector.execute(canonicalId) } coAnswers {
                    check(calls++ == 0) { "Live Host collector must execute exactly once per work" }
                    realCollector.execute(canonicalId).also { collected = it }
                }
                coEvery { observedCollector.commitProvisionalCursors(any()) } coAnswers {
                    realCollector.commitProvisionalCursors(firstArg())
                }
                val registry = mockk<IntegrationRegistry>(relaxed = true)
                coEvery { registry.awaitReady() } returns Unit
                // No fake editorial chapter integration in the LIVE matrix.
                every { registry.chapterEvidenceProviders() } returns emptyList()
                val refresh = RefreshChapterEvidence(
                    registry = registry,
                    reconcileChapterEvidence = ReconcileChapterEvidence(
                        parser = ParseCanonicalChapterLabel(),
                        canonicalChapterRepository = sqlite.chapterRepository,
                        evidenceRepository = sqlite.evidenceRepository,
                    ),
                    providerReadingEvidence = observedCollector,
                    structuredDiagnostics = diagnostics,
                )
                val completed = refresh.execute(canonicalId, forceRefresh = true)
                val persistedChapters = sqlite.chapterRepository.getByCanonicalTitleId(canonicalId)
                val uiRepositoryChapters = sqlite.chapterRepository.getChaptersByCanonicalTitleId(canonicalId)
                check(persistedChapters.map { it.id }.toSet() == uiRepositoryChapters.map { it.id }.toSet()) {
                    "UI-facing chapter repository disagrees with canonical SQLite rows"
                }
                val persistedEvidence = sqlite.evidenceRepository.getByCanonicalTitleId(canonicalId)
                val mapped = persistedEvidence.count { it.mappedCanonicalChapterId != null }
                val snapshot = collected
                val stage = when {
                    completed.isFailure -> "SQLDELIGHT_REFRESH_FAILED"
                    tape.replayGapCount > 0 -> "REPLAY_GAP"
                    tape.upstreamErrorCount > 0 -> "INCONCLUSIVE_UPSTREAM"
                    snapshot == null -> "COLLECTOR_NOT_CALLED"
                    !snapshot.complete -> "SQLDELIGHT_PARTIAL_ONLY"
                    else -> "SQLDELIGHT_REFRESH_RECONCILED"
                }
                initial.copy(
                    stage = stage,
                    sourceGaps = tape.replayGapCount,
                    upstreamErrors = tape.upstreamErrorCount,
                    collectorCalled = calls == 1,
                    collectorComplete = snapshot?.complete == true,
                    snapshotCount = snapshot?.snapshots?.size ?: 0,
                    provisionalEvidenceCount = snapshot?.evidence?.size ?: 0,
                    partialEvidenceCount = snapshot?.partialEvidence?.size ?: 0,
                    sqliteBindingRows = sqlite.bindingRepository.getByTitle(canonicalId).size,
                    sqliteEvidenceRows = persistedEvidence.size,
                    sqliteMappedRows = mapped,
                    sqliteChapterRows = persistedChapters.size,
                    uiQueryChapterRows = uiRepositoryChapters.size,
                    refreshOutcome = diagnostics.refreshCode(),
                    lookupDecisions = diagnostics.decisionSummary(),
                )
            }
        }
        return joined + sources.filter { source ->
            catalogs.none { it.id == source.id }
        }.map { source ->
            Report(source.id, "CATALOG_CAPTURE_MISSING", "CATALOG_CAPTURE_MISSING")
        }
    }
}

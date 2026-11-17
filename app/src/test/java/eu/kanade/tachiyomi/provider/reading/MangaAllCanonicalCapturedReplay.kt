package eu.kanade.tachiyomi.provider.reading

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderLookupDecision
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshotRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.CollectProviderReadingEvidence
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBindingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

/**
 * Stage 1 of actual canonical Host execution using ONLY signed real-source
 * envelopes and independently live-verified catalog identities.
 *
 * Executes the production CollectProviderReadingEvidence decision algorithm.
 * It DOES NOT claim a chapter snapshot was written to SQLite, reconciled or
 * rendered in the Reader. Uncaptured Host calls are always REPLAY_GAP.
 */
internal object MangaAllCanonicalCapturedReplay {
    internal data class Report(
        val caseId: String,
        val catalog: String,
        val stage: String,
        val replayGaps: Int = 0,
        val upstreamErrors: Int = 0,
        val bindingCount: Int = 0,
        val snapshotCount: Int = 0,
        val provisionalObservations: Int = 0,
        val partialObservations: Int = 0,
        val collectorComplete: Boolean = false,
        val lookupDecisions: String = "NONE",
    ) {
        fun toClosedLine(): String =
            "mode=CAPTURED_REAL_PROVIDER_REPLAY|catalogEvidence=DIRECT_LIVE_ANDROID_CATALOG_ONLY|" +
                "case=$caseId|catalog=$catalog|collector=$stage|gaps=$replayGaps|" +
                "sourceErrors=$upstreamErrors|bindings=$bindingCount|snapshots=$snapshotCount|" +
                "provisional=$provisionalObservations|partial=$partialObservations|" +
                "complete=$collectorComplete|lookupDecisions=$lookupDecisions|" +
                "sqlite=NOT_EXECUTED_HOST|reader=NOT_EXECUTED_HOST"
    }

    /** Closed, count-only reasons; no candidate titles, links or private source IDs. */
    internal fun summarizeDecisions(decisions: List<DiagnosticProviderLookupDecision>): String =
        decisions.groupingBy { it }.eachCount().entries
            .sortedBy { it.key.name }
            .joinToString(",") { (decision, count) -> "${decision.name}:$count" }
            .ifEmpty { "NONE" }

    private class CollectorDiagnostics : StructuredDiagnosticRecorder {
        override val sessionId: String = "00000000-0000-0000-0000-000000000000"
        val decisions = mutableListOf<DiagnosticProviderLookupDecision>()

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null
        override fun mihonMangaReference(mihonMangaId: Long): String? = null

        override fun record(event: StructuredDiagnosticEvent) {
            if (event.name != DiagnosticEventName.PROVIDER_READING_EVIDENCE ||
                event.stage != DiagnosticStage.LOOKUP
            ) {
                return
            }
            val value = (event.attributes["lookup_decision"] as? DiagnosticAttributeValue.Code)?.value
            (value as? DiagnosticProviderLookupDecision)?.let(decisions::add)
        }
    }

    /**
     * Source work IDs are authenticated by the validated real Provider batch.
     * A missing catalog attestation can never turn that work into a false pass.
     */
    internal fun missingCatalogReports(sourceIds: List<String>, catalogIds: Set<String>): List<Report> =
        sourceIds.filterNot(catalogIds::contains).map { id ->
            Report(caseId = id, catalog = "CATALOG_CAPTURE_MISSING", stage = "CATALOG_CAPTURE_MISSING")
        }

    internal suspend fun run(
        sourceCaptureText: String,
        catalogCaptureText: String,
        signedPackageBytes: ByteArray,
    ): List<Report> {
        val sourceCases = MangaAllRealSourceReplay.validate(sourceCaptureText)
        val catalogCases = MangaAllLiveCatalogAttestations.parse(catalogCaptureText)
        val batch = Json.parseToJsonElement(sourceCaptureText).jsonObject
        val expectedSha = batch.getValue("packageSha256").jsonPrimitive.content
        val expectedCode = batch.getValue("providerVersionCode").jsonPrimitive.content.toLong()
        val packageSource = MangaAllRealSourceReplay.prepare(signedPackageBytes, expectedSha, expectedCode)
        require(sourceCases.map { it.id }.distinct().size == sourceCases.size)
        val byId = sourceCases.associateBy { it.id }
        require(catalogCases.map { it.id }.distinct().size == catalogCases.size)
        val resolvedCases = catalogCases.map { catalog ->
            val outcome = catalog.outcome
            val base = Report(catalog.id, catalog = outcome.name, stage = "CATALOG_NOT_RESOLVED")
            val proven = catalog.canonicalMetadata
            if (outcome != MangaAllCatalogIdentityStatus.RESOLVED || proven == null) {
                return@map base
            }
            val capture = byId[catalog.id] ?: return@map base.copy(stage = "SOURCE_CAPTURE_MISSING")
            if (capture.replayCompleteness != "LOOKUP_CHAPTERS_ONLY") {
                return@map base.copy(stage = "SOURCE_CAPTURE_INCOMPLETE")
            }

            val tape = MangaAllCapturedRequestTape(capture.records)
            val canonicalTitleId = "mangaall-ci-local-" + catalog.id
            val identity = requireNotNull(catalog.identity)
            val canonical = CanonicalTitle(
                id = canonicalTitleId,
                displayTitle = identity.title,
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val titleRepository = mockk<CanonicalTitleRepository>()
            coEvery { titleRepository.getById(canonicalTitleId) } returns canonical
            val snapshots = mockk<CanonicalMetadataSnapshotRepository>()
            coEvery { snapshots.get(canonicalTitleId) } returns CanonicalMetadataSnapshot(
                canonicalTitleId = canonicalTitleId,
                configurationFingerprint = "independent-live-catalog-captured",
                metadata = proven,
                refreshedAt = 1L,
            )
            val diagnostics = CollectorDiagnostics()
            val collector = CollectProviderReadingEvidence(
                canonicalTitleRepository = titleRepository,
                providerRegistry = packageSource.registry,
                gateway = packageSource.gateway(tape),
                bindingRepository = IsolatedBindingStore(),
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                clock = { 1L },
                diagnostics = diagnostics,
                canonicalMetadataSnapshotRepository = snapshots,
            )
            val collected = collector.execute(canonicalTitleId)
            val stage = when {
                tape.replayGapCount > 0 -> "REPLAY_GAP"
                tape.upstreamErrorCount > 0 -> "INCONCLUSIVE_UPSTREAM"
                !collected.complete -> "COLLECT_PARTIAL"
                else -> "COLLECT_EXECUTED_NOT_RECONCILED"
            }
            base.copy(
                stage = stage,
                replayGaps = tape.replayGapCount,
                upstreamErrors = tape.upstreamErrorCount,
                bindingCount = collected.bindingCount,
                snapshotCount = collected.snapshots.size,
                provisionalObservations = collected.evidence.size,
                partialObservations = collected.partialEvidence.size,
                collectorComplete = collected.complete,
                lookupDecisions = summarizeDecisions(diagnostics.decisions),
            )
        }
        return resolvedCases + missingCatalogReports(
            sourceIds = sourceCases.map { it.id },
            catalogIds = catalogCases.mapTo(mutableSetOf()) { it.id },
        )
    }

    /** Test-only in-memory bindings, never the user's installed library/edition. */
    private class IsolatedBindingStore : ProviderReadingBindingRepository {
        private val values = mutableMapOf<Triple<String, ProviderId, String?>, ProviderReadingBinding>()

        private fun key(binding: ProviderReadingBinding) =
            Triple(binding.canonicalTitleId, binding.ref.providerId, binding.ref.facetId)

        override suspend fun get(
            canonicalTitleId: String,
            providerId: ProviderId,
            facetId: String?,
        ): ProviderReadingBinding? = values[Triple(canonicalTitleId, providerId, facetId)]

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ProviderReadingBinding) {
            values[key(binding)] = binding
        }

        override suspend fun insertIfAbsent(binding: ProviderReadingBinding): Boolean {
            val key = key(binding)
            if (key in values) return false
            values[key] = binding
            return true
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            val existing = values.values.firstOrNull { it.id == bindingId } ?: return
            values[key(existing)] = existing.copy(
                availability = ProviderBindingAvailability.UNAVAILABLE,
                updatedAt = updatedAt,
            )
        }
    }
}

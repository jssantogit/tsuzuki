package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderLookupDecision

/**
 * Contract-only SHA keys, no forged manga candidates, catalog credits or chapters.
 * A missing real-data shard is never allowed to disappear from the Host report.
 */
class MangaAllCanonicalCapturedReplayCoverageTest {
    @Test
    fun `source works missing independent catalog responses remain explicit inconclusive rows`() {
        val missing = MangaAllCanonicalCapturedReplay.missingCatalogReports(
            sourceIds = listOf("user-first", "user-second", "user-third"),
            catalogIds = setOf("user-second"),
        )
        missing.map { it.caseId } shouldContainExactly listOf("user-first", "user-third")
        missing.map { it.stage } shouldContainExactly listOf("CATALOG_CAPTURE_MISSING", "CATALOG_CAPTURE_MISSING")
        missing.all { !it.collectorComplete && it.provisionalObservations == 0 } shouldBe true
    }

    @Test
    fun `ambiguous edition outcomes are preserved as closed diagnostic codes`() {
        val decisions = MangaAllCanonicalCapturedReplay.summarizeDecisions(
            listOf(
                DiagnosticProviderLookupDecision.MULTIPLE_PRIMARY_IDENTITIES,
                DiagnosticProviderLookupDecision.AMBIGUOUS_EDITION,
                DiagnosticProviderLookupDecision.AMBIGUOUS_EDITION,
            ),
        )
        decisions shouldBe "AMBIGUOUS_EDITION:2,MULTIPLE_PRIMARY_IDENTITIES:1"
        MangaAllCanonicalCapturedReplay.summarizeDecisions(emptyList()) shouldBe "NONE"
    }

    @Test
    fun `complete catalog coverage contributes no fabricated gap rows`() {
        MangaAllCanonicalCapturedReplay.missingCatalogReports(
            sourceIds = listOf("user-first"),
            catalogIds = setOf("user-first"),
        ) shouldBe emptyList()
    }
}

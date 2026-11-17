package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
    fun `complete catalog coverage contributes no fabricated gap rows`() {
        MangaAllCanonicalCapturedReplay.missingCatalogReports(
            sourceIds = listOf("user-first"),
            catalogIds = setOf("user-first"),
        ) shouldBe emptyList()
    }
}

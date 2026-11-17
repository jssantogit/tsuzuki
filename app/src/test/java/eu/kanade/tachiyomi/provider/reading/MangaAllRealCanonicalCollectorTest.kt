package eu.kanade.tachiyomi.provider.reading

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Opt-in LIVE replay: no source/canonical fixtures in the success path.
 *
 * Source observations come from signed MangaAll code8 through real HTTP;
 * catalog credits come independently from the production Android integration.
 * Executes the actual Domain collector, NOT the refresh/SQLite/Reader yet.
 */
class MangaAllRealCanonicalCollectorTest {
    @Test
    fun `live catalog identities drive production Host canonical evidence collector`() = runBlocking {
        val source = System.getenv("MANGAALL_REAL_ENVELOPES_FILE")
        val catalog = System.getenv("MANGAALL_REAL_CATALOG_FILE")
        val archive = System.getenv("MANGAALL_SIGNED_PACKAGE_FILE")
        val required = System.getenv("MANGAALL_CANONICAL_COLLECT_REQUIRED") == "1"
        if (required) {
            require(!source.isNullOrBlank() && !catalog.isNullOrBlank() && !archive.isNullOrBlank()) {
                "Canonical replay requires original signed source, real catalog and package artifacts"
            }
        }
        assumeTrue(
            !source.isNullOrBlank() && !catalog.isNullOrBlank() && !archive.isNullOrBlank(),
            "Only manually dispatched live matrix may drive the collector",
        )
        val reports = MangaAllCanonicalCapturedReplay.run(
            sourceCaptureText = File(requireNotNull(source)).readText(),
            catalogCaptureText = File(requireNotNull(catalog)).readText(),
            signedPackageBytes = File(requireNotNull(archive)).readBytes(),
        )
        if (reports.isEmpty()) {
            println("MANGAALL_CANONICAL_COLLECTOR|EMPTY_SHARD|no_source_or_catalog_work_invented")
        }
        reports.forEach { result ->
            // A completed collector only means observation, NEVER reconciled chapters.
            println("MANGAALL_CANONICAL_COLLECTOR|" + result.toClosedLine())
        }
    }
}

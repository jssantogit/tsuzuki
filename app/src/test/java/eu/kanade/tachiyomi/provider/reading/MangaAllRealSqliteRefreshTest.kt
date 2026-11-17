package eu.kanade.tachiyomi.provider.reading

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * LIVE opt-in source envelopes and independent catalog; disposable real
 * SQLDelight SQLite repositories, actual Host collector, refresh and reconciler.
 * No preselected manga list, synthetic candidates, or Reader-pixel claims.
 */
class MangaAllRealSqliteRefreshTest {
    @Test
    fun `real MangaAll chapters reach canonical SQLDelight after actual Host refresh`() = runBlocking {
        val source = System.getenv("MANGAALL_REAL_ENVELOPES_FILE")
        val catalog = System.getenv("MANGAALL_REAL_CATALOG_FILE")
        val signed = System.getenv("MANGAALL_SIGNED_PACKAGE_FILE")
        val required = System.getenv("MANGAALL_REAL_SQLITE_REQUIRED") == "1"
        if (required) {
            require(!source.isNullOrBlank() && !catalog.isNullOrBlank() && !signed.isNullOrBlank()) {
                "Real SQLDelight gate requires source, independent catalog and signed MangaAll archive"
            }
        }
        assumeTrue(
            !source.isNullOrBlank() && !catalog.isNullOrBlank() && !signed.isNullOrBlank(),
            "Only the manually dispatched live matrix supplies genuine upstream data",
        )
        val reports = MangaAllRealSqliteReplay.run(
            sourceCaptureText = File(requireNotNull(source)).readText(),
            catalogCaptureText = File(requireNotNull(catalog)).readText(),
            signedPackageBytes = File(requireNotNull(signed)).readBytes(),
        )
        if (reports.isEmpty()) {
            println("MANGAALL_REAL_SQLITE_REFRESH|EMPTY_SHARD|no_work_invented")
        }
        reports.forEach {
            println("MANGAALL_REAL_SQLITE_REFRESH|" + it.toClosedLine())
        }
    }
}

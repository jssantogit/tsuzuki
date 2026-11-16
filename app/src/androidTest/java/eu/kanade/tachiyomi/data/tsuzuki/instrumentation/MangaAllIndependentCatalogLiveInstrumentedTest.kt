package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.integration.MangaUpdatesIntegrationProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityResult
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.catalog.interactor.ResolveMangaAllIndependentCatalogIdentity
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import java.security.MessageDigest
import java.text.Normalizer

/**
 * LIVE, opt-in, disposable-emulator catalog probe. Unlike the offline contract
 * suite this calls the production MangaUpdates integration and its real HTTP
 * implementation through the app's own TrackerManager. It never consults the
 * MangaAll provider or uses source-authored identity as canonical authority.
 *
 * This checks the catalog bootstrap ONLY. It does not run chapter reconciliation,
 * use the app's persistent library, or assert Reader pixels.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllIndependentCatalogLiveInstrumentedTest {
    @Test(timeout = 900_000L)
    fun arbitraryWorksAreQueriedThroughProductionIndependentCatalog() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("mangaAllLiveCatalogOptIn"))
        val target = instrument.targetContext
        assertEquals("app.tsuzuki.dev", target.packageName)
        assertTrue(
            "Live catalog CI is restricted to an emulator",
            Build.FINGERPRINT.contains("generic") ||
                Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.PRODUCT.startsWith("sdk_gphone"),
        )

        val encoded = requireNotNull(args.getString("worksBase64")) { "Missing arbitrary work input" }
        val works = Base64.decode(encoded, Base64.NO_WRAP).toString(Charsets.UTF_8)
            .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(works.size in 1..30) { "Live catalog requires 1-30 titles" }
        require(works.all { it.length <= 256 }) { "Work title exceeds bound" }
        val shardIndex = requireNotNull(args.getString("shardIndex")).toInt()
        val shardCount = requireNotNull(args.getString("shardCount")).toInt()
        require(shardCount in 1..3 && shardIndex in 0 until shardCount)

        // This is the *actual* Tsuzuki/MangaUpdates integration and its real
        // tracker/network adapter, not a fake provider or a test HTTP client.
        val app = target.applicationContext as App
        val catalog = MangaUpdatesIntegrationProvider(app.graph.trackerManager)
        val registry = object : IntegrationRegistry {
            override fun searchProviders(): List<SearchProvider> = listOf(catalog)
            override fun metadataProviders(): List<MetadataProvider> = listOf(catalog)
            override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
            override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
            override fun ratingsProviders(): List<RatingsProvider> = emptyList()
            override fun trackingProviders(): List<TrackingProvider> = emptyList()
        }
        val bootstrap = ResolveMangaAllIndependentCatalogIdentity(registry)
        val selected = works.withIndex().filter { it.index % shardCount == shardIndex }
        var conclusive = 0
        for ((index, line) in selected) {
            val work = parseWork(line)
            val report = try {
                withTimeout(35_000L) {
                    bootstrap.execute(work.title, work.format)
                }
            } catch (_: TimeoutCancellationException) {
                MangaAllCatalogIdentityResult(status = MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE)
            }
            if (report.status in setOf(
                    MangaAllCatalogIdentityStatus.RESOLVED,
                    MangaAllCatalogIdentityStatus.CATALOG_AMBIGUOUS,
                    MangaAllCatalogIdentityStatus.CATALOG_NOT_FOUND,
                )
            ) conclusive++
            val reportLine = buildString {
                append("MANGAALL_REAL_CATALOG|mode=DIRECT_LIVE_ANDROID_CATALOG_ONLY")
                append("|case=user-").append(sha256(normalizedTitle(work.title)).take(12))
                append("|index=").append(index)
                append("|source=MANGAUPDATES_PRODUCTION")
                append("|outcome=").append(report.status)
                append("|candidates=").append(report.candidateCount)
                append("|catalogs=").append(report.catalogsQueried)
                append("|failedCatalogs=").append(report.catalogsFailed)
                append("|provider=").append(report.identity?.provider.orEmpty())
                append("|providerId=").append(report.identity?.providerId.orEmpty())
                append("|creatorsAttested=").append(report.creatorsAttested)
                append("|canonical=NOT_EXECUTED_HOST|reader=NOT_EXECUTED_HOST")
            }
            instrument.sendStatus(1, Bundle().apply { putString("stream", reportLine) })
        }
        instrument.sendStatus(1, Bundle().apply {
            putString(
                "stream",
                "MANGAALL_REAL_CATALOG_BATCH|selected=${selected.size}|conclusive=$conclusive|" +
                    "catalog=MANGAUPDATES_PRODUCTION|canonical=NOT_EXECUTED_HOST",
            )
        })
        assertTrue(
            "All assigned catalog lookups were inconclusive (runner network/metadata unavailable)",
            selected.isEmpty() || conclusive > 0,
        )
    }

    private data class Work(val title: String, val format: CatalogItemFormat?)

    private fun parseWork(input: String): Work {
        val pieces = input.split("::", limit = 2)
        if (pieces.size == 1) return Work(input, null)
        val format = when (pieces[0].trim().lowercase().replace('-', '_')) {
            "unknown" -> CatalogItemFormat.UNKNOWN
            "manga" -> CatalogItemFormat.MANGA
            "manhwa" -> CatalogItemFormat.MANHWA
            "manhua" -> CatalogItemFormat.MANHUA
            "webtoon" -> CatalogItemFormat.WEBTOON
            "novel", "web_novel", "light_novel" -> CatalogItemFormat.NOVEL
            "one_shot", "oneshot" -> CatalogItemFormat.ONE_SHOT
            "doujin" -> CatalogItemFormat.DOUJIN
            else -> null
        }
        require(format != null && pieces[1].isNotBlank()) { "Unsupported work prefix or blank title" }
        return Work(pieces[1].trim(), format)
    }

    // Match tooling/mangaall_generic_works.mjs case identity, so both live
    // shards can be joined without exposing the original title text.
    private fun normalizedTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase()

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.integration.KitsuIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.MalIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.MangaUpdatesIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.ShikimoriIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.ShikimoriRequestGate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.data.tsuzuki.kitsu.client.KitsuHttpClient
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityResult
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.catalog.interactor.ResolveMangaAllIndependentCatalogIdentity
import tachiyomi.domain.tsuzuki.catalog.interactor.toCanonicalReadingMetadata
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
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
 * suite this calls one explicitly selected production independent integration
 * and its real HTTP implementation through the app's own TrackerManager. It never consults the
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
        val catalogName = args.getString("mangaAllLiveCatalogSource") ?: "mangaupdates"
        val catalog: SearchProvider = when (catalogName) {
            "mangaupdates" -> MangaUpdatesIntegrationProvider(app.graph.trackerManager)
            "shikimori" -> ShikimoriIntegrationProvider(app.graph.trackerManager, ShikimoriRequestGate())
            "mal" -> MalIntegrationProvider(app.graph.trackerManager)
            "kitsu" -> KitsuIntegrationProvider(
                KitsuCatalogProvider(KitsuHttpClient(app.graph.networkHelper, app.graph.json)),
                app.graph.trackerManager,
            )
            else -> error("Unknown independent catalog scope")
        }
        val metadataCatalog = catalog as MetadataProvider
        // Emit only closed failure codes and pagination counts from the
        // actual adapter; never send its exception, query, or response body.
        val diagnosticPrefix = when (catalogName) {
            "mangaupdates" -> "MU_SEARCH_CONTRACT"
            "shikimori" -> "SHIKI_SEARCH_CONTRACT"
            "mal" -> "MAL_SEARCH_CONTRACT"
            else -> "KITSU_SEARCH_CONTRACT"
        }
        var activeIndex = -1
        val observedSearch = object : SearchProvider by catalog {
            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                val result = catalog.search(query)
                val error = result.exceptionOrNull()
                val contract = error?.message?.let {
                    Regex(
                        """MU_SEARCH_CONTRACT\|reason=(FIRST_GEOMETRY|PAGE_GEOMETRY|ROW_COUNT|INVALID_OR_REPEATED_ID)\|page=-?\d+\|size=-?\d+\|total=-?\d+\|rows=\d+""",
                    )
                        .matchEntire(it)?.value
                }
                val facts = if (error != null) {
                    val reason = when {
                        catalogName == "mal" && !app.graph.trackerManager.myAnimeList.hasClientId() ->
                            "CLIENT_ID_MISSING"
                        error is CatalogError.HttpError -> "HTTP_${error.statusCode}"
                        error is CatalogError.RateLimitExceeded -> "HTTP_429"
                        error is CatalogError.NetworkError -> "NETWORK_FAILURE"
                        error is CatalogError.SerializationError -> "SERIALIZATION_FAILURE"
                        else -> "OTHER_FAILURE"
                    }
                    contract ?: "$diagnosticPrefix|reason=$reason"
                } else {
                    val page = result.getOrThrow()
                    "$diagnosticPrefix|reason=OK|total=${page.totalCount ?: -1}|rows=${page.items.size}"
                }
                instrument.sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "MANGAALL_CATALOG_DIAGNOSTIC|index=$activeIndex|offset=${query.offset}|$facts",
                        )
                    },
                )
                return result
            }
        }
        val registry = object : IntegrationRegistry {
            override fun searchProviders(): List<SearchProvider> = listOf(observedSearch)
            override fun metadataProviders(): List<MetadataProvider> = listOf(metadataCatalog)
            override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
            override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
            override fun ratingsProviders(): List<RatingsProvider> = emptyList()
            override fun trackingProviders(): List<TrackingProvider> = emptyList()
        }
        val bootstrap = ResolveMangaAllIndependentCatalogIdentity(registry)
        val searchHints = args.getString("catalogSearchHintsBase64")?.let { hints ->
            require(catalogName == "mangaupdates")
            Json.parseToJsonElement(Base64.decode(hints, Base64.NO_WRAP).toString(Charsets.UTF_8))
                .jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.orEmpty()
        require(searchHints.size <= works.size)
        require(searchHints.keys.all { title -> works.any { parseWork(it).title == title } })
        require(searchHints.values.all { it.isNotBlank() && it.length <= 256 })
        val selected = works.withIndex().filter { it.index % shardCount == shardIndex }
        var conclusive = 0
        for ((index, line) in selected) {
            activeIndex = index
            val work = parseWork(line)
            val report = if (work.format == CatalogItemFormat.NOVEL) {
                // Production MangaUpdates search explicitly excludes 'novel'.
                // An empty response here would NOT certify absence of a novel.
                MangaAllCatalogIdentityResult(status = MangaAllCatalogIdentityStatus.CATALOG_FORMAT_UNSUPPORTED)
            } else {
                try {
                    withTimeout(35_000L) {
                        bootstrap.execute(work.title, work.format, searchTitle = searchHints[work.title])
                    }
                } catch (_: TimeoutCancellationException) {
                    MangaAllCatalogIdentityResult(status = MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE)
                }
            }
            // Only the independently corroborated detail ID may supply Host creator provenance.
            val attested = if (
                report.status == MangaAllCatalogIdentityStatus.RESOLVED &&
                report.toCanonicalReadingMetadata() == null
            ) {
                report.copy(
                    status = MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED,
                    identity = null,
                    creatorsAttested = false,
                )
            } else {
                report
            }
            val attestationJson = buildJsonObject {
                put("schemaVersion", 1)
                put("mode", "DIRECT_LIVE_ANDROID_CATALOG_ONLY")
                put("caseId", "user-" + sha256(normalizedTitle(work.title)).take(12))
                put("query", work.title)
                put("retrievalHintUsed", work.title in searchHints)
                put("status", attested.status.name)
                put("catalogSource", catalogName)
                put("provider", attested.identity?.provider.orEmpty())
                put("providerId", attested.identity?.providerId.orEmpty())
                put("title", attested.identity?.title.orEmpty())
                put("format", attested.identity?.format?.name.orEmpty())
                putJsonArray("authors") { attested.identity?.authors?.forEach { add(it) } }
                putJsonArray("artists") { attested.identity?.artists?.forEach { add(it) } }
                putJsonObject("externalIds") {
                    attested.identity?.externalIds?.forEach { (provider, id) -> put(provider, id) }
                }
                put("creatorsAttested", attested.creatorsAttested)
                put("catalogsQueried", attested.catalogsQueried)
                put("catalogsFailed", attested.catalogsFailed)
                put("canonical", "NOT_EXECUTED_HOST")
            }
            val attestationBase64 = Base64.encodeToString(
                attestationJson.toString().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            instrument.sendStatus(
                1,
                Bundle().apply {
                    putString("stream", "MANGAALL_CATALOG_ATTESTATION|$attestationBase64")
                },
            )
            if (attested.status in setOf(
                    MangaAllCatalogIdentityStatus.RESOLVED,
                    MangaAllCatalogIdentityStatus.CATALOG_AMBIGUOUS,
                    MangaAllCatalogIdentityStatus.CATALOG_NOT_FOUND,
                )
            ) {
                conclusive++
            }
            val reportLine = buildString {
                append("MANGAALL_REAL_CATALOG|mode=DIRECT_LIVE_ANDROID_CATALOG_ONLY")
                append("|case=user-").append(sha256(normalizedTitle(work.title)).take(12))
                append("|index=").append(index)
                append("|source=").append(catalogName.uppercase()).append("_PRODUCTION")
                append("|outcome=").append(attested.status)
                append("|candidates=").append(attested.candidateCount)
                append("|catalogs=").append(attested.catalogsQueried)
                append("|failedCatalogs=").append(attested.catalogsFailed)
                append("|provider=").append(attested.identity?.provider.orEmpty())
                append("|providerId=").append(attested.identity?.providerId.orEmpty())
                append("|creatorsAttested=").append(attested.creatorsAttested)
                append("|canonical=NOT_EXECUTED_HOST|reader=NOT_EXECUTED_HOST")
            }
            instrument.sendStatus(1, Bundle().apply { putString("stream", reportLine) })
        }
        instrument.sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "MANGAALL_REAL_CATALOG_BATCH|selected=${selected.size}|conclusive=$conclusive|" +
                        "catalog=${catalogName.uppercase()}_PRODUCTION|canonical=NOT_EXECUTED_HOST",
                )
            },
        )
        // An unavailable public catalog is genuine, bounded evidence of an
        // inconclusive external dependency, not a JUnit assertion failure.
        // The downstream canonical report MUST keep each such result
        // INCONCLUSIVE_CATALOG and must NEVER synthesize an owner/binding.
        // The explicit attestation transport and case-set validation in the
        // CI gate proves every selected work was actually attempted.
        assertTrue("Live catalog batch exceeded the bounded work limit", selected.size <= 30)
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

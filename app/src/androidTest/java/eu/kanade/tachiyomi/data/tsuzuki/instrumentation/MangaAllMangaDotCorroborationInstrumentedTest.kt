package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.integration.MangaUpdatesIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.ShikimoriIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.ShikimoriRequestGate
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackage
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.catalog.interactor.ResolveMangaAllIndependentCatalogIdentity
import tachiyomi.domain.tsuzuki.catalog.interactor.toCanonicalReadingMetadata
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderFacetRef
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllMangaDotNativeCoverage
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkSourcePortfolio
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkVerification
import java.io.File
import java.security.MessageDigest

/**
 * Source creator + independent catalog + exact native first page control.
 * No SQLite, no automatic edition selection, no chapter reconciliation.
 * Never run against a signed package or replace the frozen cohort.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllMangaDotCorroborationInstrumentedTest {
    @Test(timeout = 360_000L)
    fun corroborateFrozenControls(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("I_ACCEPT_MANGADOT_CORROBORATION_RESEARCH", args.getString("mangaDotCorroborationOptIn"))
        val catalogName = requireNotNull(args.getString("mangaAllLiveCatalogSource"))
        require(catalogName in setOf("mangaupdates", "shikimori"))
        val context = instrument.targetContext
        assertEquals("app.tsuzuki.dev", context.packageName)
        assertTrue(
            Build.FINGERPRINT.contains("generic") ||
                Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.PRODUCT.startsWith("sdk_gphone"),
        )
        val lock = instrument.context.assets.open("mangaall-dual-source-30-cohort-lock.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val works = lock.getValue("works").jsonArray
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(works.toString().encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(30, works.size)
        assertEquals("19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1", digest)
        val bytes = instrument.context.assets.open("mangaall-dual-source-code11-unsigned-research.zip")
            .use { it.readBytes() }
        val parsed = ProviderPackageParser().parse(bytes)
        val manifest = parsed.manifest
        assertEquals("app.tsuzuki.mangaall", manifest.id)
        assertEquals(11L, manifest.version.code)
        assertTrue("modules/sources/mangadot/modules/creator-evidence.js" in parsed.entries)
        val origins = requireNotNull(manifest.permissions.network).origins
        assertTrue(origins.none { it.contains("mangafire", true) || it.contains("mfcdn", true) })
        val id = ProviderId(manifest.id)
        val repositoryId = "mangaall-dot-creator-unsigned-research"
        val registration = ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = id,
                name = manifest.name,
                version = ProviderVersion(manifest.version.name, manifest.version.code),
                origin = ProviderOrigin.Repository(repositoryId),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = setOf(
                    ProviderCapabilities.ReadingLookupV1,
                    ProviderCapabilities.ReadingChaptersV1,
                    ProviderCapabilities.ReadingPagesV1,
                ),
                permissions = ProviderPermissionSet(
                    network = ProviderNetworkPermission(origins, localNetwork = false),
                ),
                settings = emptyList(),
                contentLanguages = setOf("en"),
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
            facets = listOf(ProviderFacetRef(id, "en")),
            configurationFingerprint = "dot-creator-corroboration-research",
        )
        val registry = DefaultProviderRegistry(registrations = { listOf(registration) })
        val runtimeRoot = File(context.cacheDir, "mangaall-dot-corroboration-runtime")
        val gateway = ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource { requested ->
                if (requested != id) null else ActiveProviderScriptPackage(
                    repositoryId,
                    manifest.version.code,
                    bytes,
                    parsed,
                )
            },
            runtimeClient = ProviderRuntimeClient(
                context = context,
                hostInvocationFactory = ProviderHostInvocationFactory(context = context, storageRoot = runtimeRoot),
            ),
            limits = ProviderRuntimeLimitsDto(wallClockTimeoutMs = 30_000L, jsExecutionTimeoutMs = 15_000L),
        )
        val app = context.applicationContext as App
        val catalog: SearchProvider = when (catalogName) {
            "mangaupdates" -> MangaUpdatesIntegrationProvider(app.graph.trackerManager)
            "shikimori" -> ShikimoriIntegrationProvider(app.graph.trackerManager, ShikimoriRequestGate())
            else -> error("Unknown catalog scope")
        }
        val metadataCatalog = catalog as MetadataProvider
        val catalogRegistry = object : IntegrationRegistry {
            override fun searchProviders(): List<SearchProvider> = listOf(catalog)
            override fun metadataProviders(): List<MetadataProvider> = listOf(metadataCatalog)
            override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
            override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
            override fun ratingsProviders(): List<RatingsProvider> = emptyList()
            override fun trackingProviders(): List<TrackingProvider> = emptyList()
        }
        val bootstrap = ResolveMangaAllIndependentCatalogIdentity(catalogRegistry)

        suspend fun check(title: String): String {
            val identity = bootstrap.execute(title, CatalogItemFormat.MANHWA)
            if (identity.status != MangaAllCatalogIdentityStatus.RESOLVED) return identity.status.name
            val metadata = identity.toCanonicalReadingMetadata() ?: return "CATALOG_PROVENANCE_MISSING"
            val creators = metadata.authors?.value.orEmpty() + metadata.artists?.value.orEmpty()
            if (!identity.creatorsAttested || creators.isEmpty() || identity.identity?.provider != catalogName) {
                return "CATALOG_PROVENANCE_MISSING"
            }
            val lookup = gateway.lookup(id, ProviderReadingLookupRequest(listOf(title), requestedLanguage = "en"))
            val page = (lookup as? ProviderCallResult.Success)?.value ?: return "SOURCE_LOOKUP_FAILED"
            val candidate = page.items.filter {
                it.externalWorkId.startsWith("mangadot:") && it.title.equals(title, ignoreCase = true)
            }.singleOrNull() ?: return "SOURCE_IDENTITY_MISSING_OR_AMBIGUOUS"
            val assessment = ProviderWorkSourcePortfolio.assess(
                providerId = id,
                facetId = "en",
                queryTitle = title,
                independentlyVerifiedAuthors = creators.toSet(),
                candidates = listOf(candidate),
            ).single()
            if (assessment.workVerification != ProviderWorkVerification.AUTHOR_CORROBORATED) {
                return assessment.workVerification.name
            }
            val ref = ProviderBindingRef(id, "en", candidate.externalWorkId)
            val first = gateway.chapters(id, ProviderReadingChaptersRequest(ref))
            val chapterPage = (first as? ProviderCallResult.Success)?.value ?: return "SOURCE_CHAPTER_PAGE_FAILED"
            val preview = MangaAllMangaDotNativeCoverage.fromFirstPage(
                manifest.version.code,
                ref,
                candidate,
                chapterPage,
            ) ?: return "SOURCE_COVERAGE_UNVERIFIED"
            val checked = ProviderWorkSourcePortfolio.assess(id, "en", title, creators.toSet(), listOf(preview)).single()
            if (!checked.canCollectProvisionalChapters || checked.canOpenReader || checked.canPromoteChapters) {
                return "PORTFOLIO_PREVIEW_REJECTED"
            }
            // The actual production portfolio remains closed to MangaDot until
            // a separately reviewed live gate has established this evidence.
            assertTrue(ProviderWorkSourcePortfolio.trustedCandidates(id, "en", listOf(preview)).isEmpty())
            return "WORK_CORROBORATED_PREVIEW"
        }

        var passed = 0
        try {
            for (index in listOf(1, 3, 10, 12)) {
                val outcome = try {
                    check(works[index - 1].jsonPrimitive.content)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    "UNCLASSIFIED_FAILURE"
                }
                if (outcome == "WORK_CORROBORATED_PREVIEW") passed++
                instrument.sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "MANGAALL_DOT_CORROBORATION|index=$index|catalog=$catalogName|outcome=$outcome",
                        )
                    },
                )
            }
            assertEquals(4, passed)
        } finally {
            runtimeRoot.deleteRecursively()
        }
    }
}

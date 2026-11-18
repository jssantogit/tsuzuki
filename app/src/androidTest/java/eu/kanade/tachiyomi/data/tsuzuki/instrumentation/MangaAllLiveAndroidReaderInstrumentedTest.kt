package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.PixelCopy
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.DefaultSkinValidationActivity
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.tsuzuki.integration.MangaUpdatesIntegrationProvider
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackage
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderManagedFileStore
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.CanonicalReaderTargetPlan
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.data.tsuzuki.integration.CanonicalMetadataSnapshotRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderProvisionalCursorRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderWorkSourceAssociationRepositoryImpl
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.catalog.interactor.ResolveMangaAllIndependentCatalogIdentity
import tachiyomi.domain.tsuzuki.catalog.interactor.toCanonicalReadingMetadata
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderBrowserPermission
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
import tachiyomi.domain.tsuzuki.provider.reading.CollectProviderReadingEvidence
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in real Android journey, with the UNCHANGED signed MangaAll package and
 * the production isolated QuickJS/Host HTTP broker, canonical refresh, Reader
 * source resolver, HTTP page loader, bitmap decoder and ReaderPageImageView.
 *
 * Only app.tsuzuki.dev on a disposable emulator is accepted. CI clears this
 * app's data before AND after this run. No user account or physical app touched.
 *
 * No source-authored title/HID, chapter number or image is a fixture. Every
 * checked work is user-supplied and independently catalog-attested; unresolved
 * source/edition identity is reported and never auto-bound by the test.
 *
 * A visual widget plus PixelCopy prove an image is rendered by the actual
 * ReaderPageImageView, not that the full ReaderActivity navigation/Compose UI
 * has been exercised. The latter remains a separate acceptance boundary.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllLiveAndroidReaderInstrumentedTest {
    private data class Work(val title: String, val format: CatalogItemFormat?)

    private data class Probe(
        var stage: String = "NOT_STARTED",
        var catalog: String = "NOT_QUERIED",
        var sqlChapters: Int = 0,
        var readerOptions: Int = 0,
        var pageCount: Int = 0,
        var imageDecoded: Boolean = false,
        var viewerLoaded: Boolean = false,
        var pixelCopied: Boolean = false,
        var phase: String = "BOOTSTRAP",
    ) {
        fun resultCode(): String = if (pixelCopied) "RENDERED_PIXELS" else stage
    }

    @Test(timeout = 1_800_000L)
    fun signedMangaAllRealReaderPagesRenderInDisposableAndroidEmulator() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real source network runs only in explicit workflow_dispatch", args.getString("mangaAllLiveReaderOptIn") == "true")
        val context = instrumentation.targetContext
        assertEquals("app.tsuzuki.dev", context.packageName)
        assertTrue(
            "Only disposable emulators may run a real Reader pixel probe",
            Build.FINGERPRINT.contains("generic") ||
                Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.PRODUCT.startsWith("sdk_gphone"),
        )
        val input = requireNotNull(args.getString("worksBase64")) { "Missing arbitrary work titles" }
        val works = Base64.decode(input, Base64.NO_WRAP).toString(Charsets.UTF_8)
            .lineSequence().map(String::trim).filter(String::isNotEmpty).map(::parseWork).toList()
        require(works.size in 1..30 && works.all { it.title.length in 1..180 })
        val shard = requireNotNull(args.getString("shardIndex")).toInt()
        val shardCount = requireNotNull(args.getString("shardCount")).toInt()
        require(shardCount in 1..3 && shard in 0 until shardCount)

        val archive = instrumentation.context.assets.open("mangaall/live-code8-package.tsz")
            .use { it.readBytes() }
        val expected = requireNotNull(args.getString("signedPackageSha256"))
        require(expected.matches(Regex("[a-f0-9]{64}")) && sha256(archive) == expected) {
            "LIVE_READER_SIGNED_PACKAGE_HASH_MISMATCH"
        }
        val parsed = ProviderPackageParser().parse(archive)
        require(parsed.manifest.id == "app.tsuzuki.mangaall" && parsed.manifest.version.code == 8L)
        require(parsed.manifest.capabilities.any { it.id == "reading.pages" && it.version == 1 })
        val network = requireNotNull(parsed.manifest.permissions.network)
        require(!network.localNetwork && network.origins.isNotEmpty())
        val providerId = ProviderId(parsed.manifest.id)
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = ProviderDescriptor(
                            id = providerId,
                            name = parsed.manifest.name,
                            version = ProviderVersion(
                                parsed.manifest.version.name,
                                parsed.manifest.version.code,
                            ),
                            origin = ProviderOrigin.Repository("app.tsuzuki.providers"),
                            runtime = ProviderRuntimeKind.SCRIPT,
                            capabilities = setOf(
                                ProviderCapabilities.ReadingLookupV1,
                                ProviderCapabilities.ReadingChaptersV1,
                                ProviderCapabilities.ReadingPagesV1,
                            ),
                            permissions = ProviderPermissionSet(
                                network = ProviderNetworkPermission(
                                    network.origins,
                                    network.localNetwork,
                                ),
                                browser = parsed.manifest.permissions.browser?.let {
                                    ProviderBrowserPermission(it.origins)
                                },
                            ),
                            settings = emptyList(),
                            contentLanguages = setOf("en", "pt-br"),
                        ),
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        facets = listOf(
                            ProviderFacetRef(providerId, "en"),
                            ProviderFacetRef(providerId, "pt-br"),
                        ),
                        configurationFingerprint = "live-official-code8",
                    ),
                )
            },
        )
        val managedFiles = ProviderManagedFileStore(context)
        val runtime = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = context.cacheDir.resolve("mangaall-live-reader-runtime"),
                managedFiles = managedFiles,
            ),
        )
        val gateway = ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource {
                if (it == providerId) {
                    ActiveProviderScriptPackage(
                        repositoryId = "app.tsuzuki.providers",
                        versionCode = 8L,
                        bytes = archive,
                        parsed = parsed,
                    )
                } else {
                    null
                }
            },
            runtimeClient = runtime,
            managedResources = managedFiles,
        )
        val app = context.applicationContext as App
        val integration = MangaUpdatesIntegrationProvider(app.graph.trackerManager)
        val catalogs = object : IntegrationRegistry {
            override fun searchProviders(): List<SearchProvider> = listOf(integration)
            override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
            override fun metadataProviders(): List<MetadataProvider> = listOf(integration)
            override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
            override fun ratingsProviders(): List<RatingsProvider> = emptyList()
            override fun trackingProviders(): List<TrackingProvider> = emptyList()
        }
        val bootstrap = ResolveMangaAllIndependentCatalogIdentity(catalogs)

        UiDevice.getInstance(instrumentation).wakeUp()
        val activity = instrumentation.startActivitySync(
            Intent(context, DefaultSkinValidationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as DefaultSkinValidationActivity
        val assigned = works.withIndex().filter { it.index % shardCount == shard }
        var pixels = 0
        try {
            for ((index, work) in assigned) {
                val state = Probe()
                try {
                    withTimeout(125_000L) {
                        inspectWork(
                            title = work,
                            caseId = caseId(work.title),
                            activity = activity,
                            state = state,
                            bootstrap = bootstrap,
                            catalogs = catalogs,
                            providerId = providerId,
                            registry = registry,
                            gateway = gateway,
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    state.stage = "${state.phase}_TIMEOUT"
                } catch (_: Exception) {
                    state.stage = "${state.phase}_EXCEPTION"
                }
                if (state.pixelCopied) pixels++
                val line = buildString {
                    append("MANGAALL_LIVE_ANDROID_READER")
                    append("|case=").append(caseId(work.title))
                    append("|index=").append(index)
                    append("|mode=DIRECT_LIVE_ANDROID_READER_VIEW")
                    append("|stage=").append(state.resultCode())
                    append("|catalog=").append(state.catalog)
                    append("|sqliteChapters=").append(state.sqlChapters)
                    append("|readerOptions=").append(state.readerOptions)
                    append("|pageMetadata=").append(state.pageCount)
                    append("|bitmapDecoded=").append(state.imageDecoded)
                    append("|readerViewLoaded=").append(state.viewerLoaded)
                    append("|windowPixelCopy=").append(state.pixelCopied)
                    append("|activityNavigation=NOT_EXECUTED")
                }
                instrumentation.sendStatus(1, Bundle().apply { putString("stream", line) })
            }
            instrumentation.sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "MANGAALL_LIVE_ANDROID_READER_BATCH|selected=${assigned.size}|" +
                            "rendered=$pixels|mode=DIRECT_LIVE_ANDROID_READER_VIEW",
                    )
                },
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
        assertTrue(
            "No page rendered from a genuine signed MangaAll source; examine closed stage reports",
            assigned.isEmpty() || pixels > 0,
        )
    }

    private suspend fun inspectWork(
        title: Work,
        caseId: String,
        activity: DefaultSkinValidationActivity,
        state: Probe,
        bootstrap: ResolveMangaAllIndependentCatalogIdentity,
        catalogs: IntegrationRegistry,
        providerId: ProviderId,
        registry: DefaultProviderRegistry,
        gateway: ScriptProviderReadingGateway,
    ) {
        state.phase = "CATALOG"
        if (title.format == CatalogItemFormat.NOVEL) {
            state.stage = "CATALOG_FORMAT_UNSUPPORTED"
            return
        }
        val result = withTimeout(35_000L) { bootstrap.execute(title.title, title.format) }
        state.catalog = result.status.name
        if (result.status != MangaAllCatalogIdentityStatus.RESOLVED) {
            state.stage = result.status.name
            return
        }
        val metadata = result.toCanonicalReadingMetadata()
        if (metadata == null) {
            state.stage = "CATALOG_UNVERIFIED"
            return
        }
        val owner = requireNotNull(result.identity)
        state.phase = "CANONICAL_REFRESH"
        val database = (activity.applicationContext as App).graph.database
        val titles = CanonicalTitleRepositoryImpl(database)
        val metadataRepository = CanonicalMetadataSnapshotRepositoryImpl(database)
        val bindings = ProviderReadingBindingRepositoryImpl(database)
        val chapters = CanonicalChapterRepositoryImpl(database)
        val evidence = ChapterEvidenceRepositoryImpl(database)
        val workSources = ProviderWorkSourceAssociationRepositoryImpl(database)
        val cursors = ProviderProvisionalCursorRepositoryImpl(database)
        val canonicalId = "mangaall-pixel-ci-" + caseId
        titles.getOrCreateByExternalIdentity(
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
        metadataRepository.upsertIfNewer(
            CanonicalMetadataSnapshot(
                canonicalTitleId = canonicalId,
                configurationFingerprint = "real-android-catalog-verified",
                metadata = metadata,
                refreshedAt = 1L,
            ),
        )
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titles,
            providerRegistry = registry,
            gateway = gateway,
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            canonicalMetadataSnapshotRepository = metadataRepository,
            workSourceAssociationRepository = workSources,
            provisionalCursorRepository = cursors,
        )
        val refresh = RefreshChapterEvidence(
            registry = catalogs,
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = evidence,
            ),
            providerReadingEvidence = collector,
        )
        val refreshed = refresh.execute(canonicalId, forceRefresh = true)
        if (refreshed.isFailure) {
            state.stage = "CANONICAL_REFRESH_FAILED"
            return
        }
        val sql = chapters.getChaptersByCanonicalTitleId(canonicalId)
        state.sqlChapters = sql.size
        if (sql.isEmpty()) {
            state.stage = "NO_SQL_CANONICAL_CHAPTERS_IN_RUN"
            return
        }
        val reader = ResolveProviderChapterReading(
            canonicalChapterRepository = chapters,
            evidenceRepository = evidence,
            bindingRepository = bindings,
            workSourceAssociationRepository = workSources,
            gateway = gateway,
            providerRegistry = registry,
        )
        state.phase = "READER_OPTIONS"
        val sampled = listOf(sql.first(), sql[sql.size / 2], sql.last())
            .distinctBy { it.id }
        for (chapter in sampled) {
            val options = reader.options(chapter.id)
            state.readerOptions += options.size
            // Never pick one of multiple editions by rank, title similarity or order.
            if (options.size != 1) continue
            state.phase = "PAGES"
            val result = reader.preparedContent(options.single())
            if (result !is ProviderCallResult.Success) {
                state.stage = "PROVIDER_PAGES_NOT_READY"
                continue
            }
            val prepared = result.value as? PreparedChapterContent.HttpPages
            if (prepared == null || prepared.pages.isEmpty()) {
                state.stage = "READER_HTTP_PAGES_UNAVAILABLE"
                continue
            }
            state.pageCount = prepared.pages.size
            val plan = CanonicalReaderTargetPlan.HttpPages(
                canonicalChapterId = chapter.id,
                pages = prepared.pages,
            )
            val chapterLoader = ProviderHttpChapterLoader.from(activity, plan)
            val readerChapter = ReaderChapter(
                ChapterImpl().apply {
                    id = Long.MIN_VALUE
                    url = "real-provider-pages:${chapter.id}"
                    name = "Live Reader probe"
                },
            )
            try {
                state.phase = "IMAGE_DOWNLOAD"
                chapterLoader.loadChapter(readerChapter)
                val firstPage = requireNotNull(readerChapter.pages).first()
                withTimeout(30_000L) {
                    requireNotNull(readerChapter.pageLoader).loadPage(firstPage)
                }
                if (firstPage.status != Page.State.Ready) {
                    state.stage = "IMAGE_HTTP_FAILED"
                    continue
                }
                val image = requireNotNull(firstPage.stream).invoke().use { it.readBytes() }
                state.phase = "IMAGE_DECODE"
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image, 0, image.size, dimensions)
                if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0) {
                    state.stage = "BITMAP_DECODE_FAILED"
                    continue
                }
                state.imageDecoded = true
                state.phase = "READER_VIEW"
                val (loaded, copied) = drawReaderView(activity, image)
                state.viewerLoaded = loaded
                state.pixelCopied = copied
                state.stage = MangaAllLiveReaderVerdict.classify(
                    pageMetadata = true,
                    downloaded = true,
                    decodable = state.imageDecoded,
                    viewerLoaded = loaded,
                    windowPixels = copied,
                )
                if (copied) return
            } finally {
                readerChapter.pageLoader?.recycle()
            }
        }
        if (state.stage == "NOT_STARTED") {
            state.stage = "READER_NO_UNAMBIGUOUS_SOURCE"
        }
    }

    private fun drawReaderView(
        activity: Activity,
        image: ByteArray,
    ): Pair<Boolean, Boolean> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val view = ReaderPageImageView(activity)
        val buffer = Buffer().write(image)
        instrumentation.runOnMainSync {
            activity.addContentView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            view.onImageLoaded = { loaded.countDown() }
            view.onImageLoadError = { failed.countDown() }
            view.setImage(
                buffer,
                isAnimated = false,
                config = ReaderPageImageView.Config(zoomDuration = 150),
            )
        }
        val succeeded = loaded.await(15, TimeUnit.SECONDS) && failed.count > 0L
        val copied = if (succeeded) {
            val width = activity.window.decorView.width
            val height = activity.window.decorView.height
            if (width <= 0 || height <= 0) {
                false
            } else {
                val screenshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val latch = CountDownLatch(1)
                var result = -1
                PixelCopy.request(
                    activity.window,
                    screenshot,
                    { code -> result = code; latch.countDown() },
                    Handler(Looper.getMainLooper()),
                )
                val completed = latch.await(8, TimeUnit.SECONDS)
                screenshot.recycle()
                completed && result == PixelCopy.SUCCESS
            }
        } else {
            false
        }
        instrumentation.runOnMainSync {
            view.recycle()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        buffer.close()
        return succeeded to copied
    }

    private fun parseWork(input: String): Work {
        val pieces = input.split("::", limit = 2)
        if (pieces.size == 1) return Work(input, null)
        val format = when (pieces[0].trim().lowercase().replace('-', '_')) {
            "manga" -> CatalogItemFormat.MANGA
            "manhwa" -> CatalogItemFormat.MANHWA
            "manhua" -> CatalogItemFormat.MANHUA
            "webtoon" -> CatalogItemFormat.WEBTOON
            "novel", "web_novel", "light_novel" -> CatalogItemFormat.NOVEL
            "one_shot", "oneshot" -> CatalogItemFormat.ONE_SHOT
            "doujin" -> CatalogItemFormat.DOUJIN
            else -> error("Unsupported explicit work format")
        }
        return Work(pieces[1].trim(), format)
    }

    private fun caseId(title: String): String {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
            .trim().replace(Regex("""\s+"""), " ").lowercase()
        return "user-" + sha256(normalized.toByteArray()).take(12)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackage
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.data.Database
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterReadingOption
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.RefreshProviderReadingChapters
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dedicated Android-test fixture for a SINGLE ReaderActivity session.
 * The production ReaderViewModel, Provider source resolver, isolated SCRIPT
 * runtime, chapter reconciler, HTTP page loader and viewer all execute normally.
 *
 * Only its injected Provider registry and package source are synthetic. No
 * repository enrolment, signature trust or production Provider is modified.
 */
internal class MangaAllReaderJourneyFixture(
    private val context: Context,
    private val reader: ReaderActivity,
    private val database: Database,
    private val chapter: CanonicalChapter,
) : AutoCloseable {
    private val providerId = ProviderId("app.tsuzuki.mangaall")
    private val workId = "mangafire:zk12"
    private val sourceChapterId = "mangafire:chapter-one"
    private val server = MockWebServer()
    private val failPageDelivery = AtomicBoolean(true)
    val pageDeliveryCalls = AtomicInteger()
    val imageRequests = AtomicInteger()
    private val runtimeCache = File(context.cacheDir, "mangaall-reader-journey")
    private val field = ReaderViewModel::class.java.getDeclaredField("resolveProviderChapterReading")
        .apply { isAccessible = true }
    private var originalResolver: Any? = null
    private var swapped = false
    lateinit var selectedOption: ProviderChapterReadingOption
        private set

    init {
        val image = syntheticImage()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/mangaall/chapters" -> MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """
                        {
                          "items":[{"providerChapterId":"mangafire:chapter-one","rawLabel":"Chapter 1",
                          "rawNumber":1.0,"language":"en"}],
                          "nextCursor":null
                        }
                        """.trimIndent(),
                    )
                    .build()
                "/mangaall/pages" -> {
                    pageDeliveryCalls.incrementAndGet()
                    if (failPageDelivery.get()) {
                        MockResponse.Builder().code(503).body("test failure").build()
                    } else {
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/json")
                            .body(
                                """
                                {"type":"page_list",
                                "pages":[{"url":"${server.url("/mangaall/image.png")}",
                                "headers":{},"allowedOrigins":["${origin()}"],"allowLocalNetwork":true}]}
                                """.trimIndent(),
                            )
                            .build()
                    }
                }
                "/mangaall/image.png" -> {
                    imageRequests.incrementAndGet()
                    MockResponse.Builder().code(200)
                        .addHeader("Content-Type", "image/png")
                        .body(Buffer().write(image)).build()
                }
                else -> MockResponse.Builder().code(404).body("unmapped fixture route").build()
            }
        }
        server.start()
    }

    fun allowPages() {
        failPageDelivery.set(false)
    }

    /**
     * Reconcile a real provider SCRIPT chapter snapshot to the canonical
     * chapter already in the disposable Reader database, then replace only
     * the ReaderViewModel's provider resolver for this fixture.
     */
    suspend fun attach() {
        val origin = origin()
        val registration = ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = providerId,
                name = "MangaAll (isolated test)",
                version = ProviderVersion("0.5.2", 7),
                origin = ProviderOrigin.Repository("mangaall-android-fixture"),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = setOf(
                    ProviderCapabilities.ReadingLookupV1,
                    ProviderCapabilities.ReadingChaptersV1,
                    ProviderCapabilities.ReadingPagesV1,
                ),
                permissions = ProviderPermissionSet(
                    network = ProviderNetworkPermission(setOf(origin), localNetwork = true),
                ),
                settings = emptyList(),
                contentLanguages = setOf("en"),
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
            facets = listOf(ProviderFacetRef(providerId, "en")),
            configurationFingerprint = "android-journey",
        )
        val registry = DefaultProviderRegistry { listOf(registration) }
        val script = """
            const origin = "$origin";
            const workId = "mangafire:zk12";
            const chapterId = "mangafire:chapter-one";
            export default {
              reading: {
                lookup: async () => ({items:[],nextCursor:null}),
                chapters: async (request) => {
                  if (request.binding.externalWorkId !== workId) throw Error("wrong work");
                  return JSON.parse(await tsuzuki.http.get(origin + "/mangaall/chapters"));
                },
                pages: async (request) => {
                  if (request.binding.externalWorkId !== workId ||
                      request.providerChapterId !== chapterId ||
                      request.binding.facetId !== "en") throw Error("wrong exact source");
                  return JSON.parse(await tsuzuki.http.get(origin + "/mangaall/pages"));
                }
              }
            };
        """.trimIndent()
        val bytes = syntheticPackage(origin, script)
        val parsed = ProviderPackageParser().parse(bytes)
        val gateway = ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource {
                ActiveProviderScriptPackage(
                    repositoryId = "mangaall-android-fixture",
                    versionCode = 7,
                    bytes = bytes,
                    parsed = parsed,
                )
            },
            runtimeClient = ProviderRuntimeClient(
                context = context,
                hostInvocationFactory = ProviderHostInvocationFactory(
                    context = context,
                    storageRoot = runtimeCache.apply { deleteRecursively() },
                ),
            ),
        )
        val binding = ProviderReadingBinding(
            id = "mangaall-journey-${chapter.id}",
            canonicalTitleId = chapter.canonicalTitleId,
            ref = ProviderBindingRef(providerId, "en", workId),
            verification = ProviderBindingVerification.USER_CONFIRMED,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1,
            updatedAt = 1,
        )
        val canonicalRepository = CanonicalChapterRepositoryImpl(database)
        val evidenceRepository = ChapterEvidenceRepositoryImpl(database)
        val bindingRepository = ProviderReadingBindingRepositoryImpl(database)
        bindingRepository.upsert(binding)
        val refresh = RefreshProviderReadingChapters(
            gateway = gateway,
            evidenceAdapter = ProviderChapterEvidenceAdapter(clock = { System.currentTimeMillis() }),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = canonicalRepository,
                evidenceRepository = evidenceRepository,
            ),
        )
        val refreshed = refresh.execute(binding)
        assertTrue("MangaAll SCRIPT chapter refresh must succeed: $refreshed", refreshed is ProviderCallResult.Success)
        assertEquals(1, (refreshed as ProviderCallResult.Success).value)
        val resolver = ResolveProviderChapterReading(
            canonicalChapterRepository = canonicalRepository,
            evidenceRepository = evidenceRepository,
            bindingRepository = bindingRepository,
            gateway = gateway,
            providerRegistry = registry,
        )
        val options = resolver.options(chapter.id)
        assertEquals("Provider snapshot must reconcile to the exact canonical Reader chapter", 1, options.size)
        selectedOption = options.single()
        assertEquals(providerId, selectedOption.providerId)
        assertEquals(workId, selectedOption.externalWorkId)
        assertEquals(sourceChapterId, selectedOption.providerChapterId)
        originalResolver = field.get(reader.viewModel)
        field.set(reader.viewModel, resolver)
        swapped = true
    }

    private fun origin() = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }

    private fun syntheticPackage(origin: String, script: String): ByteArray {
        val manifest = """
            {
              "manifestVersion":1,
              "id":"app.tsuzuki.mangaall",
              "name":"MangaAll (isolated test)",
              "version":{"name":"0.5.2","code":7},
              "minHostApi":1,
              "entrypoint":"main.js",
              "capabilities":[
                {"id":"reading.lookup","version":1},
                {"id":"reading.chapters","version":1},
                {"id":"reading.pages","version":1}
              ],
              "permissions":{
                "network":{"origins":["$origin"],"localNetwork":true},
                "browser":{"origins":[]}
              },
              "contentLanguages":["en"],
              "settings":[]
            }
        """.trimIndent()
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                fun add(path: String, body: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(body.encodeToByteArray())
                    zip.closeEntry()
                }
                add("manifest.json", manifest)
                add("main.js", script)
            }
            output.toByteArray()
        }
    }

    private fun syntheticImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(12, 168, 82))
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        if (swapped) {
            field.set(reader.viewModel, originalResolver)
            swapped = false
        }
        server.close()
        runtimeCache.deleteRecursively()
    }
}

package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackage
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import eu.kanade.tachiyomi.ui.reader.CanonicalReaderTargetPlan
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.ReaderChapterLoader
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
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
    private val originalCode7Package: Boolean = false,
) : AutoCloseable {
    private val providerId = ProviderId("app.tsuzuki.mangaall")
    private val workId = "mangafire:zk12"
    private val sourceChapterId = if (originalCode7Package) {
        "mangafire:punpun-fixture-ch1"
    } else {
        "mangafire:chapter-one"
    }
    private val server = MockWebServer()
    private val failPageDelivery = AtomicBoolean(true)
    val pageDeliveryCalls = AtomicInteger()
    val imageRequests = AtomicInteger()
    private val runtimeCache = File(context.cacheDir, "mangaall-reader-journey")
    private val field = ReaderViewModel::class.java.getDeclaredField("resolveProviderChapterReading")
        .apply { isAccessible = true }
    private val pageClientField = ReaderViewModel::class.java.getDeclaredField("providerHttpLoaderFactory")
        .apply { isAccessible = true }
    private var originalResolver: Any? = null
    private var originalPageLoaderFactory: Any? = null
    private var swapped = false
    private var pageLoaderSwapped = false
    private val code7ImageUrl = "https://mfcdn.cc/replay/punpun/ch1.png"
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
                                "headers":{}}]}
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
                    network = if (originalCode7Package) {
                        ProviderNetworkPermission(
                            setOf(
                                "https://api.mangadex.org",
                                "https://mangadot.net",
                                "https://mangafire.to",
                                "https://mfcdn.cc",
                            ),
                            localNetwork = false,
                        )
                    } else {
                        ProviderNetworkPermission(setOf(origin), localNetwork = true)
                    },
                ),
                settings = emptyList(),
                contentLanguages = setOf("en"),
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
            facets = listOf(ProviderFacetRef(providerId, "en")),
            configurationFingerprint = "android-journey",
        )
        val registry = DefaultProviderRegistry(registrations = { listOf(registration) })
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
        val bytes = if (originalCode7Package) {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("mangaall/code7-package.tsz").use { it.readBytes() }
        } else {
            syntheticPackage(origin, script)
        }
        val parsed = ProviderPackageParser().parse(bytes)
        if (originalCode7Package) {
            assertEquals("app.tsuzuki.mangaall", parsed.manifest.id)
            assertEquals("0.5.2", parsed.manifest.version.name)
            assertEquals(7L, parsed.manifest.version.code)
            assertTrue(parsed.entries.containsKey("modules/router.js"))
            assertEquals("mangafire:zk12", workId)
            val replay = InstrumentationRegistry.getInstrumentation().context.assets
                .open("mangaall/code7-replay.json").bufferedReader()
                .use { JSONObject(it.readText()) }
            val punpun = replay.getJSONArray("cases").getJSONObject(0)
            assertEquals("punpun", punpun.getString("caseId"))
            assertEquals(workId, punpun.getString("sourceWorkId"))
            assertEquals(sourceChapterId, punpun.getString("sourceChapterId"))
            assertEquals(
                code7ImageUrl,
                punpun.getJSONObject("pages").getJSONArray("pages")
                    .getJSONObject(0).getString("url"),
            )
        }
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
                    baseHttpClient = if (originalCode7Package) packagedCode7HttpClient() else OkHttpClient(),
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
        if (originalCode7Package) {
            originalPageLoaderFactory = pageClientField.get(reader.viewModel)
            val images = packagedCode7ImageClient()
            val pageFactory: (CanonicalReaderTargetPlan.HttpPages) -> ReaderChapterLoader = { plan ->
                ProviderHttpChapterLoader.from(context, plan, client = images)
            }
            pageClientField.set(reader.viewModel, pageFactory)
            pageLoaderSwapped = true
        }
        originalResolver = field.get(reader.viewModel)
        field.set(reader.viewModel, resolver)
        swapped = true
    }


    /**
     * No real network request leaves this test: neither this interceptor nor
     * the image interceptor ever calls proceed(). The production Host still
     * validates the unmodified .tsz origin allowlist for every request.
     */
    private fun packagedCode7HttpClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            assertEquals("GET", request.method)
            assertEquals("https", url.scheme)
            assertEquals("mangafire.to", url.host)
            val (status, response) = when (url.encodedPath) {
                "/api/titles/zk12/chapters" -> {
                    assertEquals("en", url.queryParameter("language"))
                    assertEquals("asc", url.queryParameter("order"))
                    assertEquals("1", url.queryParameter("page"))
                    200 to JSONObject()
                        .put(
                            "items",
                            JSONArray().put(
                                JSONObject().put("id", "punpun-fixture-ch1")
                                    .put("number", 1).put("name", "Chapter 1")
                                    .put("language", "en").put("type", "official")
                                    .put("createdAt", 1720000000),
                            ),
                        )
                        .put("meta", JSONObject().put("hasNext", false).put("lastPage", 1))
                        .toString()
                }
                "/api/chapters/punpun-fixture-ch1" -> {
                    pageDeliveryCalls.incrementAndGet()
                    if (failPageDelivery.get()) {
                        503 to "<html>synthetic failure</html>"
                    } else {
                        200 to JSONObject().put(
                            "data",
                            JSONObject().put("id", "punpun-fixture-ch1")
                                .put("mangaHid", "zk12").put("language", "en")
                                .put("pages", JSONArray().put(JSONObject().put("url", code7ImageUrl))),
                        ).toString()
                    }
                }
                else -> error("Unrecognized original-code7 HTTP route")
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Offline code7 fixture")
                .body(response.toResponseBody())
                .build()
        }.build()

    private fun packagedCode7ImageClient(): OkHttpClient {
        val image = syntheticImage()
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals(code7ImageUrl, request.url.toString())
            assertEquals("https://mangafire.to", request.header("Referer"))
            imageRequests.incrementAndGet()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Synthetic PNG")
                .body(image.toResponseBody())
                .build()
        }.build()
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
        if (pageLoaderSwapped) {
            pageClientField.set(reader.viewModel, originalPageLoaderFactory)
            pageLoaderSwapped = false
        }
        if (swapped) {
            field.set(reader.viewModel, originalResolver)
            swapped = false
        }
        server.close()
        runtimeCache.deleteRecursively()
    }
}

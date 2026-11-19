package eu.kanade.tachiyomi.provider.reading

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaAllInstrumentationVerdict
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderManagedFileStore
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeBindingPhase
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.CanonicalLocalReaderFormat
import eu.kanade.tachiyomi.ui.reader.CanonicalReaderTargetPlan
import eu.kanade.tachiyomi.ui.reader.loader.LocalChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCoverageOutcome
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBindingRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkDetailOutcome
import tachiyomi.domain.tsuzuki.provider.reading.RefreshProviderReadingChapters
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class ScriptProviderReadingGatewayIsolationTest {

    @Test
    fun apiJsonProvider_reachesReadingGatewayThroughIsolatedRuntime() = runBlocking {
        val server = MockWebServer()
        server.start()
        val origin = server.origin()
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "externalWorkId": "work-42",
                          "title": "API Title",
                          "aliases": [],
                          "url": "$origin/title/42",
                          "language": "en"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "providerChapterId": "chapter-1",
                          "rawLabel": "Chapter 1",
                          "rawNumber": 1.0,
                          "language": "en"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("reader-image-bytes")
                .build(),
        )

        lateinit var providerRegistry: DefaultProviderRegistry
        val gateway = gateway(
            networkOrigins = setOf(origin),
            localNetwork = true,
            main = """
                const base = "$origin";
                export default {
                  reading: {
                    lookup: async () =>
                      JSON.parse(await tsuzuki.http.get(base + "/lookup")),
                    chapters: async () =>
                      JSON.parse(await tsuzuki.http.get(base + "/chapters")),
                    pages: async () => ({
                      type: "page_list",
                      pages: [{url: base + "/pages/001.jpg", headers: {}}]
                    })
                  }
                };
            """.trimIndent(),
            onRegistry = { providerRegistry = it },
        )

        try {
            val lookup = gateway.lookup(
                PROVIDER_ID,
                ProviderReadingLookupRequest(listOf("API Title")),
            ) as ProviderCallResult.Success
            assertEquals("work-42", lookup.value.items.single().externalWorkId)
            assertEquals("$origin/title/42", lookup.value.items.single().url)

            val binding = ProviderBindingRef(PROVIDER_ID, "en", "work-42")
            val chapters = gateway.chapters(
                PROVIDER_ID,
                ProviderReadingChaptersRequest(binding),
            ) as ProviderCallResult.Success
            assertEquals("chapter-1", chapters.value.items.single().providerChapterId)

            val pages = gateway.pages(
                PROVIDER_ID,
                ProviderReadingPagesRequest(binding, "chapter-1"),
            ) as ProviderCallResult.Success
            val pageList = pages.value as ProviderReadingDelivery.PageList
            assertEquals("$origin/pages/001.jpg", pageList.pages.single().url)

            assertEquals(
                "reader-image-bytes",
                readFirstProviderPage(
                    pageList = pageList,
                    canonicalChapterId = "canonical-chapter-1",
                ),
            )

            assertEquals("/lookup", server.takeRequest().url.encodedPath)
            assertEquals("/chapters", server.takeRequest().url.encodedPath)
            assertEquals("/pages/001.jpg", server.takeRequest().url.encodedPath)
        } finally {
            server.close()
        }
    }

    @Test
    fun chapterPagination_invokesSecondPageThroughIsolatedRuntimeBeforeDeadline() = runBlocking {
        val server = MockWebServer()
        server.start()
        val origin = server.origin()
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "providerChapterId": "chapter-100",
                          "rawLabel": "Ch. 100",
                          "rawNumber": 100.0,
                          "language": "en"
                        }
                      ],
                      "nextCursor": "2"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "providerChapterId": "chapter-101",
                          "rawLabel": "Ch. 101",
                          "rawNumber": 101.0,
                          "language": "en"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val bindingPhases = mutableListOf<ProviderRuntimeBindingPhase>()
        val gateway = gateway(
            networkOrigins = setOf(origin),
            localNetwork = true,
            onBindingPhase = { bindingPhases.add(it) },
            main = """
                const origin = "$origin";
                export default {
                  reading: {
                    lookup: async () => ({items: [], nextCursor: null}),
                    chapters: async (request) => {
                      const page = request.cursor ?? "1";
                      return JSON.parse(await tsuzuki.http.get(origin + "/chapters?page=" + page));
                    },
                    pages: async () => ({type: "page_list", pages: []})
                  }
                };
            """.trimIndent(),
        )

        try {
            val binding = ProviderBindingRef(PROVIDER_ID, "en", "long-work")
            val result = withTimeout(10_000) {
                RefreshProviderReadingChapters(
                    gateway = gateway,
                    evidenceAdapter = ProviderChapterEvidenceAdapter(),
                    reconcileChapterEvidence = ReconcileChapterEvidence(
                        parser = ParseCanonicalChapterLabel(),
                        canonicalChapterRepository = InMemoryCanonicalChapterRepository(),
                        evidenceRepository = InMemoryChapterEvidenceRepository(),
                    ),
                ).execute(
                    ProviderReadingBinding(
                        id = "preferred-long-work",
                        canonicalTitleId = "long-title",
                        ref = binding,
                        verification = ProviderBindingVerification.EXACT,
                        availability = ProviderBindingAvailability.AVAILABLE,
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                )
            }
            assertTrue(result is ProviderCallResult.Success)
            assertEquals(2, (result as ProviderCallResult.Success).value)
            assertEquals("1", server.takeRequest().url.queryParameter("page"))
            assertEquals("2", server.takeRequest().url.queryParameter("page"))
            assertEquals(2, server.requestCount)
            assertEquals(1, bindingPhases.count { it == ProviderRuntimeBindingPhase.BIND_STARTED })
            assertEquals(1, bindingPhases.count { it == ProviderRuntimeBindingPhase.BIND_REUSED })
        } finally {
            server.close()
        }
    }

    @Test
    fun apiJsonProvider_reconcilesCanonicalChapterAndReadsThroughProviderResolver() = runBlocking {
        val server = MockWebServer()
        server.start()
        val origin = server.origin()
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "externalWorkId": "work-canonical",
                          "title": "Canonical API Title",
                          "aliases": [],
                          "url": "$origin/title/canonical",
                          "language": "en"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "providerChapterId": "chapter-canonical-1",
                          "rawLabel": "Chapter 1",
                          "rawNumber": 1.0,
                          "language": "en"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("canonical-reader-image")
                .build(),
        )

        lateinit var providerRegistry: DefaultProviderRegistry
        val gateway = gateway(
            networkOrigins = setOf(origin),
            localNetwork = true,
            main = """
                const base = "$origin";
                export default {
                  reading: {
                    lookup: async () =>
                      JSON.parse(await tsuzuki.http.get(base + "/lookup")),
                    chapters: async () =>
                      JSON.parse(await tsuzuki.http.get(base + "/chapters")),
                    pages: async () => ({
                      type: "page_list",
                      pages: [{url: base + "/page.jpg", headers: {}}]
                    })
                  }
                };
            """.trimIndent(),
            onRegistry = { providerRegistry = it },
        )

        try {
            val lookup = gateway.lookup(
                PROVIDER_ID,
                ProviderReadingLookupRequest(listOf("Canonical API Title")),
            ) as ProviderCallResult.Success
            val candidate = lookup.value.items.single()
            val binding = ProviderReadingBinding(
                id = "binding-canonical",
                canonicalTitleId = "canonical-title",
                ref = ProviderBindingRef(
                    providerId = PROVIDER_ID,
                    facetId = candidate.language,
                    externalWorkId = candidate.externalWorkId,
                ),
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val chapterRepository = InMemoryCanonicalChapterRepository()
            val evidenceRepository = InMemoryChapterEvidenceRepository()
            val bindingRepository = SingleProviderReadingBindingRepository(binding)
            val refresh = RefreshProviderReadingChapters(
                gateway = gateway,
                evidenceAdapter = ProviderChapterEvidenceAdapter(clock = { 10L }),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapterRepository,
                    evidenceRepository = evidenceRepository,
                ),
            )

            val refreshResult = refresh.execute(binding) as ProviderCallResult.Success
            assertEquals(1, refreshResult.value)
            val canonicalChapter = chapterRepository
                .getByCanonicalTitleId("canonical-title")
                .single()

            val resolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
                bindingRepository = bindingRepository,
                gateway = gateway,
                providerRegistry = providerRegistry,
            )
            val option = resolver.options(canonicalChapter.id).single()
            assertEquals("chapter-canonical-1", option.providerChapterId)

            val prepared = resolver.preparedContent(option) as ProviderCallResult.Success
            val content = prepared.value as PreparedChapterContent.HttpPages
            val plan = CanonicalReaderTargetPlan.HttpPages(
                canonicalChapterId = canonicalChapter.id,
                pages = content.pages,
            )
            val readerLoader = ProviderHttpChapterLoader.from(
                InstrumentationRegistry.getInstrumentation().targetContext,
                plan,
            )
            val readerChapter = ReaderChapter(
                ChapterImpl().apply {
                    id = Long.MIN_VALUE
                    url = "provider-pages:${canonicalChapter.id}"
                    name = "Canonical Provider chapter"
                },
            )
            try {
                readerLoader.loadChapter(readerChapter)
                val readerPage = readerChapter.pages!!.single()
                readerChapter.pageLoader!!.loadPage(readerPage)
                assertEquals(Page.State.Ready, readerPage.status)
                assertEquals(
                    "canonical-reader-image",
                    readerPage.stream!!.invoke().use { it.readBytes().decodeToString() },
                )
            } finally {
                readerChapter.pageLoader?.recycle()
            }

            assertEquals("/lookup", server.takeRequest().url.encodedPath)
            assertEquals("/chapters", server.takeRequest().url.encodedPath)
            assertEquals("/page.jpg", server.takeRequest().url.encodedPath)
        } finally {
            server.close()
        }
    }

    /**
     * One isolated SCRIPT Host -> canonical refresh -> revalidated chapter -> HTTP
     * Reader byte-loader journey with a MangaAll-shaped local Provider fixture.
     * This is NOT the signed MangaAll package or an Activity/UI pixel smoke.
     */
    @Test
    fun mangaAllSyntheticJourney_reconcilesCanonicalChaptersAndReaderPages() = runBlocking {
        val mangaAll = ProviderId("app.tsuzuki.mangaall")
        val server = MockWebServer()
        server.start()
        val origin = server.origin()
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "externalWorkId": "mangafire:zk12",
                          "title": "Goodnight Punpun",
                          "aliases": ["Oyasumi Punpun"],
                          "url": "$origin/title/canonical",
                          "language": "en"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "items": [
                        {
                          "providerChapterId": "mangafire:chapter-one",
                          "rawLabel": "Chapter 1",
                          "rawNumber": 1.0,
                          "language": "en"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("mangaall-reader-fixture-image")
                .build(),
        )

        lateinit var providerRegistry: DefaultProviderRegistry
        val gateway = gateway(
            providerId = mangaAll,
            networkOrigins = setOf(origin),
            localNetwork = true,
            main = """
                const base = "$origin";
                export default {
                  reading: {
                    lookup: async () =>
                      JSON.parse(await tsuzuki.http.get(base + "/lookup")),
                    chapters: async () =>
                      JSON.parse(await tsuzuki.http.get(base + "/chapters")),
                    pages: async () => ({
                      type: "page_list",
                      pages: [{url: base + "/page.jpg", headers: {}}]
                    })
                  }
                };
            """.trimIndent(),
            onRegistry = { providerRegistry = it },
        )

        try {
            val lookup = gateway.lookup(
                mangaAll,
                ProviderReadingLookupRequest(listOf("Oyasumi Punpun")),
            ) as ProviderCallResult.Success
            val candidate = lookup.value.items.single()
            assertEquals("mangafire:zk12", candidate.externalWorkId)
            assertTrue(candidate.aliases.contains("Oyasumi Punpun"))
            val binding = ProviderReadingBinding(
                id = "binding-canonical",
                canonicalTitleId = "punpun-canonical",
                ref = ProviderBindingRef(
                    providerId = mangaAll,
                    facetId = candidate.language,
                    externalWorkId = candidate.externalWorkId,
                ),
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val chapterRepository = InMemoryCanonicalChapterRepository()
            val evidenceRepository = InMemoryChapterEvidenceRepository()
            val bindingRepository = SingleProviderReadingBindingRepository(binding)
            val refresh = RefreshProviderReadingChapters(
                gateway = gateway,
                evidenceAdapter = ProviderChapterEvidenceAdapter(clock = { 10L }),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapterRepository,
                    evidenceRepository = evidenceRepository,
                ),
            )

            val refreshResult = refresh.execute(binding) as ProviderCallResult.Success
            assertEquals(1, refreshResult.value)
            val canonicalChapter = chapterRepository
                .getByCanonicalTitleId("punpun-canonical")
                .single()

            val resolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
                bindingRepository = bindingRepository,
                gateway = gateway,
                providerRegistry = providerRegistry,
            )
            val option = resolver.options(canonicalChapter.id).single()
            assertEquals("mangafire:chapter-one", option.providerChapterId)
            assertEquals("mangafire", option.contentSourceId)
            val stale = resolver.preparedContent(
                option.copy(providerChapterId = "mangadex:foreign-chapter"),
            )
            assertTrue(stale is ProviderCallResult.Failure)

            val prepared = resolver.preparedContent(option) as ProviderCallResult.Success
            val content = prepared.value as PreparedChapterContent.HttpPages
            val plan = CanonicalReaderTargetPlan.HttpPages(
                canonicalChapterId = canonicalChapter.id,
                pages = content.pages,
            )
            val readerLoader = ProviderHttpChapterLoader.from(
                InstrumentationRegistry.getInstrumentation().targetContext,
                plan,
            )
            val readerChapter = ReaderChapter(
                ChapterImpl().apply {
                    id = Long.MIN_VALUE
                    url = "provider-pages:${canonicalChapter.id}"
                    name = "Canonical Provider chapter"
                },
            )
            try {
                readerLoader.loadChapter(readerChapter)
                val readerPage = readerChapter.pages!!.single()
                readerChapter.pageLoader!!.loadPage(readerPage)
                assertEquals(Page.State.Ready, readerPage.status)
                assertEquals(
                    "mangaall-reader-fixture-image",
                    readerPage.stream!!.invoke().use { it.readBytes().decodeToString() },
                )
            } finally {
                readerChapter.pageLoader?.recycle()
            }

            assertEquals("/lookup", server.takeRequest().url.encodedPath)
            assertEquals("/chapters", server.takeRequest().url.encodedPath)
            assertEquals("/page.jpg", server.takeRequest().url.encodedPath)
        } finally {
            server.close()
        }
    }

    /**
     * Loads the UNMODIFIED distributable code7 .tsz built with tooling/build_provider.py.
     * Only the test's OkHttp transport is synthetic. All requests still pass
     * the production origin policy and the actual isolated QuickJS package runtime.
     * No official repository enrollment or signature acceptance is simulated.
     */
    @Test
    fun mangaAllRealCode7Package_executesOfflineHttpCanonicalAndPages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageBytes = instrumentation.context.assets.open("mangaall/code7-package.tsz")
            .use { it.readBytes() }
        val parsed = ProviderPackageParser().parse(packageBytes)
        assertEquals("app.tsuzuki.mangaall", parsed.manifest.id)
        assertEquals("0.5.2", parsed.manifest.version.name)
        assertEquals(7L, parsed.manifest.version.code)
        assertEquals("main.js", parsed.manifest.entrypoint)
        assertTrue(!parsed.manifest.permissions.network!!.localNetwork)
        val replay = instrumentation.context.assets.open("mangaall/code7-replay.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(7, replay.getInt("providerVersionCode"))
        val cases = replay.getJSONArray("cases")
        assertEquals(2, cases.length())
        val observed = mutableSetOf<String>()
        repeat(cases.length()) { index ->
            val vector = cases.getJSONObject(index)
            val caseId = vector.getString("caseId")
            observed += caseId
            val expectedWorkId = vector.getString("sourceWorkId")
            val expectedChapterId = vector.getString("sourceChapterId")
            val title = vector.getString("requestedTitle")
            val hid = expectedWorkId.removePrefix("mangafire:")
            val sourceChapter = expectedChapterId.removePrefix("mangafire:")
            val sourceTitle = when (caseId) {
                "punpun" -> "Goodnight Punpun"
                "koe" -> "Koe no Katachi"
                else -> error("Unexpected packaged source case")
            }
            val alias = when (caseId) {
                "punpun" -> "Oyasumi Punpun"
                else -> "A Silent Voice"
            }
            val author = when (caseId) {
                "punpun" -> "Inio Asano"
                else -> "Yoshitoki Ooima"
            }
            val imageUrl = vector.getJSONObject("pages").getJSONArray("pages")
                .getJSONObject(0).getString("url")
            val blockedChapters = AtomicBoolean(false)
            val blockedLookup = AtomicBoolean(false)
            val blockedPages = AtomicBoolean(false)
            val httpCalls = AtomicInteger()
            val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val url = request.url
                assertEquals("GET", request.method)
                assertEquals("https", url.scheme)
                assertTrue("Synthetic contract exceeded the HTTP bound", httpCalls.incrementAndGet() <= 100)
                // Fail closed: the interceptor NEVER calls chain.proceed, so the
                // original HTTPS hostname cannot reach a remote server.
                val (status, body) = when {
                    url.host == "api.mangadex.org" && url.encodedPath == "/chapter" ->
                        200 to """{"result":"ok","data":[],"limit":100,"offset":0,"total":0}"""
                    url.host == "api.mangadex.org" && url.encodedPath == "/manga" ->
                        200 to """{"result":"ok","data":[],"limit":25,"offset":0,"total":0}"""
                    url.host == "mangadot.net" && url.encodedPath == "/api/search" ->
                        200 to """{"results":[]}"""
                    url.host == "mangafire.to" && url.encodedPath == "/api/titles" -> {
                        assertEquals(title, url.queryParameter("keyword"))
                        if (blockedLookup.get()) {
                            403 to "<html>synthetic challenge</html>"
                        } else {
                            200 to JSONObject().put(
                                "items",
                                JSONArray().put(
                                    JSONObject().put("hid", hid).put("slug", caseId)
                                        .put("title", sourceTitle)
                                        .put("aliases", JSONArray().put(alias)),
                                ),
                            ).toString()
                        }
                    }
                    url.host == "mangafire.to" && url.encodedPath == "/api/titles/$hid" ->
                        200 to JSONObject().put(
                            "data",
                            JSONObject().put("hid", hid).put("title", sourceTitle)
                                .put("aliases", JSONArray().put(alias))
                                .put("authors", JSONArray().put(author)),
                        ).toString()
                    url.host == "mangafire.to" && url.encodedPath == "/api/titles/$hid/chapters" -> {
                        assertEquals("en", url.queryParameter("language"))
                        assertEquals("asc", url.queryParameter("order"))
                        assertEquals("1", url.queryParameter("page"))
                        if (blockedChapters.get()) {
                            403 to "<html>synthetic challenge</html>"
                        } else {
                            200 to JSONObject()
                                .put(
                                    "items",
                                    JSONArray().put(
                                        JSONObject().put("id", sourceChapter).put("number", 1)
                                            .put("name", "Chapter 1").put("language", "en")
                                            .put("type", "official").put("createdAt", 1720000000),
                                    ),
                                )
                                .put("meta", JSONObject().put("hasNext", false).put("lastPage", 1))
                                .toString()
                        }
                    }
                    url.host == "mangafire.to" && url.encodedPath == "/api/chapters/$sourceChapter" -> {
                        if (blockedPages.get()) {
                            503 to "<html>synthetic failure</html>"
                        } else {
                            200 to JSONObject().put(
                                "data",
                                JSONObject().put("id", sourceChapter)
                                    .put("mangaHid", hid).put("language", "en")
                                    .put("pages", JSONArray().put(JSONObject().put("url", imageUrl))),
                            ).toString()
                        }
                    }
                    else -> error("Unexpected HTTP request in offline code7 fixture")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("Synthetic fixture")
                    .body(body.toResponseBody())
                    .build()
            }.build()
            val providerId = ProviderId(parsed.manifest.id)
            lateinit var registry: DefaultProviderRegistry
            val gateway = gateway(
                main = "",
                providerId = providerId,
                networkOrigins = parsed.manifest.permissions.network!!.origins,
                browserOrigins = parsed.manifest.permissions.browser?.origins.orEmpty(),
                localNetwork = false,
                packageBytesOverride = packageBytes,
                baseHttpClient = httpClient,
                onRegistry = { registry = it },
            )

            val richLookup = gateway.lookup(
                providerId,
                ProviderReadingLookupRequest(
                    titles = listOf(title),
                    requestedLanguage = "en",
                    coverageEvidenceVersion = 1,
                ),
            ) as ProviderCallResult.Success
            val candidate = richLookup.value.items.single { it.externalWorkId == expectedWorkId }
            assertEquals(sourceTitle, candidate.title)
            assertTrue(candidate.aliases.contains(alias))
            assertEquals(listOf(author), candidate.authors)
            assertEquals(ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES, candidate.detailOutcome)
            assertEquals(ProviderCoverageOutcome.OBSERVED, candidate.coverageOutcome)
            assertEquals(1, candidate.coverageObservedCount)
            assertEquals(1000, candidate.coverageMinMilli)
            assertEquals(1000, candidate.coverageMaxMilli)
            assertEquals(false, candidate.coverageHasMore)

            val legacy = gateway.lookup(
                providerId,
                ProviderReadingLookupRequest(titles = listOf(title), requestedLanguage = "en"),
            ) as ProviderCallResult.Success
            assertEquals(expectedWorkId, legacy.value.items.single().externalWorkId)
            assertEquals(null, legacy.value.items.single().coverageOutcome)

            val canonicalTitleId = "code7-package-$caseId"
            val chapterRepository = InMemoryCanonicalChapterRepository()
            val evidenceRepository = InMemoryChapterEvidenceRepository()
            val refresh = RefreshProviderReadingChapters(
                gateway = gateway,
                evidenceAdapter = ProviderChapterEvidenceAdapter(clock = { 10L }),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapterRepository,
                    evidenceRepository = evidenceRepository,
                ),
            )
            // Complete-empty MangaDex/en is an independent source fact.
            if (caseId == "punpun") {
                val dex = ProviderReadingBinding(
                    id = "old-dex",
                    canonicalTitleId = canonicalTitleId,
                    ref = ProviderBindingRef(
                        providerId,
                        "en",
                        "mangadex:11111111-2222-4333-8444-555555555555",
                    ),
                    verification = ProviderBindingVerification.USER_CONFIRMED,
                    availability = ProviderBindingAvailability.AVAILABLE,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
                val empty = refresh.execute(dex) as ProviderCallResult.Success
                assertEquals(0, empty.value)
                assertTrue(chapterRepository.getByCanonicalTitleId(canonicalTitleId).isEmpty())
            }

            val binding = ProviderReadingBinding(
                id = "verified-code7-$caseId",
                canonicalTitleId = canonicalTitleId,
                ref = ProviderBindingRef(providerId, "en", expectedWorkId),
                verification = ProviderBindingVerification.USER_CONFIRMED,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val refreshed = refresh.execute(binding) as ProviderCallResult.Success
            assertEquals(1, refreshed.value)
            val canonical = chapterRepository.getByCanonicalTitleId(canonicalTitleId).single()
            assertEquals("1", canonical.displayNumber)
            val resolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
                bindingRepository = SingleProviderReadingBindingRepository(binding),
                gateway = gateway,
                providerRegistry = registry,
            )
            val option = resolver.options(canonical.id).single()
            assertEquals(expectedWorkId, option.externalWorkId)
            assertEquals(expectedChapterId, option.providerChapterId)
            assertEquals("mangafire", option.contentSourceId)

            val callsBeforeForgery = httpCalls.get()
            val crossSource = resolver.preparedContent(
                option.copy(providerChapterId = "mangadex:foreign-chapter"),
            )
            assertTrue(crossSource is ProviderCallResult.Failure)
            assertEquals(callsBeforeForgery, httpCalls.get())
            val prepared = resolver.preparedContent(option) as ProviderCallResult.Success
            val delivery = prepared.value as PreparedChapterContent.HttpPages
            assertEquals(imageUrl, delivery.pages.single().url)
            assertEquals("https://mangafire.to", delivery.pages.single().headers["Referer"])
            assertTrue("https://mfcdn.cc" in delivery.pages.single().allowedOrigins)

            // A 403 cannot be promoted to a complete empty chapter inventory.
            blockedChapters.set(true)
            val blocked = gateway.chapters(
                providerId,
                ProviderReadingChaptersRequest(binding.ref),
            )
            assertTrue(blocked is ProviderCallResult.Failure)
            blockedChapters.set(false)
            blockedPages.set(true)
            val unavailablePages = gateway.pages(
                providerId,
                ProviderReadingPagesRequest(binding.ref, expectedChapterId),
            )
            assertTrue(unavailablePages is ProviderCallResult.Failure)
            blockedPages.set(false)
            blockedLookup.set(true)
            val incomplete = gateway.lookup(
                providerId,
                ProviderReadingLookupRequest(titles = listOf(title), requestedLanguage = "en"),
            ) as ProviderCallResult.Success
            assertTrue(incomplete.value.items.isEmpty())
            assertNotNull(incomplete.value.nextCursor)
            assertEquals(1, chapterRepository.getByCanonicalTitleId(canonicalTitleId).size)
            assertTrue(httpCalls.get() > 0)
        }
        assertEquals(setOf("punpun", "koe"), observed)
    }

    @Test
    fun mangaAllCode7EmittedVectors_reconcileHostAndPreserveSourceIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val replay = instrumentation.context.assets.open("mangaall/code7-replay.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(1, replay.getInt("schemaVersion"))
        assertEquals(
            "real_code7_js_with_synthetic_http_fixtures_not_live_source",
            replay.getString("provenance"),
        )
        assertEquals("app.tsuzuki.mangaall", replay.getString("providerId"))
        assertEquals(7, replay.getInt("providerVersionCode"))
        val replayCases = replay.getJSONArray("cases")
        assertEquals(2, replayCases.length())
        val expected = mapOf("punpun" to "mangafire:zk12", "koe" to "mangafire:7p56")
        val observed = mutableSetOf<String>()
        val knownEmpty = replay.getJSONObject("preferredEmpty")
        assertEquals("mangadex", knownEmpty.getString("source"))
        assertEquals(0, knownEmpty.getInt("itemCount"))
        assertTrue(knownEmpty.getBoolean("complete"))
        assertEquals("CHALLENGE_BLOCKED", replay.getJSONObject("challenge").getString("outcome"))
        assertTrue(!replay.getJSONObject("challenge").getBoolean("complete"))

        repeat(replayCases.length()) { index ->
            val vector = replayCases.getJSONObject(index)
            val caseId = vector.getString("caseId")
            observed += caseId
            val mangaAll = ProviderId(replay.getString("providerId"))
            val workId = vector.getString("sourceWorkId")
            val chapterId = vector.getString("sourceChapterId")
            assertEquals(expected[caseId], workId)
            assertTrue(chapterId.startsWith("mangafire:"))
            val vectorJson = vector.toString()
            val script = """
                const vector = $vectorJson;
                export default {
                  reading: {
                    lookup: async (request) => {
                      if (!request.titles.includes(vector.requestedTitle)) {
                        throw Error("Unexpected replay title");
                      }
                      return request.coverageEvidenceVersion === 1 &&
                        request.requestedLanguage === "en"
                          ? vector.richLookup : vector.lookup;
                    },
                    chapters: async (request) => {
                      if (request.binding.externalWorkId ===
                          "mangadex:11111111-2222-4333-8444-555555555555") {
                        return {items:[],nextCursor:null};
                      }
                      if (request.binding.externalWorkId !== vector.sourceWorkId ||
                          request.binding.facetId !== "en") {
                        throw Error("Replay chapter work mismatch");
                      }
                      return vector.chapters;
                    },
                    pages: async (request) => {
                      if (request.binding.externalWorkId !== vector.sourceWorkId ||
                          request.providerChapterId !== vector.sourceChapterId ||
                          request.binding.facetId !== "en") {
                        throw Error("Replay page work/chapter mismatch");
                      }
                      return vector.pages;
                    }
                  }
                };
            """.trimIndent()
            lateinit var registry: DefaultProviderRegistry
            // These are REAL declared code7 origins, but no Host HTTP requests
            // are issued to them. The test SCRIPT returns the pinned replay.
            val gateway = gateway(
                main = script,
                providerId = mangaAll,
                networkOrigins = setOf("https://mangafire.to", "https://mfcdn.cc"),
                localNetwork = false,
                onRegistry = { registry = it },
            )
            val observedLookup = gateway.lookup(
                mangaAll,
                ProviderReadingLookupRequest(listOf(vector.getString("requestedTitle"))),
            ) as ProviderCallResult.Success
            val candidate = observedLookup.value.items.single()
            assertEquals(workId, candidate.externalWorkId)
            assertEquals("en", candidate.language)
            val coverage = gateway.lookup(
                mangaAll,
                ProviderReadingLookupRequest(
                    titles = listOf(vector.getString("requestedTitle")),
                    coverageEvidenceVersion = 1,
                    requestedLanguage = "en",
                ),
            ) as ProviderCallResult.Success
            val corroborated = coverage.value.items.single()
            assertEquals(workId, corroborated.externalWorkId)
            assertEquals(ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES, corroborated.detailOutcome)
            assertEquals(ProviderCoverageOutcome.OBSERVED, corroborated.coverageOutcome)
            assertEquals(1, corroborated.coverageObservedCount)
            assertEquals(1000, corroborated.coverageMinMilli)
            assertEquals(1000, corroborated.coverageMaxMilli)
            assertEquals(false, corroborated.coverageHasMore)
            assertEquals(
                vector.getJSONObject("richLookup").getJSONArray("items")
                    .getJSONObject(0).getJSONArray("authors").getString(0),
                corroborated.authors.single(),
            )
            // Rich first-page coverage is provisional evidence ONLY; do not
            // treat it as edition verification, canonical chapter total,
            // persisted binding, or immediate Reader authorization.
            val canonicalTitleId = "code7-replay-$caseId"
            val chapterRepository = InMemoryCanonicalChapterRepository()
            val evidenceRepository = InMemoryChapterEvidenceRepository()
            val refresh = RefreshProviderReadingChapters(
                gateway = gateway,
                evidenceAdapter = ProviderChapterEvidenceAdapter(clock = { 10L }),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapterRepository,
                    evidenceRepository = evidenceRepository,
                ),
            )

            // Punpun's saved preferred MangaDex/en returns zero COMPLETE
            // chapters. That fact must not be mistaken for an exact MangaFire
            // edition, nor does it authorize an automatic rebind in this test.
            if (caseId == "punpun") {
                val previousBinding = ProviderReadingBinding(
                    id = "preferred-dex-code7",
                    canonicalTitleId = canonicalTitleId,
                    ref = ProviderBindingRef(
                        mangaAll,
                        "en",
                        "mangadex:11111111-2222-4333-8444-555555555555",
                    ),
                    verification = ProviderBindingVerification.USER_CONFIRMED,
                    availability = ProviderBindingAvailability.AVAILABLE,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
                val preferredRefresh = refresh.execute(previousBinding) as ProviderCallResult.Success
                assertEquals(0, preferredRefresh.value)
                assertTrue(chapterRepository.getByCanonicalTitleId(canonicalTitleId).isEmpty())
                assertEquals(
                    "mangadex:11111111-2222-4333-8444-555555555555",
                    previousBinding.ref.externalWorkId,
                )
            }

            // A separately user-confirmed MangaFire binding demonstrates
            // decodability, chapter reconciliation and scoped Reader pages.
            // Host #196 fallback on an UNCHANGED preferred binding is a
            // distinct Domain regression; this test doesn't fake that proof.
            val binding = ProviderReadingBinding(
                id = "code7-reading-$caseId",
                canonicalTitleId = canonicalTitleId,
                ref = ProviderBindingRef(mangaAll, "en", workId),
                verification = ProviderBindingVerification.USER_CONFIRMED,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val refreshed = refresh.execute(binding) as ProviderCallResult.Success
            assertEquals(1, refreshed.value)
            val canonical = chapterRepository.getByCanonicalTitleId(canonicalTitleId).single()
            assertEquals("1", canonical.displayNumber)
            val resolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
                bindingRepository = SingleProviderReadingBindingRepository(binding),
                gateway = gateway,
                providerRegistry = registry,
            )
            val option = resolver.options(canonical.id).single()
            assertEquals("mangafire", option.contentSourceId)
            assertEquals(chapterId, option.providerChapterId)
            assertEquals(workId, option.externalWorkId)
            val forged = resolver.preparedContent(
                option.copy(providerChapterId = "mangadex:foreign-chapter"),
            )
            assertTrue(forged is ProviderCallResult.Failure)
            val prepared = resolver.preparedContent(option) as ProviderCallResult.Success
            val delivery = prepared.value as PreparedChapterContent.HttpPages
            assertEquals(1, delivery.pages.size)
            val expectedUrl = vector.getJSONObject("pages").getJSONArray("pages")
                .getJSONObject(0).getString("url")
            assertEquals(expectedUrl, delivery.pages.single().url)
            assertEquals(
                "https://mangafire.to",
                delivery.pages.single().headers["Referer"],
            )
            assertTrue("https://mfcdn.cc" in delivery.pages.single().allowedOrigins)
        }
        assertEquals(expected.keys, observed)
    }

    /**
     * Cross-format Host journey. These are recognizable curated work names, but
     * every new HID/chapter/image is deliberately synthetic and scoped to this
     * disposable runtime. Only Punpun and Koe HIDs were previously seen on A54.
     * Neither source discovery nor provisional chapters authorizes a binding:
     * USER_CONFIRMED below explicitly models the edition selected by a user.
     *
     * Validates the REAL Host gateway, canonical snapshot reconciliation,
     * incremental refresh, exact-scoped Reader option and chapter-zero mapping.
     * Actual public APIs and image pixels are independent acceptance gates.
     */
    @Test
    fun mangaAllDiverseJourney_reconcilesUpdatesReaderAndRejectsPartialSnapshots() = runBlocking {
        data class JourneyCase(
            val id: String,
            val title: String,
            val format: String,
            val workId: String,
            val chapterZero: Boolean = false,
            val stallOnce: Boolean = false,
        )
        val examples = listOf(
            JourneyCase("punpun", "Oyasumi Punpun", "manga", "mangafire:zk12"),
            JourneyCase("koe", "Koe no Katachi", "manga", "mangafire:7p56"),
            JourneyCase("one-piece", "One Piece", "manga", "mangafire:fixture-one-piece", chapterZero = true),
            JourneyCase("chainsaw", "Chainsaw Man", "manga", "mangafire:fixture-chainsaw", stallOnce = true),
            JourneyCase("vagabond", "Vagabond", "manga", "mangafire:fixture-vagabond"),
            JourneyCase("yotsuba", "Yotsuba to!", "manga", "mangafire:fixture-yotsuba"),
            JourneyCase("mirai-nikki", "Mirai Nikki", "manga", "mangafire:fixture-mirai-nikki"),
            JourneyCase("solo-leveling", "Solo Leveling", "manhwa", "mangafire:fixture-solo-leveling"),
            JourneyCase("the-horizon", "The Horizon", "manhwa", "mangafire:fixture-the-horizon"),
            JourneyCase("tower-of-god", "Tower of God", "webtoon", "mangafire:fixture-tower-of-god"),
            JourneyCase("eleceed", "Eleceed", "webtoon", "mangafire:fixture-eleceed"),
            JourneyCase("martial-peak", "Martial Peak", "manhua", "mangafire:fixture-martial-peak"),
            JourneyCase("tales-demons", "Tales of Demons and Gods", "manhua", "mangafire:fixture-tales-demons"),
        )
        assertEquals(13, examples.size)
        assertEquals(setOf("manga", "manhwa", "webtoon", "manhua"), examples.map { it.format }.toSet())
        val providerId = ProviderId("app.tsuzuki.mangaall")
        for (example in examples) {
            // Each stage runs against the same real Host decoder and persistence
            // repositories, but no original MangaFire address is contacted.
            val canonicalTitleId = "matrix-" + example.id
            val chapterRepository = InMemoryCanonicalChapterRepository()
            val evidenceRepository = InMemoryChapterEvidenceRepository()
            val reconcile = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
            )
            val binding = ProviderReadingBinding(
                id = "user-confirmed-" + example.id,
                canonicalTitleId = canonicalTitleId,
                ref = ProviderBindingRef(providerId, "en", example.workId),
                verification = ProviderBindingVerification.USER_CONFIRMED,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            )
            fun script(updated: Boolean, stalled: Boolean = false): String {
                val includeFour = if (updated) "true" else "false"
                val includeZero = if (example.chapterZero) "true" else "false"
                val rejectSecond = if (stalled) "true" else "false"
                val workId = example.workId
                val title = example.title
                val scenarioId = example.id
                return """
                    const work = "$workId";
                    const updated = $includeFour;
                    const includeZero = $includeZero;
                    const stalled = $rejectSecond;
                    const chapter = (n) => ({
                      providerChapterId: work + "-c" + n,
                      rawLabel: "Chapter " + n,
                      rawNumber: n,
                      language: "en"
                    });
                    export default {
                      reading: {
                        lookup: async (request) => ({
                          items: request.titles.includes("$title")
                            ? [{externalWorkId: work, title: "$title", aliases: [], language: "en"}]
                            : [],
                          nextCursor: null
                        }),
                        chapters: async (request) => {
                          if (request.binding.externalWorkId !== work ||
                              request.binding.facetId !== "en") throw Error("Wrong edition");
                          if (!request.cursor) return {
                            items: includeZero ? [chapter(0), chapter(1), chapter(2)]
                              : [chapter(1), chapter(2)],
                            nextCursor: "second"
                          };
                          if (request.cursor !== "second") throw Error("Unexpected cursor");
                          if (stalled) throw Error("Synthetic second page unavailable");
                          return { items: updated ? [chapter(3), chapter(4)] : [chapter(3)],
                            nextCursor: null };
                        },
                        pages: async (request) => {
                          if (request.binding.externalWorkId !== work ||
                              request.binding.facetId !== "en" ||
                              !request.providerChapterId.startsWith(work + "-c")) {
                            throw Error("Reader source or chapter mismatch");
                          }
                          const number = request.providerChapterId.slice((work + "-c").length);
                          if (!/^[0-9]+$/.test(number)) throw Error("Invalid chapter");
                          return { type: "page_list", pages: [{
                            url: "https://mfcdn.cc/fixture/$scenarioId/" + number + ".png",
                            headers: { Referer: "https://mangafire.to" }
                          }] };
                        }
                      }
                    };
                """.trimIndent()
            }
            fun gatewayFor(
                updated: Boolean,
                stalled: Boolean = false,
                onRegistered: (DefaultProviderRegistry) -> Unit,
            ) =
                gateway(
                    main = script(updated, stalled),
                    providerId = providerId,
                    networkOrigins = setOf("https://mfcdn.cc", "https://mangafire.to"),
                    localNetwork = false,
                    onRegistry = onRegistered,
                )

            lateinit var firstRegistry: DefaultProviderRegistry
            val originalGateway = gatewayFor(false) { firstRegistry = it }
            val lookup = originalGateway.lookup(
                providerId,
                ProviderReadingLookupRequest(titles = listOf(example.title), requestedLanguage = "en"),
            ) as ProviderCallResult.Success
            assertEquals(example.workId, lookup.value.items.single().externalWorkId)
            val refresh = RefreshProviderReadingChapters(
                gateway = originalGateway,
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                reconcileChapterEvidence = reconcile,
            )
            val initialCount = if (example.chapterZero) 4 else 3
            assertEquals(ProviderCallResult.Success(initialCount), refresh.execute(binding))
            val initialChapters = chapterRepository.getByCanonicalTitleId(canonicalTitleId)
            assertEquals(initialCount, initialChapters.size)
            val chapterOne = initialChapters.single { it.displayNumber == "1" }
            val firstId = chapterOne.id

            val originalResolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
                bindingRepository = SingleProviderReadingBindingRepository(binding),
                gateway = originalGateway,
                providerRegistry = firstRegistry,
            )
            val originalOption = originalResolver.options(firstId).single()
            assertEquals(example.workId, originalOption.externalWorkId)
            assertEquals(example.workId + "-c1", originalOption.providerChapterId)
            assertEquals("mangafire", originalOption.contentSourceId)
            val attemptedForgery = originalResolver.preparedContent(
                originalOption.copy(providerChapterId = "mangadex:foreign"),
            )
            assertTrue(attemptedForgery is ProviderCallResult.Failure)
            val content = originalResolver.preparedContent(originalOption) as ProviderCallResult.Success
            val pages = content.value as PreparedChapterContent.HttpPages
            assertEquals("https://mfcdn.cc/fixture/" + example.id + "/1.png", pages.pages.single().url)

            if (example.chapterZero) {
                val zero = initialChapters.single { it.displayNumber == "0" }
                val option = originalResolver.options(zero.id).single()
                assertEquals(example.workId + "-c0", option.providerChapterId)
                val prepared = originalResolver.preparedContent(option) as ProviderCallResult.Success
                assertEquals(
                    "https://mfcdn.cc/fixture/" + example.id + "/0.png",
                    (prepared.value as PreparedChapterContent.HttpPages).pages.single().url,
                )
            }

            if (example.stallOnce) {
                lateinit var blockedRegistry: DefaultProviderRegistry
                val blockedGateway = gatewayFor(false, stalled = true) { blockedRegistry = it }
                val blockedRefresh = RefreshProviderReadingChapters(
                    gateway = blockedGateway,
                    evidenceAdapter = ProviderChapterEvidenceAdapter(),
                    reconcileChapterEvidence = reconcile,
                )
                assertTrue(blockedRefresh.execute(binding) is ProviderCallResult.Failure)
                // A later page failure is not a completed empty source inventory
                // and MUST NOT detach the previous canonical snapshot.
                assertEquals(initialCount, chapterRepository.getByCanonicalTitleId(canonicalTitleId).size)
            }
            // Snapshot precedence uses observed time; advance the clock between
            // two real Host refresh transactions without injecting fake timestamps.
            kotlinx.coroutines.delay(20)
            lateinit var updatedRegistry: DefaultProviderRegistry
            val updatedGateway = gatewayFor(true) { updatedRegistry = it }
            val updatedRefresh = RefreshProviderReadingChapters(
                gateway = updatedGateway,
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                reconcileChapterEvidence = reconcile,
            )
            assertEquals(ProviderCallResult.Success(initialCount + 1), updatedRefresh.execute(binding))
            val laterChapters = chapterRepository.getByCanonicalTitleId(canonicalTitleId)
            assertEquals(initialCount + 1, laterChapters.size)
            assertEquals(firstId, laterChapters.single { it.displayNumber == "1" }.id)
            val updatedResolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
                bindingRepository = SingleProviderReadingBindingRepository(binding),
                gateway = updatedGateway,
                providerRegistry = updatedRegistry,
            )
            assertEquals(example.workId + "-c1", updatedResolver.options(firstId).single().providerChapterId)
            val newLast = laterChapters.single { it.displayNumber == "4" }
            val newOption = updatedResolver.options(newLast.id).single()
            assertEquals(example.workId + "-c4", newOption.providerChapterId)
            val newContent = updatedResolver.preparedContent(newOption) as ProviderCallResult.Success
            assertEquals(
                "https://mfcdn.cc/fixture/" + example.id + "/4.png",
                (newContent.value as PreparedChapterContent.HttpPages).pages.single().url,
            )
        }
    }

    @Test
    fun htmlDomProvider_parsesHostOwnedDocumentInsideIsolatedInvocation() = runBlocking {
        val server = MockWebServer()
        server.start()
        val origin = server.origin()
        server.enqueue(
            MockResponse.Builder()
                .body("""<html><body><h1 id="title">DOM Title</h1></body></html>""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("dom-reader-image")
                .build(),
        )

        val gateway = gateway(
            networkOrigins = setOf(origin),
            localNetwork = true,
            main = """
                const base = "$origin";
                export default {
                  reading: {
                    lookup: async () => {
                      const document = await tsuzuki.http.getResource(base + "/work");
                      const title = await tsuzuki.dom.selectText(document, "#title");
                      return {
                        items: [{
                          externalWorkId: "dom-work",
                          title,
                          aliases: [],
                          url: base + "/work",
                          language: "en"
                        }],
                        nextCursor: null
                      };
                    },
                    chapters: async () => ({items: [], nextCursor: null}),
                    pages: async () => ({
                      type: "page_list",
                      pages: [{url: base + "/page.jpg", headers: {}}]
                    })
                  }
                };
            """.trimIndent(),
        )

        try {
            val result = gateway.lookup(
                PROVIDER_ID,
                ProviderReadingLookupRequest(listOf("DOM Title")),
            ) as ProviderCallResult.Success

            assertEquals("DOM Title", result.value.items.single().title)

            val pages = gateway.pages(
                PROVIDER_ID,
                ProviderReadingPagesRequest(
                    binding = ProviderBindingRef(PROVIDER_ID, "en", "dom-work"),
                    providerChapterId = "dom-chapter-1",
                ),
            ) as ProviderCallResult.Success
            assertEquals(
                "dom-reader-image",
                readFirstProviderPage(
                    pageList = pages.value as ProviderReadingDelivery.PageList,
                    canonicalChapterId = "canonical-dom-chapter-1",
                ),
            )
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun browserProvider_readsRenderedDomWithoutAndroidAuthorityInScript() = runBlocking {
        val server = MockWebServer()
        server.start()
        val origin = server.origin()
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    <!doctype html>
                    <html>
                      <body>
                        <h1 id="title">initial</h1>
                        <script>
                          document.getElementById('title').textContent = 'Browser Title';
                        </script>
                      </body>
                    </html>
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("browser-reader-image")
                .build(),
        )

        val gateway = gateway(
            networkOrigins = setOf(origin),
            browserOrigins = setOf(origin),
            localNetwork = true,
            main = """
                const base = "$origin";
                export default {
                  reading: {
                    lookup: async () => {
                      const title = await tsuzuki.browser.readText(base + "/browser", "#title");
                      return {
                        items: [{
                          externalWorkId: "browser-work",
                          title,
                          aliases: [],
                          url: base + "/browser",
                          language: "en"
                        }],
                        nextCursor: null
                      };
                    },
                    chapters: async () => ({items: [], nextCursor: null}),
                    pages: async () => ({
                      type: "page_list",
                      pages: [{url: base + "/page.jpg", headers: {}}]
                    })
                  }
                };
            """.trimIndent(),
        )

        try {
            val result = gateway.lookup(
                PROVIDER_ID,
                ProviderReadingLookupRequest(listOf("Browser Title")),
            ) as ProviderCallResult.Success

            assertEquals("Browser Title", result.value.items.single().title)

            val pages = gateway.pages(
                PROVIDER_ID,
                ProviderReadingPagesRequest(
                    binding = ProviderBindingRef(PROVIDER_ID, "en", "browser-work"),
                    providerChapterId = "browser-chapter-1",
                ),
            ) as ProviderCallResult.Success
            assertEquals(
                "browser-reader-image",
                readFirstProviderPage(
                    pageList = pages.value as ProviderReadingDelivery.PageList,
                    canonicalChapterId = "canonical-browser-chapter-1",
                ),
            )
            // WebView can request auxiliary resources such as favicons during
            // document rendering; only the required document and Reader page
            // requests are contractual, not the total HTTP request count.
            val requestedPaths = List(server.requestCount) { server.takeRequest().url.encodedPath }
            assertTrue("Browser document was not requested: $requestedPaths", "/browser" in requestedPaths)
            assertEquals(1, requestedPaths.count { it == "/page.jpg" })
        } finally {
            server.close()
        }
    }

    @Test
    fun complexArchiveCryptoImageProvider_reachesCanonicalReaderWithHostOwnedBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val managedRoot = File(context.cacheDir, "provider-managed-files").apply {
            deleteRecursively()
        }
        val managedFiles = ProviderManagedFileStore(context)
        val key = ByteArray(16) { index -> (index + 1).toByte() }
        val iv = ByteArray(16) { index -> (0x10 + index).toByte() }
        val fixture = complexReadingBundle(key, iv)

        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(fixture))
                .build(),
        )
        server.start()
        val origin = server.origin()
        val gateway = gateway(
            networkOrigins = setOf(origin),
            localNetwork = true,
            managedFiles = managedFiles,
            main = """
                const base = "$origin";
                export default {
                  reading: {
                    lookup: async () => ({items: [], nextCursor: null}),
                    chapters: async () => ({items: [], nextCursor: null}),
                    pages: async () => {
                      const archive = await tsuzuki.binary.fetch(base + "/complex.zip");
                      const encrypted = await tsuzuki.binary.zipEntry(archive, "probe.enc");
                      const decrypted = await tsuzuki.crypto.aesCbcDecrypt(
                        encrypted,
                        "${key.toHex()}",
                        "${iv.toHex()}"
                      );
                      const cropped = await tsuzuki.image.crop(decrypted, 0, 0, 1, 1);
                      const pixel = await tsuzuki.image.pixel(cropped, 0, 0);
                      if (pixel !== "FFFF0000") {
                        throw new Error("unexpected transformed pixel");
                      }
                      const chapter = await tsuzuki.binary.zipEntry(archive, "chapter.cbz");
                      const managed = await tsuzuki.binary.promote(chapter, "CBZ");
                      return {
                        type: "managed_file",
                        resource: managed,
                        format: "CBZ"
                      };
                    }
                  }
                };
            """.trimIndent(),
        )

        try {
            val response = gateway.pages(
                PROVIDER_ID,
                ProviderReadingPagesRequest(
                    binding = ProviderBindingRef(PROVIDER_ID, "en", "complex-work"),
                    providerChapterId = "complex-chapter-1",
                ),
            ) as ProviderCallResult.Success
            val managed = response.value as ProviderReadingDelivery.ManagedFile
            val uri = managedFiles.resolve(
                providerId = PROVIDER_ID,
                resource = managed.resource,
                format = managed.format,
            )
            assertNotNull(uri)

            val plan = CanonicalReaderTargetPlan.Local(
                canonicalChapterId = "canonical-complex-chapter-1",
                uri = requireNotNull(uri),
                format = CanonicalLocalReaderFormat.ARCHIVE,
            )
            val loader = LocalChapterLoader.from(context, plan)
            val readerChapter = ReaderChapter(
                ChapterImpl().apply {
                    id = Long.MIN_VALUE
                    url = "provider-managed:canonical-complex-chapter-1"
                    name = "Complex Provider chapter"
                },
            )
            try {
                loader.loadChapter(readerChapter)
                val page = readerChapter.pages!!.single()
                val pageBytes = page.stream!!.invoke().use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size)
                assertNotNull(bitmap)
                requireNotNull(bitmap).useBitmap {
                    assertEquals(Color.RED, it.getPixel(0, 0))
                }
            } finally {
                readerChapter.pageLoader?.recycle()
            }

            assertEquals(1, server.requestCount)
            assertEquals("/complex.zip", server.takeRequest().url.encodedPath)
        } finally {
            server.close()
            managedRoot.deleteRecursively()
        }
    }

    @Test
    fun metadataOnlyProvider_contributesChapterMetadataWithoutReaderPages() = runBlocking {
        lateinit var providerRegistry: DefaultProviderRegistry
        val gateway = gateway(
            networkOrigins = emptySet(),
            localNetwork = false,
            includePages = false,
            onRegistry = { providerRegistry = it },
            main = """
                export default {
                  reading: {
                    lookup: async () => ({items: [], nextCursor: null}),
                    chapters: async () => ({
                      items: [{
                        providerChapterId: "metadata-only-4",
                        rawLabel: "Chapter 4",
                        rawNumber: 4.0,
                        language: "pt-BR",
                        scanlationGroup: "Grupo Ñ",
                        releaseDateMillis: 1700000000000
                      }],
                      nextCursor: null
                    })
                  }
                };
            """.trimIndent(),
        )
        val binding = ProviderReadingBinding(
            id = "metadata-only-binding",
            canonicalTitleId = "metadata-only-title",
            ref = ProviderBindingRef(PROVIDER_ID, "pt-BR", "metadata-only-work"),
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val chapterRepository = InMemoryCanonicalChapterRepository()
        val evidenceRepository = InMemoryChapterEvidenceRepository()
        val bindingRepository = SingleProviderReadingBindingRepository(binding)
        val refresh = RefreshProviderReadingChapters(
            gateway = gateway,
            evidenceAdapter = ProviderChapterEvidenceAdapter(clock = { 42L }),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
            ),
        )

        assertEquals(1, (refresh.execute(binding) as ProviderCallResult.Success).value)
        val chapters = chapterRepository.getByCanonicalTitleId("metadata-only-title")
        assertEquals(1, chapters.size)
        val evidence = evidenceRepository.getByCanonicalTitleId("metadata-only-title").single().evidence
        assertEquals("pt-BR", evidence.providerMetadata?.language)
        assertEquals("Grupo Ñ", evidence.providerMetadata?.scanlationGroup)
        assertEquals(1700000000000L, evidence.providerMetadata?.releaseDateMillis)

        val resolver = ResolveProviderChapterReading(
            canonicalChapterRepository = chapterRepository,
            evidenceRepository = evidenceRepository,
            bindingRepository = bindingRepository,
            gateway = gateway,
            providerRegistry = providerRegistry,
        )
        assertEquals(emptyList<Any>(), resolver.options(chapters.single().id))
    }

    /** Offline smoke: prove the am-instrument stream contains the closed verdict. */
    @Test
    fun mangaAllLiveVerdictProtocol_emitsAndroidStatusStream() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("mangaAllStatusProbeOptIn"))
        MangaAllInstrumentationVerdict.publish("MANGAALL_STATUS_PROBE|outcome=VISIBLE")
        // Contract-only markers; never evidence of real source coverage.
        // Check the actual adb/am-instrument status channel before live traffic.
        MangaAllInstrumentationVerdict.publish(
            "MANGAALL_BATCH_SOURCE|source=mangadex|outcome=READY|candidates=1" +
                "|workId=mangadex:11111111-2222-4333-8444-555555555555" +
                "|chapterId=mangadex:aaaa1111-2222-4333-8444-555555555555|language=en",
        )
        MangaAllInstrumentationVerdict.publish(
            "MANGAALL_BATCH_SOURCE|source=mangadot|outcome=AMBIGUOUS|candidates=2",
        )
        MangaAllInstrumentationVerdict.publish(
            "MANGAALL_BATCH_SOURCE|source=mangafire|outcome=MISSING|candidates=0",
        )
        MangaAllInstrumentationVerdict.publish(
            "MANGAALL_BATCH_AUDIT|outcome=COMPLETE|sources=3" +
                "|titleParity=VARIANCE|authorParity=UNVERIFIED",
        )
        MangaAllInstrumentationVerdict.publish(
            "MANGAALL_BATCH_AUDIT|outcome=INCONCLUSIVE|reason=LOOKUP_UNAVAILABLE" +
                "|code=SOURCE_READ_ERROR|sourceFailure=CHALLENGE_BLOCKED" +
                "|httpStatus=403|retryable=true",
        )
    }

    /**
     * Live MangaAll integration audit. One request enters the SINGLE original
     * signed code8 Host router, which fans out to all three source adapters.
     *
     * A source-specific ambiguity MUST NOT prevent inspection of another
     * source. Do not convert matching titles into editorial edition grants:
     * even three successful image paths are not author/edition confirmation.
     */
    @Test(timeout = 540_000L)
    fun mangaAllSignedCode8LiveBatchAuditAllSources() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("mangaAllLiveBatchOptIn"))
        assertEquals("app.tsuzuki.dev", instrumentation.targetContext.packageName)
        val title = android.util.Base64.decode(
            requireNotNull(args.getString("mangaAllBatchTitleBase64")),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
        ).toString(Charsets.UTF_8).trim()
        val language = requireNotNull(args.getString("facetLanguage"))
        require(title.length in 2..120 && title.none { it.isISOControl() || it == '|' })
        // English is the only facet shared by ALL THREE published adapters.
        require(language == "en")

        val packageBytes = instrumentation.context.assets
            .open("mangaall/code8-published-package.tsz").use { it.readBytes() }
        val parsed = ProviderPackageParser().parse(packageBytes)
        assertEquals("app.tsuzuki.mangaall", parsed.manifest.id)
        assertEquals("0.5.3", parsed.manifest.version.name)
        assertEquals(8L, parsed.manifest.version.code)
        val providerId = ProviderId(parsed.manifest.id)
        val gateway = gateway(
            main = "",
            providerId = providerId,
            networkOrigins = requireNotNull(parsed.manifest.permissions.network).origins,
            browserOrigins = parsed.manifest.permissions.browser?.origins.orEmpty(),
            localNetwork = false,
            packageBytesOverride = packageBytes,
            facetLanguage = language,
            baseHttpClient = OkHttpClient.Builder()
                .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build(),
        )
        val sources = listOf("mangadex", "mangadot", "mangafire")

        fun inconclusive(reason: String) {
            MangaAllInstrumentationVerdict.publish(
                "MANGAALL_BATCH_AUDIT|outcome=INCONCLUSIVE|reason=$reason",
            )
        }

        fun normalize(value: String): String = java.text.Normalizer
            .normalize(value.trim(), java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\s+"), " ")

        fun sourceVerdict(
            source: String,
            outcome: String,
            candidates: Int,
            workId: String? = null,
            chapterId: String? = null,
        ) {
            val ready = if (workId != null && chapterId != null) {
                "|workId=$workId|chapterId=$chapterId|language=$language"
            } else {
                ""
            }
            MangaAllInstrumentationVerdict.publish(
                "MANGAALL_BATCH_SOURCE|source=$source|outcome=$outcome|candidates=$candidates$ready",
            )
        }

        try {
            withTimeout(500_000L) {
                val observed = sources.associateWith {
                    linkedMapOf<String, tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkCandidate>()
                }
                var lookupCursor: tachiyomi.domain.tsuzuki.provider.ProviderCursor? = null
                val seenLookupCursors = hashSetOf<String>()
                var lookupCompleted = false
                // Never interpret a partial provider result as a complete catalog.
                for (index in 0 until 8) {
                    val response = withTimeout(50_000L) {
                        gateway.lookup(
                            providerId,
                            ProviderReadingLookupRequest(
                                titles = listOf(title),
                                cursor = lookupCursor,
                                requestedLanguage = language,
                            ),
                        )
                    }
                    if (response !is ProviderCallResult.Success) {
                        // Structured Host error only. Never emit response bodies,
                        // upstream URLs, headers, provider IDs or raw exceptions.
                        val error = (response as ProviderCallResult.Failure).error
                        MangaAllInstrumentationVerdict.publish(
                            "MANGAALL_BATCH_AUDIT|outcome=INCONCLUSIVE|reason=LOOKUP_UNAVAILABLE" +
                                "|code=${error.code.name}" +
                                "|sourceFailure=${error.sourceFailureCode?.name ?: "NONE"}" +
                                "|httpStatus=${error.httpStatus?.toString() ?: "NONE"}" +
                                "|retryable=${error.retryable}",
                        )
                        return@withTimeout
                    }
                    for (candidate in response.value.items) {
                        val source = sources.firstOrNull {
                            candidate.externalWorkId.startsWith("$it:")
                        }
                        if (source == null) {
                            inconclusive("INVALID_SOURCE_IDENTITY")
                            return@withTimeout
                        }
                        if (candidate.language != null && candidate.language != language) {
                            inconclusive("SOURCE_LANGUAGE_CONFLICT")
                            return@withTimeout
                        }
                        val known = requireNotNull(observed[source])
                        val prior = known[candidate.externalWorkId]
                        if (prior != null && normalize(prior.title) != normalize(candidate.title)) {
                            inconclusive("SOURCE_IDENTITY_CONFLICT")
                            return@withTimeout
                        }
                        known[candidate.externalWorkId] = candidate
                    }
                    val next = response.value.nextCursor
                    if (next == null) {
                        lookupCompleted = true
                        break
                    }
                    if (!seenLookupCursors.add(next.toString())) {
                        inconclusive("LOOKUP_CURSOR_LIMIT")
                        return@withTimeout
                    }
                    lookupCursor = next
                }
                if (!lookupCompleted) {
                    inconclusive("LOOKUP_CURSOR_LIMIT")
                    return@withTimeout
                }

                // Metadata signals are diagnostics, NOT editorial proof that
                // every source HID represents the same edition.
                val uniqueWorks = sources.mapNotNull { observed[it]?.values?.singleOrNull() }
                val titleParity = when {
                    uniqueWorks.size < 2 -> "UNVERIFIED"
                    uniqueWorks.map { normalize(it.title) }.distinct().size == 1 -> "CONSISTENT"
                    else -> "VARIANCE"
                }
                val authors = uniqueWorks.map { candidate ->
                    candidate.authors.map(::normalize).filter(String::isNotBlank).toSet()
                }.filter { it.isNotEmpty() }
                val authorParity = when {
                    authors.size < 2 -> "UNVERIFIED"
                    authors.indices.any { left ->
                        (left + 1 until authors.size).any { right ->
                            authors[left].intersect(authors[right]).isEmpty()
                        }
                    } -> "VARIANCE"
                    else -> "CONSISTENT"
                }

                // Work on every source independently AFTER the unified lookup.
                // Ambiguous editions remain visible; they do not suppress the
                // other sources' chapter evidence or Reader pixel probes.
                for (source in sources) {
                    val matches = requireNotNull(observed[source])
                    val count = matches.size
                    if (count == 0) {
                        sourceVerdict(source, "MISSING", count)
                        continue
                    }
                    if (count > 1) {
                        sourceVerdict(source, "AMBIGUOUS", count)
                        continue
                    }
                    val workId = matches.keys.single()
                    if (!Regex("(mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}").matches(workId)) {
                        sourceVerdict(source, "INVALID_SOURCE_IDENTITY", count)
                        continue
                    }
                    val binding = ProviderBindingRef(providerId, language, workId)
                    val chapters = linkedSetOf<String>()
                    val seenChapterCursors = hashSetOf<String>()
                    var chapterCursor: tachiyomi.domain.tsuzuki.provider.ProviderCursor? = null
                    var complete = false
                    var failed = false
                    for (page in 0 until 12) {
                        val response = withTimeout(35_000L) {
                            gateway.chapters(
                                providerId,
                                ProviderReadingChaptersRequest(binding, cursor = chapterCursor),
                            )
                        }
                        if (response !is ProviderCallResult.Success) {
                            failed = true
                            break
                        }
                        response.value.items.asSequence()
                            .filter { it.rawNumber == 1.0 && it.volume == null }
                            .filter { it.language == null || it.language == language }
                            .map { it.providerChapterId }
                            .forEach { chapters.add(it) }
                        val next = response.value.nextCursor
                        if (next == null) {
                            complete = true
                            break
                        }
                        if (!seenChapterCursors.add(next.toString())) {
                            break
                        }
                        chapterCursor = next
                    }
                    val status = when {
                        failed -> "CHAPTERS_UNAVAILABLE"
                        !complete -> "CHAPTER_CURSOR_LIMIT"
                        chapters.isEmpty() -> "CHAPTER_ONE_NOT_ATTESTED"
                        chapters.size > 1 -> "AMBIGUOUS_CHAPTER_ONE"
                        !Regex("[A-Za-z0-9._:-]{1,160}").matches(chapters.single()) -> {
                            "INVALID_SOURCE_IDENTITY"
                        }
                        else -> "READY"
                    }
                    if (status == "READY") {
                        sourceVerdict(source, status, count, workId, chapters.single())
                    } else {
                        sourceVerdict(source, status, count)
                    }
                }
                MangaAllInstrumentationVerdict.publish(
                    "MANGAALL_BATCH_AUDIT|outcome=COMPLETE|sources=3" +
                        "|titleParity=$titleParity|authorParity=$authorParity",
                )
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            inconclusive("UPSTREAM_TIMEOUT")
        }
    }

    /**
     * Opt-in, direct-live Android transport gate for the ORIGINAL published code8.
     *
     * IDs are taken verbatim from a separately observed Provider result and explicitly
     * supplied by the operator. No test chooses a title, edition, author or chapter;
     * this is not a canonical ReaderActivity/pixel acceptance test.
     */
    @Test(timeout = 300_000L)
    fun mangaAllSignedCode8LivePages_downloadAndDecodeAtMostTwo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("mangaAllLivePageOptIn"))
        assertEquals("app.tsuzuki.dev", instrumentation.targetContext.packageName)
        val workId = requireNotNull(args.getString("providerWorkId"))
        val chapterId = requireNotNull(args.getString("providerChapterId"))
        val language = requireNotNull(args.getString("facetLanguage"))
        require(Regex("(mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}").matches(workId))
        require(Regex("[A-Za-z0-9._:-]{1,160}").matches(chapterId))
        require(Regex("[a-z]{2}(-[a-z]{2})?").matches(language))
        val archive = instrumentation.context.assets.open("mangaall/code8-published-package.tsz")
            .use { it.readBytes() }
        val parsed = ProviderPackageParser().parse(archive)
        assertEquals("app.tsuzuki.mangaall", parsed.manifest.id)
        assertEquals(8L, parsed.manifest.version.code)
        val providerId = ProviderId(parsed.manifest.id)
        val permission = requireNotNull(parsed.manifest.permissions.network)
        val networkClient = OkHttpClient.Builder()
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val actualGateway = gateway(
            main = "",
            providerId = providerId,
            networkOrigins = permission.origins,
            browserOrigins = parsed.manifest.permissions.browser?.origins.orEmpty(),
            localNetwork = false,
            packageBytesOverride = archive,
            facetLanguage = language,
            baseHttpClient = networkClient,
        )
        val binding = ProviderBindingRef(providerId, language, workId)
        // The caller's chapter ID is not itself evidence that it belongs to the
        // chosen source work. Require an exact, source-scoped observation on the
        // first real chapter page; never fall back to a title or another edition.
        // This bounded proof is NOT an exhaustive inventory (other cursors may exist).
        // A long-running manga may place chapter one on a later cursor.
        // Do not claim a complete negative result from the first source page.
        val matchingChapters = linkedSetOf<String>()
        val chapterCursors = hashSetOf<String>()
        var chapterCursor: tachiyomi.domain.tsuzuki.provider.ProviderCursor? = null
        var inventoryComplete = false
        for (index in 0 until 12) {
            val chapterAttestation = withTimeout(35_000L) {
                actualGateway.chapters(
                    providerId,
                    ProviderReadingChaptersRequest(binding, cursor = chapterCursor),
                )
            }
            if (chapterAttestation !is ProviderCallResult.Success) {
                MangaAllInstrumentationVerdict.publish(
                    "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=CHAPTER_ATTESTATION_UNAVAILABLE",
                )
                return@runBlocking
            }
            chapterAttestation.value.items.asSequence()
                .filter {
                    it.providerChapterId == chapterId &&
                        (it.language == null || it.language == language)
                }
                .map { it.providerChapterId }
                .forEach { matchingChapters.add(it) }
            val next = chapterAttestation.value.nextCursor
            if (next == null) {
                inventoryComplete = true
                break
            }
            if (!chapterCursors.add(next.toString())) {
                break
            }
            chapterCursor = next
        }
        if (!inventoryComplete) {
            MangaAllInstrumentationVerdict.publish(
                "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=CHAPTER_CURSOR_LIMIT",
            )
            return@runBlocking
        }
        if (matchingChapters.size != 1) {
            MangaAllInstrumentationVerdict.publish(
                "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=CHAPTER_NOT_ATTESTED_IN_BOUND",
            )
            return@runBlocking
        }
        val response = withTimeout(60_000L) {
            actualGateway.pages(
                providerId,
                ProviderReadingPagesRequest(
                    binding = binding,
                    providerChapterId = chapterId,
                ),
            )
        }
        if (response !is ProviderCallResult.Success) {
            MangaAllInstrumentationVerdict.publish(
                "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=PROVIDER_PAGES_UNAVAILABLE",
            )
            return@runBlocking
        }
        val delivery = response.value as? ProviderReadingDelivery.PageList
        if (delivery == null || delivery.pages.isEmpty()) {
            MangaAllInstrumentationVerdict.publish(
                "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=NO_HTTP_PAGES",
            )
            return@runBlocking
        }
        val selected = delivery.pages.take(2).map { page ->
            PreparedHttpPage(
                url = page.url,
                headers = page.headers,
                allowedOrigins = page.allowedOrigins,
                allowLocalNetwork = page.allowLocalNetwork,
            )
        }
        val plan = CanonicalReaderTargetPlan.HttpPages(
            canonicalChapterId = "disposable-mangaall-live-probe",
            pages = selected,
        )
        val readerLoader = ProviderHttpChapterLoader.from(
            instrumentation.targetContext,
            plan,
            client = networkClient,
        )
        val readerChapter = ReaderChapter(
            ChapterImpl().apply {
                id = Long.MIN_VALUE
                url = "provider-pages:disposable-mangaall-live-probe"
                name = "Live page transport probe"
            },
        )
        var decoded = 0
        try {
            readerLoader.loadChapter(readerChapter)
            for (page in requireNotNull(readerChapter.pages)) {
                withTimeout(35_000L) {
                    requireNotNull(readerChapter.pageLoader).loadPage(page)
                }
                if (page.status != Page.State.Ready || page.stream == null) {
                    MangaAllInstrumentationVerdict.publish(
                        "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=CDN_FETCH_UNAVAILABLE",
                    )
                    return@runBlocking
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                requireNotNull(page.stream).invoke().use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000) {
                    MangaAllInstrumentationVerdict.publish(
                        "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=INVALID_IMAGE_DIMENSIONS",
                    )
                    return@runBlocking
                }
                val sample = generateSequence(1) { it * 2 }
                    .first { maxOf(bounds.outWidth / it, bounds.outHeight / it) <= 4096 }
                val bitmap = requireNotNull(page.stream).invoke().use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
                if (bitmap == null) {
                    MangaAllInstrumentationVerdict.publish(
                        "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=IMAGE_DECODE_FAILED",
                    )
                    return@runBlocking
                }
                bitmap.recycle()
                decoded++
            }
            assertTrue("At least one original CDN image must decode", decoded > 0)
            MangaAllInstrumentationVerdict.publish(
                "MANGAALL_LIVE_PAGE_GATE|outcome=PASS|provenance=DIRECT_LIVE_ANDROID" +
                    "|pagesDecoded=$decoded|readerActivityPixels=NOT_TESTED",
            )
        } finally {
            readerChapter.pageLoader?.recycle()
        }
    }

    private suspend fun readFirstProviderPage(
        pageList: ProviderReadingDelivery.PageList,
        canonicalChapterId: String,
    ): String {
        val prepared = PreparedChapterContent.HttpPages(
            pageList.pages.map { page ->
                PreparedHttpPage(
                    url = page.url,
                    headers = page.headers,
                    allowedOrigins = page.allowedOrigins,
                    allowLocalNetwork = page.allowLocalNetwork,
                )
            },
        )
        val plan = CanonicalReaderTargetPlan.HttpPages(
            canonicalChapterId = canonicalChapterId,
            pages = prepared.pages,
        )
        val readerLoader = ProviderHttpChapterLoader.from(
            InstrumentationRegistry.getInstrumentation().targetContext,
            plan,
        )
        val readerChapter = ReaderChapter(
            ChapterImpl().apply {
                id = Long.MIN_VALUE
                url = "provider-pages:$canonicalChapterId"
                name = "Provider chapter"
            },
        )

        return try {
            readerLoader.loadChapter(readerChapter)
            val readerPage = readerChapter.pages!!.single()
            readerChapter.pageLoader!!.loadPage(readerPage)
            assertEquals(Page.State.Ready, readerPage.status)
            readerPage.stream!!.invoke().use { it.readBytes().decodeToString() }
        } finally {
            readerChapter.pageLoader?.recycle()
        }
    }

    private class SingleProviderReadingBindingRepository(
        private var binding: ProviderReadingBinding,
    ) : ProviderReadingBindingRepository {

        override suspend fun get(
            canonicalTitleId: String,
            providerId: ProviderId,
            facetId: String?,
        ): ProviderReadingBinding? =
            binding.takeIf {
                it.canonicalTitleId == canonicalTitleId &&
                    it.ref.providerId == providerId &&
                    it.ref.facetId == facetId
            }

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
            listOf(binding).filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ProviderReadingBinding) {
            this.binding = binding
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            if (binding.id == bindingId) {
                binding = binding.copy(
                    availability = ProviderBindingAvailability.UNAVAILABLE,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    private class InMemoryChapterEvidenceRepository : ChapterEvidenceRepository {
        private val records = mutableListOf<PersistedChapterEvidence>()

        override suspend fun getByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<PersistedChapterEvidence> =
            records.filter { it.evidence.canonicalTitleId == canonicalTitleId }

        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? =
            records.firstOrNull {
                it.evidence.producerKind == producerKind &&
                    it.evidence.producerId == producerId &&
                    it.evidence.externalChapterKey == externalChapterKey
            }

        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence {
            val existingIndex = records.indexOfFirst { current ->
                current.evidence.id == evidence.id ||
                    (
                        evidence.externalChapterKey != null &&
                            current.evidence.producerKind == evidence.producerKind &&
                            current.evidence.producerId == evidence.producerId &&
                            current.evidence.externalChapterKey == evidence.externalChapterKey
                        )
            }
            val stableEvidence = if (existingIndex >= 0) {
                evidence.copy(id = records[existingIndex].evidence.id)
            } else {
                evidence
            }
            val persisted = PersistedChapterEvidence(
                evidence = stableEvidence,
                mappedCanonicalChapterId = mappedCanonicalChapterId,
            )
            if (existingIndex >= 0) {
                records[existingIndex] = persisted
            } else {
                records += persisted
            }
            return persisted
        }
    }

    private class InMemoryCanonicalChapterRepository : CanonicalChapterRepository {
        private val chapters = linkedMapOf<String, CanonicalChapter>()
        private val state = MutableStateFlow<List<CanonicalChapter>>(emptyList())

        override suspend fun getByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapter>> = state

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(
            canonicalChapterId: String,
        ): List<ChapterVariant> = emptyList()

        override suspend fun getVariantsBySourceMappingId(
            sourceMappingId: String,
        ): List<ChapterVariant> = emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
            state.value = chapters.values.toList()
        }

        override suspend fun upsertVariant(variant: ChapterVariant) = Unit

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) {
            chapters.forEach { upsert(it) }
        }
    }

    private fun gateway(
        main: String,
        providerId: ProviderId = PROVIDER_ID,
        networkOrigins: Set<String>,
        browserOrigins: Set<String> = emptySet(),
        localNetwork: Boolean,
        managedFiles: ProviderManagedFileStore? = null,
        includePages: Boolean = true,
        facetLanguage: String = "en",
        onBindingPhase: (ProviderRuntimeBindingPhase) -> Unit = {},
        onRegistry: (DefaultProviderRegistry) -> Unit = {},
        packageBytesOverride: ByteArray? = null,
        baseHttpClient: OkHttpClient = OkHttpClient(),
        repositoryId: String = REPOSITORY_ID,
        packageVersionCode: Long = 1L,
        packageVersionName: String = "1.0.1",
        experimentalV2OptIn: Boolean = false,
    ): ScriptProviderReadingGateway {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageRoot = File(context.cacheDir, "provider-reading-gateway").apply {
            deleteRecursively()
        }
        val packageBytes = packageBytesOverride ?: tsz(
            main = main,
            providerId = providerId,
            networkOrigins = networkOrigins,
            browserOrigins = browserOrigins,
            localNetwork = localNetwork,
            includePages = includePages,
            versionCode = packageVersionCode,
            versionName = packageVersionName,
        )
        val parsed = ProviderPackageParser().parse(packageBytes)
        val descriptor = ProviderDescriptor(
            id = providerId,
            name = "Reading Fixture",
            version = ProviderVersion(parsed.manifest.version.name, parsed.manifest.version.code),
            origin = ProviderOrigin.Repository(repositoryId),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = buildSet {
                add(ProviderCapabilities.ReadingLookupV1)
                add(ProviderCapabilities.ReadingChaptersV1)
                if (includePages) add(ProviderCapabilities.ReadingPagesV1)
            },
            permissions = ProviderPermissionSet(
                network = ProviderNetworkPermission(
                    origins = networkOrigins,
                    localNetwork = localNetwork,
                ).takeIf { networkOrigins.isNotEmpty() || localNetwork },
                browser = ProviderBrowserPermission(browserOrigins)
                    .takeIf { browserOrigins.isNotEmpty() },
            ),
            settings = emptyList(),
            contentLanguages = setOf(facetLanguage),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = descriptor,
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        facets = listOf(ProviderFacetRef(providerId, facetLanguage)),
                        configurationFingerprint = "instrumentation",
                    ),
                )
            },
        )
        onRegistry(registry)
        val managedFileStore = managedFiles ?: ProviderManagedFileStore(context)
        val runtimeClient = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = storageRoot,
                managedFiles = managedFileStore,
                baseHttpClient = baseHttpClient,
            ),
            bindingObserver = onBindingPhase,
        )

        return ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource {
                ActiveProviderScriptPackage(
                    repositoryId = repositoryId,
                    versionCode = parsed.manifest.version.code,
                    bytes = packageBytes,
                    parsed = parsed,
                )
            },
            runtimeClient = runtimeClient,
            managedResources = managedFileStore,
            enableExperimentalMangaAllV2 = experimentalV2OptIn,
        )
    }

    private fun complexReadingBundle(
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.RED)
        bitmap.setPixel(1, 0, Color.BLUE)
        val png = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv),
            )
            doFinal(png)
        }
        val chapter = binaryZip("001.png" to png)

        return binaryZip(
            "probe.enc" to encrypted,
            "chapter.cbz" to chapter,
        )
    }

    private fun binaryZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }

    private fun tsz(
        main: String,
        providerId: ProviderId = PROVIDER_ID,
        networkOrigins: Set<String>,
        browserOrigins: Set<String>,
        localNetwork: Boolean,
        includePages: Boolean = true,
        versionCode: Long = 1L,
        versionName: String = "1.0.1",
    ): ByteArray {
        fun origins(values: Set<String>) =
            values.joinToString(prefix = "[", postfix = "]") { value -> """"$value"""" }

        val pageCapability = if (includePages) """, {"id":"reading.pages","version":1}""" else ""
        val manifest = """
            {
              "manifestVersion": 1,
              "id": "${providerId.value}",
              "name": "Reading Fixture",
              "version": {"name": "$versionName", "code": $versionCode},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [
                {"id":"reading.lookup","version":1},
                {"id":"reading.chapters","version":1}$pageCapability
              ],
              "permissions": {
                "network": {
                  "origins": ${origins(networkOrigins)},
                  "localNetwork": $localNetwork
                },
                "browser": {
                  "origins": ${origins(browserOrigins)}
                }
              },
              "contentLanguages": ["en"],
              "settings": []
            }
        """.trimIndent()

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(path: String, value: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.encodeToByteArray())
                    zip.closeEntry()
                }

                entry("manifest.json", manifest)
                entry("main.js", main)
            }
            output.toByteArray()
        }
    }

    private fun MockWebServer.origin(): String =
        url("/").let { "${it.scheme}://${it.host}:${it.port}" }

    private companion object {
        val PROVIDER_ID = ProviderId("org.example.reader")
        const val REPOSITORY_ID = "repo.example"
    }
}

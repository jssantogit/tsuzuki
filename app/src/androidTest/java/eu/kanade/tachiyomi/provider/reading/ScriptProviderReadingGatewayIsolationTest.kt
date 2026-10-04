package eu.kanade.tachiyomi.provider.reading

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.reader.CanonicalLocalReaderFormat
import eu.kanade.tachiyomi.ui.reader.loader.LocalChapterLoader
import okio.Buffer
import org.junit.Assert.assertNotNull
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderManagedFileStore
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.CanonicalReaderTargetPlan
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderBrowserPermission
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBindingRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
import tachiyomi.domain.tsuzuki.provider.reading.RefreshProviderReadingChapters
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipOutputStream

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
            assertEquals(2, server.requestCount)
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
        networkOrigins: Set<String>,
        browserOrigins: Set<String> = emptySet(),
        localNetwork: Boolean,
        managedFiles: ProviderManagedFileStore? = null,
    ): ScriptProviderReadingGateway {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageRoot = File(context.cacheDir, "provider-reading-gateway").apply {
            deleteRecursively()
        }
        val packageBytes = tsz(
            main = main,
            networkOrigins = networkOrigins,
            browserOrigins = browserOrigins,
            localNetwork = localNetwork,
        )
        val parsed = ProviderPackageParser().parse(packageBytes)
        val descriptor = ProviderDescriptor(
            id = PROVIDER_ID,
            name = "Reading Fixture",
            version = ProviderVersion("1.0.1", 1),
            origin = ProviderOrigin.Repository(REPOSITORY_ID),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(
                ProviderCapabilities.ReadingLookupV1,
                ProviderCapabilities.ReadingChaptersV1,
                ProviderCapabilities.ReadingPagesV1,
            ),
            permissions = ProviderPermissionSet(
                network = ProviderNetworkPermission(
                    origins = networkOrigins,
                    localNetwork = localNetwork,
                ).takeIf { networkOrigins.isNotEmpty() || localNetwork },
                browser = ProviderBrowserPermission(browserOrigins)
                    .takeIf { browserOrigins.isNotEmpty() },
            ),
            settings = emptyList(),
            contentLanguages = setOf("en"),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = descriptor,
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        configurationFingerprint = "instrumentation",
                    ),
                )
            },
        )
        val managedFileStore = managedFiles ?: ProviderManagedFileStore(context)
        val runtimeClient = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = storageRoot,
                managedFiles = managedFileStore,
            ),
        )

        return ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource {
                ActiveProviderScriptPackage(
                    repositoryId = REPOSITORY_ID,
                    versionCode = 1,
                    bytes = packageBytes,
                    parsed = parsed,
                )
            },
            runtimeClient = runtimeClient,
            managedResources = managedFileStore,
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
        networkOrigins: Set<String>,
        browserOrigins: Set<String>,
        localNetwork: Boolean,
    ): ByteArray {
        fun origins(values: Set<String>) =
            values.joinToString(prefix = "[", postfix = "]") { value -> """"$value"""" }

        val manifest = """
            {
              "manifestVersion": 1,
              "id": "${PROVIDER_ID.value}",
              "name": "Reading Fixture",
              "version": {"name": "1.0.1", "code": 1},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [
                {"id":"reading.lookup","version":1},
                {"id":"reading.chapters","version":1},
                {"id":"reading.pages","version":1}
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

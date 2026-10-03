package eu.kanade.tachiyomi.provider.reading

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpChapterLoader
import eu.kanade.tachiyomi.ui.reader.CanonicalReaderTargetPlan
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
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
                canonicalChapterId = "canonical-chapter-1",
                pages = prepared.pages,
            )
            val readerLoader = ProviderHttpChapterLoader.from(
                InstrumentationRegistry.getInstrumentation().targetContext,
                plan,
            )
            val readerChapter = ReaderChapter(
                ChapterImpl().apply {
                    id = Long.MIN_VALUE
                    url = "provider-pages:canonical-chapter-1"
                    name = "Chapter 1"
                },
            )
            try {
                readerLoader.loadChapter(readerChapter)
                val readerPage = readerChapter.pages!!.single()
                readerChapter.pageLoader!!.loadPage(readerPage)
                assertEquals(Page.State.Ready, readerPage.status)
                assertEquals(
                    "reader-image-bytes",
                    readerPage.stream!!.invoke().use { it.readBytes().decodeToString() },
                )
            } finally {
                readerChapter.pageLoader?.recycle()
            }

            assertEquals("/lookup", server.takeRequest().url.encodedPath)
            assertEquals("/chapters", server.takeRequest().url.encodedPath)
            assertEquals("/pages/001.jpg", server.takeRequest().url.encodedPath)
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
            assertEquals(1, server.requestCount)
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

        val gateway = gateway(
            networkOrigins = emptySet(),
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
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    private fun gateway(
        main: String,
        networkOrigins: Set<String>,
        browserOrigins: Set<String> = emptySet(),
        localNetwork: Boolean,
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
        val runtimeClient = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = storageRoot,
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
        )
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

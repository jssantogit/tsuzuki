package eu.kanade.tachiyomi.provider.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.provider.torrent.ScriptProviderTorrentGateway
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

@RunWith(AndroidJUnit4::class)
class ProviderTorrentRuntimeBridgeTest {

    @Test
    fun scriptTorrentProvider_crossesRealIsolatedRuntimeAndHostHttp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = MockWebServer()
        server.start()
        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        server.enqueue(
            MockResponse(
                body = """
                    {
                      "items": [
                        {
                          "infoHash": "0123456789abcdef0123456789abcdef01234567",
                          "magnetUri": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                          "displayName": "Test Manga chapter pack",
                          "languages": ["en"],
                          "files": [
                            {"index": 0, "path": "chapter-011.cbz", "sizeBytes": 101},
                            {"index": 1, "path": "chapter-012.cbz", "sizeBytes": 202},
                            {"index": 2, "path": "chapter-013.cbz", "sizeBytes": 303}
                          ]
                        }
                      ],
                      "nextCursor": null
                    }
                """.trimIndent(),
            ),
        )

        try {
            val fixture = ProviderTorrentAcceptanceFixtures.runtimeTorrentGateway(
                context = context,
                origin = origin,
            )
            val gateway: ScriptProviderTorrentGateway = fixture.gateway

            val result = gateway.search(
                providerId = fixture.providerId,
                request = TorrentSearchRequest(
                    titles = listOf("Test Manga", "Test Manga Alt"),
                    preferredLanguages = setOf("en"),
                    chapterNumber = "12",
                    volume = 2,
                    cursor = null,
                ),
            )

            assertTrue(result is ProviderCallResult.Success)
            val page = (result as ProviderCallResult.Success).value
            assertEquals(1, page.items.size)
            val candidate = page.items.single()
            assertEquals("0123456789abcdef0123456789abcdef01234567", candidate.infoHash)
            assertEquals(
                listOf("chapter-011.cbz", "chapter-012.cbz", "chapter-013.cbz"),
                candidate.files?.map { it.path },
            )

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("Test Manga|Test Manga Alt", request.url.queryParameter("titles"))
            assertEquals("12", request.url.queryParameter("chapter"))
            assertEquals("2", request.url.queryParameter("volume"))
            assertEquals("en", request.url.queryParameter("languages"))
        } finally {
            server.close()
        }
    }
}

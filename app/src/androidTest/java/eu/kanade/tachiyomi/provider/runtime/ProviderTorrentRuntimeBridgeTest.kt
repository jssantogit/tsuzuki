package eu.kanade.tachiyomi.provider.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.provider.torrent.ScriptProviderTorrentGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProviderTorrentRuntimeBridgeTest {

    @Test
    fun torrentSearch_runsThroughRealScriptRuntimeAndHostHttpBridge() = runBlocking {
        withGateway(MockResponse(body = CANDIDATE_PAGE)) { gateway, server ->
            val result = gateway.search(PROVIDER_ID, searchRequest())

            assertTrue("expected successful torrent search but was $result", result is ProviderCallResult.Success)
            val page = (result as ProviderCallResult.Success).value
            val candidate = page.items.single()
            assertEquals(INFO_HASH, candidate.infoHash)
            assertEquals("Acceptance pack", candidate.displayName)
            assertEquals(
                listOf("chapter-011.cbz", "chapter-012.cbz", "chapter-013.cbz"),
                candidate.files.orEmpty().map { it.path },
            )

            assertEquals(1, server.requestCount)
            val request = withContext(Dispatchers.IO) { server.takeRequest() }
            assertEquals("GET", request.method)
            assertEquals("/torrent-search", request.url.encodedPath)
            assertEquals("Test Manga|Test Manga Alt", request.headers["X-Tsuzuki-Titles"])
            assertEquals("en", request.headers["X-Tsuzuki-Languages"])
            assertEquals("12", request.headers["X-Tsuzuki-Chapter"])
            assertEquals("2", request.headers["X-Tsuzuki-Volume"])
        }
    }

    @Test
    fun torrentSearch_malformedProviderPageReturnsTypedFailureBeforeMapping() = runBlocking {
        withGateway(MockResponse(body = """{"items":"not-a-list"}""")) { gateway, server ->
            val result = gateway.search(PROVIDER_ID, searchRequest())

            assertTrue("expected malformed result failure but was $result", result is ProviderCallResult.Failure)
            val failure = result as ProviderCallResult.Failure
            assertEquals(ProviderErrorCode.MALFORMED_RESULT, failure.error.code)
            assertEquals(false, failure.error.retryable)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun torrentSearch_slowHostHttpReturnsTypedRetryableFailure() = runBlocking {
        val delayed = MockResponse.Builder()
            .body(CANDIDATE_PAGE)
            .bodyDelay(5, TimeUnit.SECONDS)
            .build()
        val limits = ProviderRuntimeLimitsDto(
            wallClockTimeoutMs = 500,
            jsExecutionTimeoutMs = 500,
        )

        withGateway(delayed, limits) { gateway, server ->
            val result = gateway.search(PROVIDER_ID, searchRequest())

            assertTrue("expected bounded Host HTTP failure but was $result", result is ProviderCallResult.Failure)
            val failure = result as ProviderCallResult.Failure
            assertTrue(
                "slow Host HTTP must remain a retryable typed runtime/Host failure but was ${failure.error}",
                failure.error.code == ProviderErrorCode.UNAVAILABLE ||
                    failure.error.code == ProviderErrorCode.TIMEOUT,
            )
            assertEquals(true, failure.error.retryable)
            assertEquals(1, server.requestCount)
        }
    }

    private suspend fun withGateway(
        response: MockResponse,
        limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
        block: suspend (ScriptProviderTorrentGateway, MockWebServer) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        server.enqueue(response)
        server.start()

        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val activePackage = ProviderTorrentAcceptanceFixtures.createTorrentSearchProviderPackage(
            providerId = PROVIDER_ID.value,
            repositoryId = REPOSITORY_ID,
            origin = origin,
            endpoint = server.url("/torrent-search").toString(),
        )
        val storageRoot = File(context.cacheDir, "provider-torrent-runtime-bridge").apply {
            deleteRecursively()
        }
        val runtimeClient = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = storageRoot,
            ),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(registration(activePackage))
            },
        )
        val executor = ScriptProviderCapabilityExecutor(
            registry = registry,
            packageSource = ScriptProviderPackageSource { requested ->
                activePackage.takeIf { requested == PROVIDER_ID }
            },
            runtimeClient = runtimeClient,
            limits = limits,
        )
        val gateway = ScriptProviderTorrentGateway(executor)

        try {
            block(gateway, server)
        } finally {
            server.close()
            storageRoot.deleteRecursively()
        }
    }

    private fun searchRequest() = TorrentSearchRequest(
        titles = listOf("Test Manga", "Test Manga Alt"),
        preferredLanguages = setOf("en"),
        chapterNumber = "12",
        volume = 2,
    )

    private fun registration(activePackage: ScriptProviderPackage): ProviderRegistration {
        val manifest = activePackage.parsed.manifest
        return ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = ProviderId(manifest.id),
                name = manifest.name,
                version = ProviderVersion(manifest.version.name, manifest.version.code),
                origin = ProviderOrigin.Repository(activePackage.repositoryId),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                permissions = ProviderPermissionSet(),
                settings = emptyList(),
                contentLanguages = manifest.contentLanguages,
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
            configurationFingerprint = "acceptance-v1",
            enabledCapabilities = setOf(ProviderCapabilities.TorrentSearchV1),
        )
    }

    private companion object {
        val PROVIDER_ID = ProviderId("org.example.torrent.acceptance")
        const val REPOSITORY_ID = "acceptance.repo"
        const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        const val CANDIDATE_PAGE = """
            {
              "items": [
                {
                  "infoHash": "$INFO_HASH",
                  "magnetUri": "magnet:?xt=urn:btih:$INFO_HASH",
                  "displayName": "Acceptance pack",
                  "sizeBytes": 3072,
                  "seeders": 4,
                  "peers": 1,
                  "languages": ["en"],
                  "files": [
                    {"index":0,"path":"chapter-011.cbz","sizeBytes":1024,"languages":["en"]},
                    {"index":1,"path":"chapter-012.cbz","sizeBytes":1024,"languages":["en"]},
                    {"index":2,"path":"chapter-013.cbz","sizeBytes":1024,"languages":["en"]}
                  ]
                }
              ]
            }
        """
    }
}

package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ProviderQuickJsRuntimeTest {

    @Test
    fun `evaluates primitive JavaScript result`() = runBlocking {
        ProviderQuickJsRuntime().evaluate("1 + 2") shouldBe
            ProviderScriptExecution.Success("3")
    }

    @Test
    fun `uses a fresh JavaScript VM for every invocation`() = runBlocking {
        val runtime = ProviderQuickJsRuntime()
        val source = "globalThis.counter = (globalThis.counter || 0) + 1; counter"

        runtime.evaluate(source) shouldBe ProviderScriptExecution.Success("1")
        runtime.evaluate(source) shouldBe ProviderScriptExecution.Success("1")
    }

    @Test
    fun `interrupts runaway JavaScript and sanitizes script failures`() = runBlocking {
        val runtime = ProviderQuickJsRuntime(
            limits = ProviderRuntimeLimits(
                wallClockTimeoutMs = 100,
                jsExecutionTimeoutMs = 50,
                memoryLimitBytes = 8L * 1024L * 1024L,
                stackLimitBytes = 256L * 1024L,
            ),
        )

        runtime.evaluate("while (true) {}") shouldBe
            ProviderScriptExecution.Failure(ProviderScriptFailure.TIMEOUT)
        runtime.evaluate("throw new Error('provider secret text')") shouldBe
            ProviderScriptExecution.Failure(ProviderScriptFailure.SCRIPT_ERROR)
    }

    @Test
    fun `async Host wait does not expire subsequent JavaScript execution budget`() = runBlocking {
        val runtime = ProviderQuickJsRuntime(
            limits = ProviderRuntimeLimits(
                wallClockTimeoutMs = 1_000,
                jsExecutionTimeoutMs = 100,
                memoryLimitBytes = 8L * 1024L * 1024L,
                stackLimitBytes = 256L * 1024L,
            ),
        )
        val services = ProviderHostServices(
            http = object : ProviderHttpHostService {
                override suspend fun getText(url: String): String {
                    delay(250)
                    return "host-ok"
                }

                override suspend fun getResource(url: String): ProviderResourceHandle = error("unused")
            },
        )

        runtime.evaluate(
            source = """
                await tsuzuki.http.get('https://allowed.example/data');
                let total = 0;
                for (let i = 0; i < 100000; i++) total += i;
                total > 0 ? 7 : 0;
            """.trimIndent(),
            hostServices = services,
        ) shouldBe ProviderScriptExecution.Success("7")
    }

    @Test
    fun `distinguishes host service failures from provider script failures`() = runBlocking {
        val runtime = ProviderQuickJsRuntime()
        val services = ProviderHostServices(
            http = object : ProviderHttpHostService {
                override suspend fun getText(url: String): String {
                    throw IllegalStateException("host-only detail")
                }

                override suspend fun getResource(url: String): ProviderResourceHandle {
                    throw IllegalStateException("host-only detail")
                }
            },
        )

        runtime.evaluate(
            source = "await tsuzuki.http.get('https://allowed.example/data')",
            hostServices = services,
        ) shouldBe ProviderScriptExecution.Failure(ProviderScriptFailure.HOST_ERROR)
    }

    @Test
    fun `provider scripts can issue typed HTTP API requests through host service`() = runBlocking {
        var captured: ProviderHttpRequest? = null
        val services = ProviderHostServices(
            http = object : ProviderHttpHostService {
                override suspend fun request(request: ProviderHttpRequest): ProviderHttpResponse {
                    captured = request
                    return ProviderHttpResponse(
                        statusCode = 202,
                        body = """{"job":"queued"}""",
                    )
                }

                override suspend fun getText(url: String): String = error("unused")

                override suspend fun getResource(url: String): ProviderResourceHandle = error("unused")
            },
        )

        ProviderQuickJsRuntime().evaluate(
            source = """
                const response = JSON.parse(
                  await tsuzuki.http.request(
                    "POST",
                    "https://debrid.example/torrents",
                    JSON.stringify({"Authorization":"Bearer opaque","Content-Type":"application/json"}),
                    JSON.stringify({"magnet":"magnet:?xt=urn:btih:abc"})
                  )
                );
                response.statusCode
            """.trimIndent(),
            hostServices = services,
        ) shouldBe ProviderScriptExecution.Success("202")

        captured shouldBe ProviderHttpRequest(
            method = ProviderHttpMethod.POST,
            url = "https://debrid.example/torrents",
            headers = mapOf(
                "Authorization" to "Bearer opaque",
                "Content-Type" to "application/json",
            ),
            body = """{"magnet":"magnet:?xt=urn:btih:abc"}""",
        )
    }

    @Test
    fun `provider scripts can use host granted p2p without receiving native authority`() = runBlocking {
        var captured: ProviderP2pAcquireRequest? = null
        val services = ProviderHostServices(
            p2p = ProviderP2pHostService { request ->
                captured = request
                ProviderP2pAcquireResponse.Pending("host-job-12")
            },
        )

        ProviderQuickJsRuntime().evaluate(
            source = """
                const response = JSON.parse(
                  await tsuzuki.p2p.acquire(
                    JSON.stringify({
                      operationId: "read:canonical-12",
                      magnetUri: "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                      selectedFileIndex: 1,
                      selectedFilePath: "pack/chapter-012.cbz"
                    })
                  )
                );
                response.status + ":" + response.jobId
            """.trimIndent(),
            hostServices = services,
        ) shouldBe ProviderScriptExecution.Success("pending:host-job-12")

        captured shouldBe ProviderP2pAcquireRequest(
            operationId = "read:canonical-12",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            torrentUrl = null,
            infoHash = null,
            selectedFileIndex = 1,
            selectedFilePath = "pack/chapter-012.cbz",
        )
    }

    @Test
    fun `exposes only configured host service modules`() = runBlocking {
        val services = ProviderHostServices(
            http = object : ProviderHttpHostService {
                override suspend fun getText(url: String): String {
                    url shouldBe "https://allowed.example/data"
                    return "http-ok"
                }

                override suspend fun getResource(url: String): ProviderResourceHandle =
                    ProviderResourceHandle("res:test")
            },
            storage = object : ProviderStorageHostService {
                private val values = mutableMapOf<String, String>()

                override suspend fun get(key: String): String? = values[key]

                override suspend fun set(key: String, value: String) {
                    values[key] = value
                }

                override suspend fun remove(key: String) {
                    values.remove(key)
                }
            },
        )
        val runtime = ProviderQuickJsRuntime()

        runtime.evaluate(
            source = "await tsuzuki.http.get('https://allowed.example/data')",
            hostServices = services,
        ) shouldBe ProviderScriptExecution.Success("http-ok")

        runtime.evaluate(
            source = "await tsuzuki.storage.set('key', 'value'); await tsuzuki.storage.get('key')",
            hostServices = services,
        ) shouldBe ProviderScriptExecution.Success("value")

        runtime.evaluate(
            source = "await tsuzuki.secrets.get('missing')",
            hostServices = services,
        ) shouldBe ProviderScriptExecution.Failure(ProviderScriptFailure.SCRIPT_ERROR)
    }
}

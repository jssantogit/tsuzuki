package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
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
    fun `distinguishes host service failures from provider script failures`() = runBlocking {
        val runtime = ProviderQuickJsRuntime()
        val services = ProviderHostServices(
            http = object : ProviderHttpHostService {
                override suspend fun getText(url: String): String {
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
    fun `exposes only configured host service modules`() = runBlocking {
        val services = ProviderHostServices(
            http = object : ProviderHttpHostService {
                override suspend fun getText(url: String): String {
                    url shouldBe "https://allowed.example/data"
                    return "http-ok"
                }
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

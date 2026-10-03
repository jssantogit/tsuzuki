package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ProviderHostBridgeRuntimeTest {

    @Test
    fun `provider JavaScript reaches declared host bridge`() = runBlocking {
        val hostBridge = object : ProviderHostBridge {
            override suspend fun httpGet(url: String): String {
                url shouldBe "https://allowed.example/data"
                return "http-ok"
            }

            override suspend fun browserReadText(
                url: String,
                cssSelector: String,
            ): String {
                url shouldBe "https://allowed.example/browser"
                cssSelector shouldBe "#probe"
                return "browser-ok"
            }
        }

        val runtime = ProviderQuickJsRuntime()
        runtime.evaluate(
            source = "await tsuzuki.http.get('https://allowed.example/data')",
            hostBridge = hostBridge,
        ) shouldBe ProviderScriptExecution.Success("http-ok")

        runtime.evaluate(
            source = "await tsuzuki.browser.readText('https://allowed.example/browser', '#probe')",
            hostBridge = hostBridge,
        ) shouldBe ProviderScriptExecution.Success("browser-ok")
    }
}

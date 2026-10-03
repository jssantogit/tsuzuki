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

    @Test
    fun `provider JavaScript keeps binary values behind opaque host handles`() = runBlocking {
        val hostBridge = object : ProviderHostBridge {
            override suspend fun httpGet(url: String): String = error("not used")

            override suspend fun browserReadText(
                url: String,
                cssSelector: String,
            ): String = error("not used")

            override suspend fun binaryFetch(url: String): String {
                url shouldBe "https://allowed.example/bundle.zip"
                return "res:1"
            }

            override suspend fun binaryZipEntry(
                resourceHandle: String,
                entryName: String,
            ): String {
                resourceHandle shouldBe "res:1"
                entryName shouldBe "page.enc"
                return "res:2"
            }

            override suspend fun binaryAesCbcDecrypt(
                resourceHandle: String,
                keyHex: String,
                ivHex: String,
            ): String {
                resourceHandle shouldBe "res:2"
                keyHex shouldBe "0011"
                ivHex shouldBe "2233"
                return "res:3"
            }

            override suspend fun binaryImageCrop(
                resourceHandle: String,
                x: Int,
                y: Int,
                width: Int,
                height: Int,
            ): String {
                resourceHandle shouldBe "res:3"
                x shouldBe 1
                y shouldBe 2
                width shouldBe 3
                height shouldBe 4
                return "res:4"
            }

            override suspend fun binaryImagePixel(
                resourceHandle: String,
                x: Int,
                y: Int,
            ): String {
                resourceHandle shouldBe "res:4"
                x shouldBe 5
                y shouldBe 6
                return "FF112233"
            }
        }

        val runtime = ProviderQuickJsRuntime()
        val source = """
            const archive = await tsuzuki.binary.fetch('https://allowed.example/bundle.zip');
            const entry = await tsuzuki.binary.zipEntry(archive, 'page.enc');
            const decrypted = await tsuzuki.binary.aesCbcDecrypt(entry, '0011', '2233');
            const cropped = await tsuzuki.binary.imageCrop(decrypted, 1, 2, 3, 4);
            await tsuzuki.binary.imagePixel(cropped, 5, 6);
        """.trimIndent()

        runtime.evaluate(source, hostBridge = hostBridge) shouldBe
            ProviderScriptExecution.Success("FF112233")
    }
}

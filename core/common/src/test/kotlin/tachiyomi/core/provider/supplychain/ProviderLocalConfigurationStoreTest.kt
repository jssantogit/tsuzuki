package tachiyomi.core.provider.supplychain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProviderLocalConfigurationStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `persists enablement and explicit content-language selection`() {
        val store = FileProviderLocalConfigurationStore(tempDir.resolve("config").toFile())
        store.save(
            ProviderLocalConfiguration(
                providerId = "reader.example",
                enabled = false,
                enabledContentLanguages = setOf("pt-BR", "en"),
            ),
        )

        val restarted = FileProviderLocalConfigurationStore(tempDir.resolve("config").toFile())
        restarted.get("reader.example") shouldBe ProviderLocalConfiguration(
            providerId = "reader.example",
            enabled = false,
            enabledContentLanguages = setOf("pt-BR", "en"),
        )
    }

    @Test
    fun `null language selection means all declared languages while empty means none`() {
        val store = FileProviderLocalConfigurationStore(tempDir.resolve("semantics").toFile())

        store.save(
            ProviderLocalConfiguration(
                providerId = "reader.example",
                enabledContentLanguages = null,
            ),
        )
        store.get("reader.example")?.enabledContentLanguages shouldBe null

        store.save(
            ProviderLocalConfiguration(
                providerId = "reader.example",
                enabledContentLanguages = emptySet(),
            ),
        )
        store.get("reader.example")?.enabledContentLanguages shouldBe emptySet()
    }

    @Test
    fun `configuration list is deterministic and removal is idempotent`() {
        val store = FileProviderLocalConfigurationStore(tempDir.resolve("list").toFile())
        store.save(ProviderLocalConfiguration(providerId = "z.reader"))
        store.save(ProviderLocalConfiguration(providerId = "a.reader"))

        store.list().map { it.providerId } shouldBe listOf("a.reader", "z.reader")
        store.remove("a.reader") shouldBe true
        store.remove("a.reader") shouldBe false
    }
}

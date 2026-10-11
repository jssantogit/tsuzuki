package eu.kanade.presentation.tsuzuki

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinVisualContractTest {

    @Test
    fun `shared Default Skin owns the approved neutral palette`() {
        val source = readSource("components/DefaultSkinPrimitives.kt")

        source shouldContain "0xFFF5F3EC"
        source shouldContain "0xFF0B0C0D"
        source shouldContain "0xFF242628"
        source shouldContain "0xFFC9CCCE"
        source shouldNotContain "0xFF275A80"
    }

    @Test
    fun `entry search profile and settings consume shared Default Skin primitives`() {
        readSource("entry/TsuzukiEntryScreen.kt") shouldContain "defaultSkinColors()"
        readSource("search/TsuzukiSearchScreen.kt") shouldContain "defaultSkinColors()"
        readSource("profile/TsuzukiProfileScreen.kt") shouldContain "defaultSkinColors()"
        readSettingsSource("SettingsMainScreen.kt") shouldContain "defaultSkinColors()"
    }

    @Test
    fun `public entry and search do not fall back to stock outlined Material fields`() {
        val entry = readSource("entry/TsuzukiEntryScreen.kt")
        val search = readSource("search/TsuzukiSearchScreen.kt")

        entry shouldNotContain "OutlinedTextField("
        search shouldNotContain "OutlinedTextField("
        entry shouldContain "BasicTextField("
        search shouldContain "BasicTextField("
    }

    @Test
    fun `search empty state stays owned by metadata integrations`() {
        val search = readSource("search/TsuzukiSearchScreen.kt")

        search shouldContain "onOpenIntegrations"
        search shouldContain "tsuzuki_search_configure_integrations"
        search shouldNotContain "tsuzuki_search_configure_providers"
    }

    private fun readSource(relativePath: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
        Path.of("app/src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
    )

    private fun readSettingsSource(fileName: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
        Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
    )

    private fun readFirst(vararg candidates: Path): String {
        val source = candidates.firstOrNull(Files::exists)
            ?: error("Source file was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}

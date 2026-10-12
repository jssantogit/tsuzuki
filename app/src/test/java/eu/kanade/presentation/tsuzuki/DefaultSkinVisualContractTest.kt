package eu.kanade.presentation.tsuzuki

import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.home.homeModuleTopSpacing
import io.kotest.matchers.shouldBe
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
    fun `promoted root surfaces consume shared Default Skin primitives`() {
        readSource("entry/TsuzukiEntryScreen.kt") shouldContain "defaultSkinColors()"
        readSource("home/TsuzukiHomeScreen.kt") shouldContain "defaultSkinColors()"
        readSource("search/TsuzukiSearchScreen.kt") shouldContain "defaultSkinColors()"
        readSource("profile/TsuzukiProfileScreen.kt") shouldContain "defaultSkinColors()"
        readSource("library/CanonicalLibraryScreen.kt") shouldContain "defaultSkinColors()"
        readSettingsSource("SettingsMainScreen.kt") shouldContain "SettingsCanvas {"
        readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt") shouldContain "defaultSkinColors()"
        providerLanding() shouldContain "defaultSkinColors()"
        providerDetail() shouldContain "defaultSkinColors()"
        trackingService() shouldContain "defaultSkinColors()"
        readSettingsComponentSource("SettingsPresentation.kt") shouldContain "defaultSkinColors()"
    }

    @Test
    fun `public text entry surfaces do not fall back to stock outlined Material fields`() {
        val files = listOf(
            readSource("entry/TsuzukiEntryScreen.kt"),
            readSource("search/TsuzukiSearchScreen.kt"),
            readSource("library/CanonicalLibraryScreen.kt"),
            readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt"),
            providerLanding(),
            trackingService(),
        )

        files.forEach { source -> source shouldNotContain "OutlinedTextField(" }
        readSource("entry/TsuzukiEntryScreen.kt") shouldContain "DefaultSkinFormField("
        readSource("components/DefaultSkinPrimitives.kt") shouldContain "BasicTextField("
        readSource("search/TsuzukiSearchScreen.kt") shouldContain "BasicTextField("
    }

    @Test
    fun `Home actions use Default Skin hierarchy instead of stock Material buttons`() {
        val home = readSource("home/TsuzukiHomeScreen.kt")

        home shouldContain "DefaultSkinPrimaryButton("
        home shouldContain "DefaultSkinSecondaryButton("
        home shouldNotContain "import androidx.compose.material3.Button"
        home shouldNotContain "import androidx.compose.material3.OutlinedButton"
    }

    @Test
    fun `canonical details hero keeps one artwork layer in the title transition without a phantom read spacer`() {
        val hero = readSource("detail/CanonicalDetailsHero.kt")
        val atmosphere = sourceBlockStartingAt(
            hero,
            "Box(Modifier.fillMaxWidth().background(background))",
        )
        val artworkIndex = atmosphere.indexOf("MangaCover.Square(")
        val titleIndex = atmosphere.indexOf("state.title.displayTitle")

        hero.split("MangaCover.Square(").size shouldBe 2
        hero shouldNotContain "AsyncImage("
        hero shouldNotContain "MangaCover.Book("
        (artworkIndex >= 0 && titleIndex > artworkIndex) shouldBe true
        atmosphere shouldNotContain "if (state.tsuzukiRating"
        (hero.indexOf("if (state.tsuzukiRating") > hero.indexOf("state.title.displayTitle")) shouldBe true
        hero shouldNotContain "Spacer(Modifier.weight(1f))"
        hero shouldNotContain "Arrangement.End"
    }

    @Test
    fun `canonical chapter actions and Home hero controls stay on the Default Skin`() {
        val chapters = readSource("detail/CanonicalDetailsChapterComponents.kt")
        val home = readSource("home/TsuzukiHomeScreen.kt")
        val homeHero = home.substringAfter("private fun HomeHero(")
            .substringBefore("\nprivate fun ContinueReadingSection")

        chapters shouldContain "DefaultSkinTextAction("
        chapters shouldNotContain "TextButton("
        homeHero shouldContain "FlowRow("
        homeHero shouldNotContain "Modifier.weight(1f)"
        home shouldNotContain "home_safe_top"
    }

    @Test
    fun `Home keeps its first post-hero gap compact and ordinary module gaps intact`() {
        homeModuleTopSpacing(
            previousModuleExists = false,
            heroPrecedesModule = true,
        ) shouldBe DefaultSkinSpacing.Group
        homeModuleTopSpacing(
            previousModuleExists = true,
            heroPrecedesModule = false,
        ) shouldBe DefaultSkinSpacing.Section
        homeModuleTopSpacing(
            previousModuleExists = false,
            heroPrecedesModule = false,
        ) shouldBe 0.dp
    }

    @Test
    fun `library advanced filters use Default Skin controls instead of stock filter chips`() {
        val library = readSource("library/CanonicalLibraryScreen.kt")

        library shouldContain "DefaultSkinChoiceChip("
        library shouldNotContain "FilterChip("
        library shouldNotContain "SuggestionChip("
    }

    @Test
    fun `integrations and Provider family use promoted page composition instead of stock app bars`() {
        val integrations = readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt")
        val providers = providerLanding()
        val detail = providerDetail()
        val tracking = trackingService()

        integrations shouldContain "DefaultSkinPageTitle("
        providers shouldContain "DefaultSkinPageTitle("
        detail shouldContain "DefaultSkinPageTitle("
        tracking shouldContain "DefaultSkinPageTitle("
        integrations shouldNotContain "TopAppBar("
        providers shouldNotContain "TopAppBar("
        detail shouldNotContain "TopAppBar("
        tracking shouldNotContain "TopAppBar("
        tracking shouldNotContain "ListItem("
    }

    @Test
    fun `provider landing keeps approved selectors and hides rejected global acquisition`() {
        val providers = providerLanding()

        providers shouldNotContain "SettingsTsuzukiTorrentAcquisitionScreen()"
        providers shouldNotContain "tsuzuki_provider_acquisition_title"
        providers shouldContain "tsuzuki_providers_installed"
        providers shouldContain "tsuzuki_providers_discover"
        providers shouldContain "tsuzuki_providers_repositories"
    }

    @Test
    fun `provider landing does not surface metadata integration registrations`() {
        val providers = providerLanding()

        providers shouldContain "filter { it.descriptor.origin is ProviderOrigin.Repository }"
        providers shouldNotContain "builtinIntegrationProviderRegistry"
        providers shouldContain "SettingsTsuzukiProviderDetailDefaultSkinScreen("
        readSettingsSource("SettingsMainScreen.kt") shouldContain "SettingsTsuzukiProvidersDefaultSkinScreen()"
    }

    @Test
    fun `tracking Provider recovery stays on the promoted Provider surface`() {
        val tracking = trackingService()

        tracking shouldContain "SettingsTsuzukiProvidersDefaultSkinScreen()"
        tracking shouldNotContain "SettingsTsuzukiProvidersScreen()"
    }

    @Test
    fun `search empty state stays owned by metadata integrations`() {
        val search = readSource("search/TsuzukiSearchScreen.kt")

        search shouldContain "onOpenIntegrations"
        search shouldContain "tsuzuki_search_configure_integrations"
        search shouldNotContain "tsuzuki_search_configure_providers"
    }

    @Test
    fun `search compact cards retain a backed fit book cover without nested interaction`() {
        val search = readSource("search/TsuzukiSearchScreen.kt")
        val compactCard = search.substringAfter("private fun SearchCompactCard(").substringBefore("\n@OptIn")
        val cover = compactCard.substringAfter("MangaCover.Book(").substringBefore("\n        )")

        compactCard shouldContain ".width(148.dp)"
        compactCard shouldContain "MangaCover.Book("
        compactCard shouldContain "contentDescription = item.title"
        compactCard shouldContain "ContentScale.Fit"
        compactCard shouldNotContain "ContentScale.Crop"
        compactCard shouldContain ".background(color = colors.surfaceMuted, shape = MaterialTheme.shapes.extraSmall)"
        cover shouldNotContain "onClick ="
        cover shouldNotContain ".clickable("
        readMangaCoverSource() shouldContain "Book(2f / 3f)"
    }

    private fun providerLanding(): String = readSettingsSource("SettingsTsuzukiProvidersDefaultSkinScreen.kt")

    private fun providerDetail(): String = readSettingsSource("SettingsTsuzukiProviderDetailDefaultSkinScreen.kt")

    private fun trackingService(): String = readSettingsSource("SettingsTsuzukiTrackingServiceScreen.kt")

    private fun sourceBlockStartingAt(source: String, marker: String): String {
        val markerStart = source.indexOf(marker)
        require(markerStart >= 0) { "Source block marker was not found: $marker" }
        val openingBrace = source.indexOf('{', markerStart + marker.length)
        require(openingBrace >= 0) { "Source block opening brace was not found: $marker" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        error("Source block closing brace was not found: $marker")
    }

    private fun readSettingsComponentSource(fileName: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/more/settings/components/$fileName"),
        Path.of("app/src/main/java/eu/kanade/presentation/more/settings/components/$fileName"),
    )

    private fun readSource(relativePath: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
        Path.of("app/src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
    )

    private fun readMangaCoverSource(): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/manga/components/MangaCover.kt"),
        Path.of("app/src/main/java/eu/kanade/presentation/manga/components/MangaCover.kt"),
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

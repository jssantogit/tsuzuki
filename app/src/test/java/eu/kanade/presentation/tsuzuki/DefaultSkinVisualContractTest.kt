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
    fun `settings routes keep tracking with account and library with general reading`() {
        val settings = readSettingsSource("SettingsMainScreen.kt")
        val sections = settings.substringAfter("private val sections = listOf(")
        val account = sections.substringAfter("key = \"account\"").substringBefore("key = \"general\"")
        val general = sections.substringAfter("key = \"general\"").substringBefore("key = \"content\"")

        account shouldContain "SettingsTsuzukiAccountScreen"
        account shouldContain "SettingsTrackingScreen"
        (account.indexOf("SettingsTsuzukiAccountScreen") < account.indexOf("SettingsTrackingScreen")) shouldBe true

        general shouldContain "SettingsTsuzukiPersonalizationsScreen"
        general shouldContain "SettingsTsuzukiReadingHubScreen"
        general shouldContain "SettingsLibraryScreen"
        listOf(
            "SettingsTsuzukiPersonalizationsScreen",
            "SettingsTsuzukiReadingHubScreen",
            "SettingsLibraryScreen",
        ).zipWithNext().forEach { (first, second) ->
            (general.indexOf(first) < general.indexOf(second)) shouldBe true
        }
    }

    @Test
    fun `personalizations link only to real localized destinations`() {
        val source = readSettingsSource("SettingsTsuzukiPersonalizationsScreen.kt")

        source shouldContain "MR.strings.tsuzuki_visual_preset_summary"
        source shouldContain "SettingsTsuzukiVisualPresetScreen"
        source shouldContain "SettingsAppearanceScreen"
        source shouldContain "CollectionsScreen()"
        source shouldNotContain "Tsuzuki Default Skin"
        source shouldNotContain "TsuzukiHomeScreen"
    }

    @Test
    fun `appearance theme mode remains a localized preference and theme previews keep selection chrome`() {
        val appearance = readSettingsSource("SettingsAppearanceScreen.kt")
            .substringAfter("private fun getThemeGroup(")
            .substringBefore("private fun getDisplayGroup(")
        val preview = readSettingsWidgetSource("AppThemePreferenceWidget.kt")
        val themesList = sourceBlockStartingAt(preview, "private fun AppThemesList(")
        val previewItem = sourceBlockStartingAt(preview, "fun AppThemePreviewItem(")
        val previewItemSignature = preview
            .substringAfter("fun AppThemePreviewItem(")
            .substringBefore(") {")
        val previewBorderStart = previewItem.indexOf(".border(")
        val previewBorderEnd = previewItem.indexOf(".padding(4.dp)", previewBorderStart)
        val previewBorder = previewItem.substring(previewBorderStart, previewBorderEnd)
        val baseSettings = readSettingsResource("base/strings_default_skin_settings.xml")
        val portugueseSettings = readSettingsResource("pt-rBR/strings_default_skin_settings.xml")

        appearance shouldContain "Preference.PreferenceItem.ListPreference("
        appearance shouldContain "preference = themeModePref"
        appearance shouldContain "title = stringResource(MR.strings.tsuzuki_theme_mode_title)"
        appearance shouldContain "entries = linkedMapOf("
        listOf(
            "ThemeMode.SYSTEM to stringResource(MR.strings.theme_system)",
            "ThemeMode.LIGHT to stringResource(MR.strings.theme_light)",
            "ThemeMode.DARK to stringResource(MR.strings.theme_dark)",
        ).zipWithNext().forEach { (first, second) ->
            (appearance.indexOf(first) < appearance.indexOf(second)) shouldBe true
        }
        appearance shouldContain "setAppCompatDelegateThemeMode(it)"
        appearance shouldContain "AppThemePreferenceWidget("
        appearance shouldContain "title = stringResource(MR.strings.pref_app_theme)"
        appearance shouldContain "ActivityCompat.recreate(it)"
        appearance shouldNotContain "AppThemeModePreferenceWidget("
        baseSettings shouldContain "<string name=\"tsuzuki_theme_mode_title\">Theme mode</string>"
        portugueseSettings shouldContain "<string name=\"tsuzuki_theme_mode_title\">Modo do tema</string>"

        val selectionColorsIndex = themesList.indexOf("val selectionColors = defaultSkinColors()")
        val previewThemeIndex = themesList.indexOf("TachiyomiTheme(")
        (selectionColorsIndex >= 0 && selectionColorsIndex < previewThemeIndex) shouldBe true
        themesList shouldContain "selectionColor = selectionColors.accent"
        themesList shouldContain "unselectedBorderColor = selectionColors.outline"
        previewItemSignature shouldContain "selectionColor: Color"
        previewItemSignature shouldContain "unselectedBorderColor: Color"
        previewBorder shouldContain "selectionColor"
        previewBorder shouldContain "unselectedBorderColor"
        previewBorder shouldNotContain "MaterialTheme.colorScheme.primary"
        previewItem shouldContain "tint = selectionColor"
        previewItem shouldContain "MaterialTheme.colorScheme.primary"
        previewItem shouldContain "MaterialTheme.colorScheme.tertiary"
    }

    @Test
    fun `settings search uses promoted grouped controls and preserves search behavior`() {
        val search = readSettingsSource("SettingsSearchScreen.kt")
        val searchField = sourceBlockStartingAt(search, "Surface(")
        val searchableResults = sourceBlockStartingAt(search, "private fun SearchResult(")

        search shouldContain "SettingsCanvas {"
        search shouldContain "SettingsPageHeader("
        search shouldContain "BasicTextField("
        search shouldContain "DefaultSkinShape.Control"
        search shouldContain "rememberTextFieldState()"
        search shouldContain "focusRequester.requestFocus()"
        search shouldContain "runOnEnterKeyPressed(action = focusManager::clearFocus)"
        search shouldContain "softKeyboardController?.hide()"
        search shouldContain "SearchableSettings.highlightKey = result.highlightKey"
        search shouldContain "navigator.replace(result.route)"
        search shouldContain "getIndex()"
        search shouldContain "settingScreens"
        search shouldContain "getLocalizedBreadcrumb("
        search shouldContain "if (isLtr)"
        search shouldContain "\"\$node < \$path\""
        search shouldNotContain "Scaffold("
        search shouldNotContain "TopAppBar("
        search shouldNotContain "EmptyScreen("
        searchField shouldContain "BasicTextField("
        searchField shouldContain "textFieldState.text.isEmpty()"
        searchableResults shouldContain "filterNot { it.second is Preference.PreferenceItem.InfoPreference }"
        searchableResults shouldContain ".take(10)"
        searchableResults shouldContain "item(key = \"settings_search_results\")"
        searchableResults shouldContain "SettingsGroup {"
        searchableResults.split("item(key = \"settings_search_results\")").size shouldBe 2
        searchableResults.split("SettingsGroup {").size shouldBe 2
        searchableResults shouldNotContain "groupBy"
        searchableResults shouldContain "SettingsNavigationRow("
    }

    @Test
    fun `library advanced filters use Default Skin controls instead of stock filter chips`() {
        val library = readSource("library/CanonicalLibraryScreen.kt")

        library shouldContain "DefaultSkinChoiceChip("
        library shouldNotContain "FilterChip("
        library shouldNotContain "SuggestionChip("
    }

    @Test
    fun `library search mode keeps its own inline field and selectable status rail`() {
        val library = readSource("library/CanonicalLibraryScreen.kt")
        val header = sourceBlockStartingAt(library, "private fun CanonicalLibraryHeader(")
        val statusRail = sourceBlockStartingAt(library, "private fun CanonicalLibraryStatusFilters(")
        val clearSearch = sourceBlockStartingAt(header, "if (query.isNotEmpty())")

        header shouldContain "BackHandler(enabled = query != null) { onQueryChange(null) }"
        header shouldContain "if (query == null)"
        header shouldContain "DefaultSkinPageTitle(title, modifier = Modifier.weight(1f))"
        header shouldContain "val focusRequester = remember { FocusRequester() }"
        header shouldContain "LaunchedEffect(Unit) { focusRequester.requestFocus() }"
        header shouldContain "BasicTextField("
        header shouldContain "singleLine = true"
        header shouldContain "value = query"
        header shouldContain "onValueChange = onQueryChange"
        header shouldContain "KeyboardOptions(imeAction = ImeAction.Search)"
        header shouldContain "cursorBrush = SolidColor(colors.accent)"
        header shouldContain "val searchLabel = stringResource(MR.strings.tsuzuki_library_search)"
        header shouldContain "contentDescription = searchLabel"
        header shouldContain "shape = DefaultSkinShape.Control"
        header shouldContain "colors.outline"
        header shouldContain "color = colors.surface"
        header shouldContain "color = colors.foreground"
        header shouldContain "color = colors.secondary"
        header shouldContain "maxLines = 1"
        header shouldContain "overflow = TextOverflow.Ellipsis"
        header shouldContain "if (query.isNotEmpty())"
        header shouldContain "contentDescription = stringResource(MR.strings.tsuzuki_library_clear_search)"
        header shouldContain "onClick = { onQueryChange(\"\") }"
        header shouldContain "IconButton(onClick = onShowFilters)"
        header shouldNotContain "KeyboardActions("
        header shouldNotContain "DefaultSkinTextField("
        header shouldNotContain "DefaultSkinTextAction("
        clearSearch shouldContain "IconButton(onClick = { onQueryChange(\"\") })"
        clearSearch shouldNotContain "onQueryChange(null)"

        library shouldContain "val colors = defaultSkinColors()"
        library shouldContain "val advancedFilterCount = successState?.filters?.let { filters ->"
        library shouldContain "if (advancedFilterCount > 0)"
        library shouldContain "CanonicalLibraryFilterSheet("
        library shouldContain "CanonicalLibraryActiveFilters("
        library shouldNotContain "SearchToolbar("
        library shouldNotContain "TopAppBar("
        library shouldContain "onQueryChange = onSearchQueryChange"
        library shouldContain "searchMode = successState?.searchQuery != null"
        statusRail shouldContain "listOf<LibraryStatus?>(null) + LibraryStatus.entries"
        statusRail shouldContain ".horizontalScroll(rememberScrollState())"
        statusRail shouldContain "top = if (searchMode) DefaultSkinSpacing.Row else 0.dp"
        statusRail shouldContain "style = if (searchMode) {"
        statusRail shouldContain "selected = selected"
        statusRail shouldContain "role = Role.Tab"
    }

    @Test
    fun `integrations and Provider family use promoted page composition instead of stock app bars`() {
        val integrations = readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt")
        val providers = providerLanding()
        val detail = providerDetail()
        val tracking = trackingService()

        integrations shouldContain "SettingsPageHeader("
        integrations shouldNotContain "DefaultSkinPageTitle("
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
    fun `integration settings use grouped rows and independent skin toggles`() {
        val source = readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt")
        val landing = source.substringAfter("class SettingsTsuzukiIntegrationsScreen")
            .substringBefore("class SettingsTsuzukiIntegrationDetailScreen")
        val detail = source.substringAfter("class SettingsTsuzukiIntegrationDetailScreen")
            .substringBefore("@Composable\nprivate fun MalClientIdConfiguration")
        val integrationRow = sourceBlockStartingAt(source, "private fun IntegrationSettingRow(")
        val toggleRow = sourceBlockStartingAt(source, "private fun IntegrationToggleRow(")
        val switchControl = sourceBlockStartingAt(source, "private fun IntegrationSwitch(")
        val actionRow = sourceBlockStartingAt(source, "private fun IntegrationActionRow(")

        landing shouldContain "SettingsCanvas {"
        landing shouldContain "SettingsPageHeader("
        landing shouldContain "IntegrationCategory.entries.forEach { category ->"
        landing shouldContain "SettingsGroup(title = stringResource(category.labelRes()))"
        landing shouldContain "categoryItems.forEachIndexed"
        landing shouldContain "SettingsGroupDivider()"
        landing.split("SettingsGroup(title = stringResource(category.labelRes()))").size shouldBe 2

        detail shouldContain "SettingsCanvas {"
        detail shouldContain "SettingsPageHeader("
        detail shouldContain "SettingsGroup("
        detail shouldNotContain "IntegrationHeader("

        integrationRow shouldContain ".weight(1f)"
        integrationRow shouldContain ".clickable(role = Role.Button, onClick = onOpen)"
        integrationRow shouldContain "IntegrationSwitch("
        integrationRow shouldContain "item.id.value == \"mal\" && !malClientIdConfigured"
        integrationRow shouldContain "onCheckedChange = onEnabledChange"
        integrationRow shouldNotContain "DefaultSkinCard("
        integrationRow shouldNotContain ".toggleable("
        integrationRow.split(".clickable(").size shouldBe 2

        toggleRow shouldContain "IntegrationSwitch("
        toggleRow shouldNotContain "DefaultSkinCard("
        toggleRow shouldNotContain ".clickable("

        switchControl shouldContain ".toggleable("
        switchControl shouldContain "value = checked"
        switchControl shouldContain "role = Role.Switch"
        switchControl shouldContain "onValueChange = onCheckedChange"
        switchControl shouldContain "SettingsPreferenceSwitchIndicator(checked = checked, enabled = enabled)"
        switchControl.split(".toggleable(").size shouldBe 2

        actionRow shouldContain "SettingsNavigationRow("
        actionRow shouldNotContain "trailing ="

        source shouldNotContain "DefaultSkinCard("
        source shouldNotContain "androidx.compose.material3.Switch"
        source shouldContain "if (!configured && item.enabled)"
        source shouldContain "screenModel.setEnabled(item.id, false)"
        source shouldContain "screenModel.setEnabled(item.id, it)"
        source shouldContain "screenModel.setCapabilityEnabled(item.id, capability, it)"
        source shouldContain "item.id.value != \"mal\" || malClientIdConfigured || item.enabled"
        source shouldContain "item.restrictedCapabilities.isNotEmpty()"
        source shouldContain "item.supportsTracking && item.legacyTrackerId != null"
        source shouldContain "tracker as? MyAnimeList"
        source shouldContain "tracker as? Shikimori"
        source shouldContain "tracker as? Hikka"
        source shouldContain "PasswordVisualTransformation()"
        source shouldContain "DefaultSkinTextField("
        source shouldContain "item.configurableCapabilities.isNotEmpty()"
        source shouldContain "SettingsTsuzukiTrackingBehaviorScreen"
        source shouldContain "SettingsTsuzukiTrackingServiceScreen(item.legacyTrackerId)"
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

    private fun readSettingsWidgetSource(fileName: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/more/settings/widget/$fileName"),
        Path.of("app/src/main/java/eu/kanade/presentation/more/settings/widget/$fileName"),
    )

    private fun readSettingsResource(fileName: String): String = readFirst(
        Path.of("../i18n/src/commonMain/moko-resources/$fileName"),
        Path.of("i18n/src/commonMain/moko-resources/$fileName"),
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

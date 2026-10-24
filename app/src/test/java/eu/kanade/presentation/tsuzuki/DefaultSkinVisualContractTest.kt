package eu.kanade.presentation.tsuzuki

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.tsuzuki.components.DefaultSkinColors
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.defaultSkinPalette
import eu.kanade.presentation.tsuzuki.home.homeHeroHeight
import eu.kanade.presentation.tsuzuki.home.homeModuleTopSpacing
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.model.metadataStartYear
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinVisualContractTest {

    @Test
    fun `shared Default Skin separates neutral interface roles from brand Paper`() {
        defaultSkinPalette(isLight = true) shouldBe DefaultSkinColors(
            canvas = Color(0xFFF7F7F8),
            surface = Color(0xFFFFFFFF),
            surfaceMuted = Color(0xFFE8EAED),
            foreground = Color(0xFF17181A),
            secondary = Color(0xFF50565C),
            outline = Color(0xFFD6D9DC),
            accent = Color(0xFF17181A),
            onAccent = Color(0xFFFFFFFF),
        )
        defaultSkinPalette(isLight = false) shouldBe DefaultSkinColors(
            canvas = Color(0xFF0B0C0D),
            surface = Color(0xFF1C1E1F),
            surfaceMuted = Color(0xFF2B2D2E),
            foreground = Color(0xFFF5F5F5),
            secondary = Color(0xFFADB2B6),
            outline = Color(0xFF303234),
            accent = Color(0xFFF5F5F5),
            onAccent = Color(0xFF0B0C0D),
        )
    }

    @Test
    fun `active palette responds to selected preset without losing neutral default`() {
        val light = lightColorScheme(
            primary = Color(0xFF005A9C),
            onPrimary = Color.White,
            background = Color(0xFFF6F4EF),
        )
        defaultSkinPalette(isLight = true, appTheme = AppTheme.DEFAULT, scheme = light) shouldBe
            defaultSkinPalette(isLight = true)
        val selected = defaultSkinPalette(isLight = true, appTheme = AppTheme.MONET, scheme = light)
        selected.accent shouldBe light.primary
        selected.onAccent shouldBe light.onPrimary
        selected.canvas shouldBe light.background

        val dark = darkColorScheme(
            primary = Color(0xFFC3B1FF),
            onPrimary = Color.Black,
            background = Color(0xFF181328),
        )
        defaultSkinPalette(isLight = false, appTheme = AppTheme.CATPPUCCIN, scheme = dark).accent shouldBe
            dark.primary
        defaultSkinPalette(isLight = false, appTheme = AppTheme.CATPPUCCIN, scheme = dark, amoled = true)
            .canvas shouldBe Color.Black
    }

    @Test
    fun `password visibility and filter chips center labels in compact touch targets`() {
        val fields = readSource("components/DefaultSkinPrimitives.kt")
        val listBuilder = readSource("collections/ListBuilderScreen.kt")
        val textAction = fields.substringAfter("fun DefaultSkinTextAction(")
            .substringBefore("fun DefaultSkinChoiceChip(")
        val chip = fields.substringAfter("fun DefaultSkinChoiceChip(")
            .substringBefore("fun DefaultSkinTag(")
        textAction shouldContain "contentAlignment = Alignment.Center"
        textAction shouldContain ".heightIn(min = 48.dp)"
        chip shouldContain ".heightIn(min = 48.dp)"
        chip shouldContain ".heightIn(min = 32.dp)"
        listBuilder shouldContain "DefaultSkinChoiceChip("
    }

    @Test
    fun `editorial metadata uses compact labels and values instead of page headings`() {
        val source = readSource("detail/CanonicalDetailsHero.kt")
        val section = source.substringAfter("private fun EditorialSection(")
            .substringBefore("private fun editorialFormatLabel(")
        section shouldContain "MaterialTheme.typography.label"
        section shouldContain "MaterialTheme.typography.bodyMedium"
        section shouldNotContain "MaterialTheme.typography.titleLarge"
        section shouldNotContain "MaterialTheme.typography.bodyLarge"
    }

    @Test
    fun `selected chips center their text in compact touch targets`() {
        val source = readSource("components/DefaultSkinPrimitives.kt")
        val chip = source.substringAfter("fun DefaultSkinChoiceChip(")
            .substringBefore("fun DefaultSkinTag(")
        chip shouldContain "contentAlignment = Alignment.Center"
        chip shouldContain ".heightIn(min = 48.dp)"
        chip shouldContain ".heightIn(min = 32.dp)"
    }

    @Test
    fun `advanced filters reuse the live provider result preview instead of showing a blind form`() {
        val screen = readSource("collections/ListBuilderScreen.kt")
        screen shouldContain "item(key = \"advanced_live_preview\")"
        screen shouldContain "ListBuilderPreviewContent(editor, previewState)"
        screen shouldContain "onPreviewDraft(draft)"
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
    fun `Home hero is cinematic responsive and offers one compact details action`() {
        val home = readSource("home/TsuzukiHomeScreen.kt")
        val carousel = sourceBlockStartingAt(home, "private fun HomeHeroCarousel(")
        val homeHero = sourceBlockStartingAt(home, "private fun HomeHero(")
        val button = readSource("components/DefaultSkinPrimitives.kt")

        carousel shouldContain "homeHeroHeight("
        carousel shouldContain "configuration.screenHeightDp"
        carousel shouldNotContain ".aspectRatio(HERO_ASPECT_RATIO)"
        homeHero shouldContain "DefaultSkinPrimaryButton("
        (homeHero.split("DefaultSkinPrimaryButton(").size - 1) shouldBe 1
        homeHero shouldContain "Modifier.widthIn(min = 200.dp, max = 240.dp)"
        homeHero shouldContain "MR.strings.tsuzuki_home_view_work"
        homeHero shouldContain "catalogFormatLabel(item.format)"
        homeHero shouldContain "metadataStartYear(item.startDate)"
        homeHero shouldContain "onDetails"
        homeHero shouldContain "TextAlign.Center"
        homeHero shouldNotContain "onRead"
        homeHero shouldNotContain "item.synopsis"
        homeHero shouldNotContain "item.genres"
        homeHero shouldNotContain "item.status"
        home shouldNotContain "onHeroRead"
        home shouldNotContain "DefaultSkinHomeHeroAction"
        button shouldContain "minimumHeight: Dp = 52.dp"
    }

    @Test
    fun `canonical details visual lead is sparse and places real content below artwork`() {
        val hero = readSource("detail/CanonicalDetailsHero.kt")
        val screen = readSource("detail/CanonicalTitleDetailScreen.kt")
        val atmosphere = sourceBlockStartingAt(hero, "canonical_details_hero_visual")
        val editorial = sourceBlockStartingAt(hero, "internal fun DetailsEditorial(")
        val artworkIndex = atmosphere.indexOf("MangaCoverFrame(")
        val titleIndex = atmosphere.indexOf("state.title.displayTitle")

        hero shouldNotContain "MangaCover.Square("
        hero shouldNotContain "MangaCover.Book("
        hero shouldContain "MangaCoverFrame("
        hero shouldContain ".matchParentSize()"
        hero shouldContain ".clipToBounds()"
        hero shouldContain "canonicalDetailsHeroFadeStops(background)"
        hero shouldContain "if (hasArtwork)"
        hero shouldContain "Modifier.heightIn(min = artworkLead)"
        hero shouldContain "configuration.screenHeightDp"
        hero shouldContain "configuration.fontScale"
        hero shouldContain "WindowInsets.navigationBars"
        hero shouldContain "align(Alignment.BottomCenter)"
        hero shouldContain "maxLines = 2"
        hero shouldContain "overflow = TextOverflow.Ellipsis"
        hero shouldNotContain ".height(toolbarLeadHeight + artworkLead)"
        hero shouldNotContain "artworkLead * 0.68f"
        (artworkIndex >= 0 && titleIndex > artworkIndex) shouldBe true
        atmosphere shouldContain "toolbarLeadHeight"
        atmosphere shouldContain "Brush.verticalGradient("
        atmosphere shouldNotContain "MangaCover.Square("
        atmosphere shouldNotContain "if (state.tsuzukiRating"
        atmosphere shouldNotContain "editorialStatusLabel(state.editorialStatus)"
        atmosphere shouldContain "detailsHeroGenres(state.genres)"
        (hero.indexOf("if (state.tsuzukiRating") > hero.indexOf("state.title.displayTitle")) shouldBe true
        hero shouldContain "DefaultSkinPrimaryButton("
        hero shouldContain "state.refreshError"
        (
            hero.indexOf("editorialStatusLabel(state.editorialStatus)") >
                hero.indexOf("if (state.tsuzukiRating")
            ) shouldBe
            true
        (hero.indexOf("state.genres") < hero.indexOf("if (state.tsuzukiRating")) shouldBe true
        editorial shouldNotContain "state.description"
        editorial shouldContain "editorialGenres(state.genres)"
        editorial shouldContain "state.author"
        editorial shouldContain "state.artists"
        editorial shouldContain "state.startDate"
        editorial shouldContain "state.tags"
        editorial shouldNotContain "stringResource(MR.strings.tsuzuki_details_synopsis)"
        screen shouldContain "defaultSkinColors().canvas"
        screen shouldNotContain "luminance()"
        hero shouldNotContain "Spacer(Modifier.weight(1f))"
        hero shouldNotContain "Arrangement.End"
    }

    @Test
    fun `full chapter browsing uses a dedicated full width list without Material vertical slider`() {
        val screen = readSource("detail/CanonicalTitleDetailScreen.kt")
        val full = screen.substringAfter("CanonicalDetailsLowerMode.FULL -> {")
            .substringBefore("DetailsToolbar(")
        screen shouldNotContain "VerticalSlider("
        screen shouldNotContain "CHAPTER_SCRUBBER_LANE_WIDTH"
        screen shouldContain "FULL_CHAPTER_FIRST_ITEM = 1"
        full shouldContain "DetailsChapterRow("
        full shouldContain "Modifier.padding(horizontal = 16.dp)"
        screen shouldContain "if (mode != CanonicalDetailsLowerMode.FULL)"
        screen shouldContain "if (mode == CanonicalDetailsLowerMode.FULL)"
    }

    @Test
    fun `MangaCover ratio wrappers delegate to a caller bounds renderer`() {
        val source = readMangaCoverSource()
        val frameRenderer = sourceBlockStartingAt(source, "internal fun MangaCoverFrame(")
        val ratioWrapper = sourceBlockStartingAt(source, "operator fun invoke(")

        ratioWrapper shouldContain "modifier = modifier.aspectRatio(ratio)"
        frameRenderer shouldContain ".clip(shape)"
        frameRenderer shouldNotContain "aspectRatio("
    }

    @Test
    fun `canonical chapter actions and Home hero controls stay on the Default Skin`() {
        val chapters = readSource("detail/CanonicalDetailsChapterComponents.kt")
        val home = readSource("home/TsuzukiHomeScreen.kt")
        val homeHero = home.substringAfter("private fun HomeHero(")
            .substringBefore("\nprivate fun ContinueReadingSection")

        chapters shouldContain "DefaultSkinTextAction("
        chapters shouldNotContain "TextButton("
        homeHero shouldContain "DefaultSkinPrimaryButton("
        homeHero shouldContain "repeat(itemCount)"
        homeHero shouldNotContain "Modifier.weight(1f)"
        home shouldNotContain "home_safe_top"
    }

    @Test
    fun `Home year uses only a valid factual catalog start date`() {
        metadataStartYear("2020") shouldBe "2020"
        metadataStartYear("2020-06-12") shouldBe "2020"
        metadataStartYear(null) shouldBe null
        metadataStartYear("unknown") shouldBe null
        metadataStartYear("2020-13-01") shouldBe null
    }

    @Test
    fun `Home keeps its first post-hero gap compact and ordinary module gaps intact`() {
        homeModuleTopSpacing(
            previousModuleExists = false,
            heroPrecedesModule = true,
        ) shouldBe 8.dp
        homeModuleTopSpacing(
            previousModuleExists = true,
            heroPrecedesModule = false,
        ) shouldBe DefaultSkinSpacing.Section
        homeModuleTopSpacing(
            previousModuleExists = false,
            heroPrecedesModule = false,
        ) shouldBe 0.dp
        val standardViewport = homeHeroHeight(
            screenHeightDp = 800,
            statusBarInset = 24.dp,
            navigationBarInset = 24.dp,
            fontScale = 1f,
        )
        (standardViewport.value in 480f..500f) shouldBe true
        val largeFontViewport = homeHeroHeight(
            screenHeightDp = 800,
            statusBarInset = 24.dp,
            navigationBarInset = 24.dp,
            fontScale = 1.8f,
        )
        (largeFontViewport >= standardViewport) shouldBe true
        (largeFontViewport.value <= 640f) shouldBe true
    }

    @Test
    fun `settings routes keep approved destinations in their visual macrogroups`() {
        val settings = readSettingsSource("SettingsMainScreen.kt")
        val groups = settings.substringAfter("private val settingsGroups = listOf(")
        val account = groups.substringAfter("key = \"account\"").substringBefore("key = \"experience\"")
        val experience = groups.substringAfter("key = \"experience\"").substringBefore("key = \"content\"")
        listOf("account", "experience", "content", "data", "system", "about").forEach { key ->
            groups shouldContain "key = \"$key\""
        }
        groups.split("SettingsMacroGroup(").size shouldBe 7

        account shouldContain "SettingsTsuzukiAccountScreen"
        account shouldContain "SettingsTrackingScreen"
        (account.indexOf("SettingsTsuzukiAccountScreen") < account.indexOf("SettingsTrackingScreen")) shouldBe true

        experience shouldContain "SettingsTsuzukiPersonalizationsScreen"
        experience shouldContain "SettingsTsuzukiReadingHubScreen"
        experience shouldContain "SettingsLibraryScreen"
        listOf(
            "SettingsTsuzukiPersonalizationsScreen",
            "SettingsTsuzukiReadingHubScreen",
            "SettingsLibraryScreen",
        ).zipWithNext().forEach { (first, second) ->
            (experience.indexOf(first) < experience.indexOf(second)) shouldBe true
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
        val compactPreview = sourceBlockStartingAt(preview, "internal fun AppThemePalettePreview(")
        val previewSample = sourceBlockStartingAt(preview, "private fun AppThemePaletteSample(")
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
        appearance shouldNotContain "AppThemePreferenceWidget("
        val preset = readSettingsSource("SettingsTsuzukiVisualPresetScreen.kt")
        preset shouldContain "AppTheme.entries"
        preset shouldContain "AppThemePalettePreview("
        preset shouldContain "TachiyomiTheme("
        preset shouldContain "appThemePref.set(theme)"
        preset shouldContain "presetDescription(theme)"
        preset shouldNotContain "AppThemePreferenceWidget("
        appearance shouldNotContain "AppThemeModePreferenceWidget("
        baseSettings shouldContain "<string name=\"tsuzuki_theme_mode_title\">Theme mode</string>"
        portugueseSettings shouldContain "<string name=\"tsuzuki_theme_mode_title\">Modo do tema</string>"

        val selectionColorsIndex = themesList.indexOf("val selectionColors = defaultSkinColors()")
        val previewThemeIndex = themesList.indexOf("TachiyomiTheme(")
        (selectionColorsIndex >= 0 && selectionColorsIndex < previewThemeIndex) shouldBe true
        themesList shouldContain "selectionColor = selectionColors.accent"
        themesList shouldContain "unselectedBorderColor = selectionColors.outline"
        themesList shouldContain "AppThemePalettePreview("
        compactPreview shouldContain "selectionColor"
        compactPreview shouldContain "unselectedBorderColor"
        compactPreview shouldContain "Role.RadioButton"
        compactPreview shouldContain "contentDescription = themeName"
        compactPreview shouldContain "this.selected = selected"
        compactPreview shouldContain "contentDescription = null"
        compactPreview shouldNotContain "aspectRatio(9f / 16f)"
        compactPreview shouldNotContain "MangaCover.Book"
        previewSample shouldContain "MaterialTheme.colorScheme.primary"
        previewSample shouldContain "MaterialTheme.colorScheme.surfaceVariant"
        previewSample shouldContain "MaterialTheme.colorScheme.primaryContainer"
        previewSample shouldContain "MaterialTheme.colorScheme.surfaceContainerHigh"
        previewSample shouldNotContain "AppBar"
        preview shouldNotContain "MaterialTheme.colorScheme.primary.copy(alpha"
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
        providers shouldContain "SettingsPageHeader("
        detail shouldContain "SettingsPageHeader("
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
        val integrationRow = sourceBlockStartingAt(source, "internal fun IntegrationSettingRow(")
        val toggleRow = sourceBlockStartingAt(source, "internal fun IntegrationToggleRow(")
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
        integrationRow shouldContain "integrationRootStatus("
        integrationRow shouldContain "IntegrationBrandIcon("
        integrationRow shouldNotContain "IntegrationSwitch("
        integrationRow shouldNotContain "onEnabledChange"
        integrationRow shouldNotContain "DefaultSkinCard("
        integrationRow shouldNotContain ".toggleable("
        integrationRow shouldContain "IntegrationRootStatus.NOT_CONFIGURED"
        integrationRow shouldContain "MR.strings.tsuzuki_integration_status_not_configured"
        integrationRow shouldContain "(tracker as? MyAnimeList)?.hasClientId() == true"
        integrationRow shouldContain "(tracker as? Shikimori)?.hasApplicationCredentials() == true"
        integrationRow shouldContain "(tracker as? Hikka)?.hasApplicationCredentials() == true"
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
    fun `auth forms share one page gutter and keep accessible field owners`() {
        val source = readSource("entry/TsuzukiEntryScreen.kt")
        val auth = source.substringAfter("private fun AuthContent(")
            .substringBefore("private fun TsuzukiEntryPage.titleResource()")
        val backButton = sourceBlockStartingAt(source, "private fun AuthBackButton(")
        val footerMarker = "if (page == TsuzukiEntryPage.LOGIN || page == TsuzukiEntryPage.REGISTER)"
        val footerStart = auth.lastIndexOf(footerMarker)
        val supportingFooter = sourceBlockStartingAt(
            auth.substring(footerStart),
            footerMarker,
        )
        val formField = readSource("components/DefaultSkinPrimitives.kt")
            .substringAfter("fun DefaultSkinFormField(")
            .substringBefore("@Composable\nfun DefaultSkinCard(")

        auth shouldContain "DefaultSkinSpacing.PageHorizontal"
        auth shouldContain "DefaultSkinFormField("
        auth shouldContain "Modifier.heightIn(min = 48.dp)"
        auth shouldContain "DefaultSkinTextAction("
        auth shouldContain "onLogin(email, password)"
        auth shouldContain "onRegister(email, password)"
        auth shouldContain "onSendRecovery(email)"
        auth shouldContain "onUpdatePassword(password, confirmation)"
        auth shouldContain "TsuzukiEntryError.PASSWORDS_DO_NOT_MATCH"
        auth shouldNotContain "OutlinedTextField("
        auth shouldNotContain "padding(horizontal = 24.dp)"
        auth shouldNotContain "padding(start = -"
        backButton shouldContain "Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick)"
        backButton shouldContain "contentAlignment = Alignment.CenterStart"
        supportingFooter shouldContain "modifier = Modifier.alignByBaseline()"
        supportingFooter shouldContain "Modifier.heightIn(min = 48.dp).alignByBaseline()"
        formField shouldContain "BasicTextField("
        formField shouldContain "PasswordVisualTransformation()"
        formField shouldContain "contentDescription = label"
        formField shouldContain "this.error(error)"
        formField shouldContain "Modifier.heightIn(min = 48.dp)"
        formField shouldNotContain "OutlinedTextField("
    }

    @Test
    fun `provider surfaces use Settings shells and separate navigation from toggles`() {
        val root = providerLanding()
        val detail = providerDetail()
        val tabs = sourceBlockStartingAt(root, "private fun ProviderTabs(")
        val installedRow = root.substringAfter("private fun InstalledProviderRow(")
            .substringBefore("@Composable\nprivate fun DiscoverProviderCards(")

        root shouldContain "SettingsCanvas {"
        root shouldContain "SettingsPageHeader("
        root shouldContain "navigateUp = navigator::pop"
        root shouldContain "SettingsGroup"
        root shouldContain "SettingsGroupDivider("
        root shouldContain "SettingsPreferenceSwitchIndicator("
        root shouldContain "Role.Switch"
        root shouldContain "ProviderLifecycleStatus.BLOCKED"
        root shouldContain "ProviderLifecycleStatus.INVALID"
        root shouldContain "SettingsTsuzukiProviderDetailDefaultSkinScreen("
        root shouldContain "manager.install("
        root shouldContain "manager.rollback("
        root shouldContain "showRepositories = true"
        root shouldNotContain "androidx.compose.material3.Switch"
        root shouldNotContain "Switch("
        root shouldNotContain "DefaultSkinChoiceChip("
        root shouldNotContain "MR.strings.tsuzuki_navigation_back"
        tabs shouldContain ".selectableGroup()"
        tabs shouldContain "selected = selectedTab == index"
        tabs shouldContain "role = Role.Tab"
        tabs shouldContain "heightIn(min = 48.dp)"
        tabs shouldContain "if (selectedTab == index)"
        tabs shouldContain "HorizontalDivider(color = colors.foreground, thickness = 2.dp)"
        tabs shouldNotContain "DefaultSkinChoiceChip("
        installedRow shouldContain ".clickable(role = Role.Button, onClick = onOpen)"
        installedRow shouldContain "role = Role.Switch"
        installedRow shouldContain "contentDescription = descriptor.name"
        installedRow shouldContain "DefaultSkinTag(text = capability.id)"
        installedRow shouldNotContain "DefaultSkinChoiceChip("

        detail shouldContain "SettingsCanvas {"
        detail shouldContain "SettingsPageHeader("
        detail shouldContain "navigateUp = navigator::pop"
        detail shouldContain "SettingsGroup"
        detail shouldContain "SettingsGroupDivider("
        detail shouldContain "SettingsPreferenceSwitchIndicator("
        detail shouldContain "registry.setEnabled(providerId, enabled)"
        detail shouldContain "registry.setEnabledContentLanguages("
        detail shouldContain "if (next == descriptor.contentLanguages)"
        detail shouldContain "contentDescription = descriptor.name"
        detail shouldContain "contentDescription = language"
        detail shouldContain "ProviderSettingType.SECRET"
        detail shouldContain "descriptor.permissions.network"
        detail shouldContain "descriptor.permissions.browser"
        detail shouldNotContain "androidx.compose.material3.Switch"
        detail shouldNotContain "Switch("
        detail shouldNotContain "MR.strings.tsuzuki_navigation_back"
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
    fun `search compact cards use the shared cropped standard cover without an atmosphere layer`() {
        val search = readSource("search/TsuzukiSearchScreen.kt")
        val compactCard = search.substringAfter("internal fun SearchCompactCard(").substringBefore("\n@OptIn")
        val artwork = sourceBlockStartingAt(search, "internal fun SearchCompactArtwork(")
        val cover = readSource("components/TsuzukiDefaultSkinCover.kt")

        compactCard shouldContain ".width(TsuzukiDefaultSkinCoverSize.Standard.width)"
        compactCard shouldContain "SearchCompactArtwork("
        artwork shouldContain "TsuzukiDefaultSkinCover("
        artwork shouldContain "contentDescription = title"
        artwork shouldNotContain "ContentScale.Fit"
        artwork shouldNotContain "DecorativeSearchArtwork("
        artwork shouldNotContain "onAtmosphereLoadEvent ="
        cover shouldContain "MangaCoverFrame("
        cover shouldContain "ContentScale.Crop"
        cover shouldContain "DefaultSkinShape.Control"
        cover shouldContain "TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO"
        artwork shouldNotContain "onClick ="
        artwork shouldNotContain ".clickable("
        readMangaCoverSource() shouldContain "Book(2f / 3f)"
    }

    @Test
    fun `catalog and library covers use the shared standard and compact variants`() {
        val collections = readSource("collections/CollectionBrowseScreens.kt")
        val catalogTile = sourceBlockStartingAt(collections, "private fun CatalogGridItem(")
        val library = readSource("library/CanonicalLibraryScreen.kt")
        val libraryList = sourceBlockStartingAt(library, "private fun CanonicalLibraryList(")

        catalogTile shouldContain "TsuzukiDefaultSkinCoverSize.Grid"
        catalogTile shouldContain "contentAlignment = Alignment.TopCenter"
        catalogTile shouldContain "propagateMinConstraints = false"
        catalogTile shouldContain "Modifier.fillMaxWidth()"
        collections shouldContain "GridCells.Fixed(defaultSkinWorkGridColumnCount(maxWidth))"
        libraryList shouldContain "GridCells.Fixed(defaultSkinWorkGridColumnCount(maxWidth))"
        libraryList shouldContain "TsuzukiDefaultSkinCoverSize.Grid"
        libraryList shouldContain "item.coverUrl ?: item.sourceCover ?: item.localCoverUrl"
        libraryList shouldContain "fallbackData = listOf(item.sourceCover, item.sourceCover?.url, item.localCoverUrl)"
        libraryList shouldContain "item.startYear"
        libraryList shouldContain "maxLines = 2"
        libraryList shouldContain "Modifier.fillMaxWidth()"
        libraryList shouldNotContain "item.status"
        libraryList shouldNotContain "item.format"
        libraryList shouldNotContain "item.categories"
        libraryList shouldNotContain "ResolveCanonicalMetadata"
        libraryList shouldNotContain "MangaCover.Book("
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
